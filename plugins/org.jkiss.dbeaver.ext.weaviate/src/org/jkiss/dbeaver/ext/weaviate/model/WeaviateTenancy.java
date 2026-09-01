/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ext.weaviate.model;

import io.weaviate.client6.v1.api.collections.tenants.Tenant;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.runtime.ui.DBPPlatformUI;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Everything a collection does about tenants: listing them, caching their navigator nodes,
 * switching them on and off, and asking which one to read.
 * <p>
 * A collaborator rather than a utility class, because unlike the query translation this owns
 * state -- the tenant node cache, which the navigator asks for repeatedly while painting labels
 * and which has to be dropped the moment a tenant's state changes. Keeping the cache and the
 * things that invalidate it in one object is the point: a stale cache here shows a tenant as
 * active after it was switched off.
 * <p>
 * {@link WeaviateCollection} keeps thin delegating methods over this, so the navigator, the UI
 * bundle and the tests go on calling the collection. Not moved here on purpose:
 * {@code describeFailure}, which reads as tenant code but is a read-path error helper -- it walks
 * a failure's cause chain and merely special-cases an inactive tenant.
 */
final class WeaviateTenancy {

    private static final Log log = Log.getLog(WeaviateTenancy.class);

    /**
     * Beyond this many tenants the platform's choice dialog (a flat list of labels) stops being
     * usable, and the searchable "Select Tenant..." picker is the only sensible way in.
     */
    private static final int MAX_TENANTS_TO_PROMPT = 30;

    private final WeaviateCollection collection;
    private final WeaviateDataSource dataSource;

    /** Navigator nodes for the Tenants folder; dropped whenever a tenant's state changes. */
    private volatile List<WeaviateTenantNode> tenantNodes;

    WeaviateTenancy(@NotNull WeaviateCollection collection, @NotNull WeaviateDataSource dataSource) {
        this.collection = collection;
        this.dataSource = dataSource;
    }

    /**
     * True when the collection partitions its objects by tenant.
     * <p>
     * Weaviate rejects a query against such a collection unless it names a tenant, so the data
     * view has to ask which one before it can show anything.
     */
    boolean isMultiTenant() {
        return collection.getConfig().multiTenancy() != null && collection.getConfig().multiTenancy().enabled();
    }

    /**
     * Whether writing to an unknown tenant creates it, or null when the server never said.
     * <p>
     * Null is not the same as false. Weaviate omits these fields entirely on a server older than
     * 1.25.2, and a UI that renders the absence as an unticked box invites someone to "fix" a
     * setting that does not exist.
     */
    @Nullable
    Boolean getAutoTenantCreation() {
        return collection.getConfig().multiTenancy() == null ? null : collection.getConfig().multiTenancy().createAutomatically();
    }

    /** Whether reading an inactive tenant wakes it. See {@link #getAutoTenantCreation()} on null. */
    @Nullable
    Boolean getAutoTenantActivation() {
        return collection.getConfig().multiTenancy() == null ? null : collection.getConfig().multiTenancy().activateAutomatically();
    }

    /**
     * Turns automatic tenant creation and activation on or off.
     * <p>
     * Both travel in one request because they are one object to the server: the update carries a
     * whole multiTenancy block, so sending only one of them would silently reset the other to the
     * client's default.
     * <p>
     * {@code enabled} is always passed through unchanged. Weaviate will not switch multi-tenancy
     * itself on or off after a collection exists, and leaving it out of the block would ask it to.
     */
    void setAutoTenantOptions(
        @NotNull DBRProgressMonitor monitor,
        boolean autoCreation,
        boolean autoActivation
    ) throws DBException {
        if (!isMultiTenant()) {
            throw new DBException(collection.getName() + " is not multi-tenant");
        }
        monitor.subTask("Update multi-tenancy settings of " + collection.getName());
        try {
            dataSource.getClient().collections.use(collection.getName()).config.update(
                b -> b.multiTenancy(mt -> mt
                    .enabled(true)
                    .autoTenantCreation(autoCreation)
                    .autoTenantActivation(autoActivation)));
            // Read back rather than patching the local copy: the server is free to refuse or
            // adjust, and the checkboxes must show what it actually holds.
            collection.refreshConfig();
        } catch (Exception e) {
            throw new DBException(
                "Cannot update multi-tenancy settings of " + collection.getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Tenants defined for this collection with their states, ordered by name.
     * <p>
     * One request, no paging, even for a collection with thousands of tenants: the server answers
     * with the whole list and there is no endpoint that returns part of it. Measured against a
     * 4000-tenant collection this is around 190 KiB and 25 ms, so the cost of holding them all is
     * not what limits the UI -- rendering them is. Reading is the uncapped direction; writing
     * back is not, see {@link #TENANT_UPDATE_CHUNK}.
     */
    @NotNull
    List<WeaviateTenant> listTenants(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (!isMultiTenant()) {
            return List.of();
        }
        monitor.subTask("Read tenants of " + collection.getName());
        try {
            List<WeaviateTenant> tenants = new ArrayList<>();
            for (Tenant tenant : dataSource.getClient().collections.use(collection.getName()).tenants.list()) {
                if (tenant.name() != null && !tenant.name().isBlank()) {
                    tenants.add(new WeaviateTenant(
                        tenant.name(),
                        WeaviateTenantStatus.fromName(
                            tenant.status() == null ? null : tenant.status().name())));
                }
            }
            tenants.sort(Comparator.comparing(WeaviateTenant::name));
            return tenants;
        } catch (Exception e) {
            throw new DBException(
                "Cannot list tenants of " + collection.getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Tenants as navigator nodes, for the Tenants folder under a multi-tenant collection.
     * <p>
     * Cached, because the navigator asks repeatedly while painting labels, and dropped whenever
     * a state changes so the tree cannot go on showing a tenant as active after it was switched
     * off. A collection with thousands of tenants makes this folder large, which is the same
     * bargain the platform already makes for a schema with thousands of tables: it is lazy, so
     * nothing is read until someone expands it.
     */
    List<WeaviateTenantNode> getTenantNodes(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (!isMultiTenant()) {
            return List.of();
        }
        if (tenantNodes == null) {
            List<WeaviateTenant> tenants = listTenants(monitor);
            List<WeaviateTenantNode> nodes = new ArrayList<>(tenants.size());
            for (WeaviateTenant tenant : tenants) {
                nodes.add(new WeaviateTenantNode(collection, tenant));
            }
            tenantNodes = nodes;
        }
        return tenantNodes;
    }

    /**
     * Tenant nodes already in memory, or null if this collection's tenants have not been read.
     * <p>
     * For callers that run while a context menu is being built, where fetching would put a network
     * round trip on the UI thread.
     */
    @Nullable
    List<WeaviateTenantNode> getLoadedTenantNodes() {
        return tenantNodes;
    }

    /**
     * Forgets the cached tenant nodes. Called after any change of state, and available to the UI
     * so a navigator refresh shows what the server now holds.
     */
    void resetTenantCache() {
        tenantNodes = null;
    }

    /**
     * How many tenants go in one activate/deactivate request.
     * <p>
     * This is the server's hard limit, not a tuning choice. Weaviate rejects an update naming
     * more than 100 tenants with
     * {@code 422 maximum number of tenants allowed to be updated simultaneously is 100}
     * ({@code usecases/schema/tenant.go}, {@code validateTenants(..., allowOverHundred=false)}).
     * Creating tenants is uncapped -- only updates are limited -- which is why seeding thousands
     * works and switching them off in one go does not.
     * <p>
     * Chunking also buys a progress bar that moves and a Cancel that can be honoured between
     * chunks, but those are the secondary reasons. The limit is the reason.
     */
    public static final int TENANT_UPDATE_CHUNK = 100;

    /**
     * Activates or deactivates the named tenants, and reports how many actually changed.
     * <p>
     * Tenants already in the wanted state are dropped before anything is sent. The server would
     * accept them, but the count returned is shown to the user, and "deactivated 750" is a false
     * statement when 700 of them were already inactive.
     * <p>
     * Cancelling stops at a chunk boundary, so the work already sent stands. That is why the
     * dialog re-reads the list afterwards instead of assuming what it asked for is what happened.
     *
     * @return the number of tenants whose state was changed
     */
    int setTenantStatus(
        @NotNull DBRProgressMonitor monitor,
        @NotNull List<WeaviateTenant> tenants,
        @NotNull WeaviateTenantStatus target
    ) throws DBException {
        if (!target.isSettable()) {
            // OFFLOADED needs an offload module rather than a version, and the two transitional
            // states belong to the server. Refusing here keeps that decision in one place.
            throw new DBException("Tenants cannot be set to " + target.getLabel());
        }
        List<String> pending = new ArrayList<>();
        for (WeaviateTenant tenant : tenants) {
            if (tenant.status() != target) {
                pending.add(tenant.name());
            }
        }
        if (pending.isEmpty()) {
            return 0;
        }

        boolean activate = target == WeaviateTenantStatus.ACTIVE;
        monitor.beginTask(
            (activate ? "Activate " : "Deactivate ") + pending.size() + " tenants of " + collection.getName(),
            pending.size());
        try {
            var tenantsClient = dataSource.getClient().collections.use(collection.getName()).tenants;
            int done = 0;
            for (int from = 0; from < pending.size(); from += TENANT_UPDATE_CHUNK) {
                if (monitor.isCanceled()) {
                    break;
                }
                List<String> chunk = pending.subList(
                    from, Math.min(from + TENANT_UPDATE_CHUNK, pending.size()));
                monitor.subTask(chunk.get(0) + (chunk.size() > 1 ? " and " + (chunk.size() - 1) + " more" : ""));
                if (activate) {
                    tenantsClient.activate(chunk);
                } else {
                    tenantsClient.deactivate(chunk);
                }
                done += chunk.size();
                monitor.worked(chunk.size());
            }
            return done;
        } catch (Exception e) {
            throw new DBException("Cannot update tenants of " + collection.getName() + ": " + e.getMessage(), e);
        } finally {
            // Whatever happened, including a cancel partway, the cached states are now suspect.
            resetTenantCache();
            monitor.done();
        }
    }

    /**
     * Ask which tenant to read, when the data view is opened on a multi-tenant collection.
     * <p>
     * The choice cannot be defaulted: reading the wrong tenant returns real rows that are simply
     * someone else's, which is worse than showing nothing. Only interactive reads prompt -- an
     * export runs unattended and must not block on a dialog.
     *
     * @return the chosen tenant, or null if the user declined or there are none
     */
    @Nullable
    String promptForTenant(
        @NotNull DBCSession session,
        @NotNull DBRProgressMonitor monitor,
        @NotNull WeaviateQuerySpec spec
    ) {
        if (!session.getPurpose().isUser()) {
            return null;
        }
        List<WeaviateTenant> tenants;
        try {
            tenants = listTenants(monitor);
        } catch (DBException e) {
            log.warn("Cannot list tenants of " + collection.getName(), e);
            return null;
        }
        if (tenants.isEmpty()) {
            return null;
        }
        // The searchable picker handles any number of tenants, so prefer it whenever the UI
        // bundle has registered one.
        WeaviateTenantPrompt prompt = WeaviateTenantPrompt.getProvider();
        if (prompt != null) {
            return prompt.selectTenant(collection.getName(), tenants, spec.getTenant());
        }
        if (tenants.size() > MAX_TENANTS_TO_PROMPT) {
            // Fallback only. The platform dialog lays options out as a row of buttons, so past a
            // handful it is unusable; better to say nothing and let the message point at the
            // "Select Tenant..." command.
            log.debug(collection.getName() + " has " + tenants.size()
                + " tenants and no searchable picker is registered");
            return null;
        }
        // The fallback dialog takes plain labels, so the state is spelled into them -- it is the
        // one thing that decides whether the chosen tenant can actually be read.
        List<String> labels = new ArrayList<>(tenants.size());
        for (WeaviateTenant tenant : tenants) {
            labels.add(tenant.isActive()
                ? tenant.name()
                : tenant.name() + " (" + tenant.status().getLabel() + ")");
        }
        DBPPlatformUI.UserChoiceResponse response = DBWorkbench.getPlatformUI().showUserChoice(
            "Select tenant",
            "\"" + collection.getName() + "\" is a multi-tenant collection. Choose which tenant's data to show.",
            labels,
            List.of(),
            null,
            0);
        if (response.choiceIndex < 0 || response.choiceIndex >= tenants.size()) {
            return null;
        }
        return tenants.get(response.choiceIndex).name();
    }
}
