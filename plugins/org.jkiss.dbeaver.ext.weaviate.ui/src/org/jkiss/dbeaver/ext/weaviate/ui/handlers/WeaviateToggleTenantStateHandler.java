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
package org.jkiss.dbeaver.ext.weaviate.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseFolder;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateTenant;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateTenantNode;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateTenantStatus;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateNavigatorRefresh;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIIcon;
import org.jkiss.dbeaver.ui.UIUtils;

import java.lang.reflect.InvocationTargetException;
import java.text.MessageFormat;
import org.jkiss.utils.CommonUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Activates or deactivates the selected tenants straight from the tree.
 * <p>
 * One entry rather than two, naming the action it will perform. A menu offering both "Activate"
 * and "Deactivate" makes the reader work out which one applies; an entry that already says
 * "Deactivate Tenant" has answered that before it is read.
 * <p>
 * With a mixed selection the offer is Activate. That is the direction that restores access: an
 * unwanted activate costs memory until it is undone, an unwanted deactivate makes data unreadable
 * for whoever was using it.
 */
public class WeaviateToggleTenantStateHandler extends AbstractHandler implements IElementUpdater {

    private static final Log log = Log.getLog(WeaviateToggleTenantStateHandler.class);

    /** Folders that stand for every tenant of their collection. */
    private static final java.util.Set<String> TENANCY_FOLDERS =
        java.util.Set.of("multiTenancy", "tenants");

    /**
     * What pressing the entry would do to this selection: activate unless everything in it is
     * already active.
     */
    private static boolean wouldActivate(@NotNull List<WeaviateTenantNode> tenants) {
        for (WeaviateTenantNode tenant : tenants) {
            if (tenant.getTenantStatus() != WeaviateTenantStatus.ACTIVE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tenants the action would act on.
     * <p>
     * Either the tenant nodes that are selected, or -- when a Multi-Tenancy or Tenants folder is
     * selected -- every tenant of that collection. The folders are where someone looks when the
     * thought is "wake all of these up", and requiring them to expand the folder and select
     * thousands of rows first would be the wrong answer to that.
     *
     * @param monitor null to use only what is already loaded, for callers that must not fetch
     */
    /**
     * What the action resolved to: the tenants, or why there are none.
     * <p>
     * The reason exists because every way this can come back empty used to be a silent
     * {@code return}, and the handler then did nothing at all -- no error, no message. Selecting
     * tenants, pressing Activate and watching nothing happen is indistinguishable from a broken
     * action, and gives nobody anything to report.
     */
    private record Resolution(@NotNull List<WeaviateTenantNode> tenants, @Nullable String reason) {
        static Resolution of(@NotNull List<WeaviateTenantNode> tenants) {
            return new Resolution(tenants, null);
        }

        static Resolution refused(@NotNull String reason) {
            return new Resolution(List.of(), reason);
        }
    }

    @NotNull
    private static Resolution subjects(
        @Nullable ISelection selection, @Nullable DBRProgressMonitor monitor
    ) {
        if (selection == null) {
            return Resolution.refused("Nothing is selected.");
        }
        List<WeaviateTenantNode> tenants = new ArrayList<>();
        WeaviateCollection owner = null;

        for (DBNNode node : NavigatorUtils.getSelectedNodes(selection)) {
            List<WeaviateTenantNode> found = tenantsUnder(node, monitor);
            if (found.isEmpty() && !isTenancyFolder(node)) {
                return Resolution.refused(
                    "\"" + node.getNodeDisplayName() + "\" is not a tenant, so the selection "
                        + "cannot be acted on as one. Select tenants, or the Tenants folder.");
            }
            for (WeaviateTenantNode tenant : found) {
                if (!(tenant.getParentObject() instanceof WeaviateCollection collection)) {
                    return Resolution.refused(
                        "Tenant \"" + tenant.getName() + "\" has no collection behind it. "
                            + "Refresh the connection and try again.");
                }
                if (owner == null) {
                    owner = collection;
                } else if (!owner.getName().equals(collection.getName())) {
                    // One request per collection, so a selection spanning two is not one action.
                    // Compared by name rather than by identity: a refresh rebuilds the collection
                    // objects, so a selection made across one would fail an identity check while
                    // naming a single collection throughout.
                    return Resolution.refused(
                        "The selection spans " + owner.getName() + " and " + collection.getName()
                            + ". Tenants are changed one collection at a time.");
                }
                if (!tenant.getTenantStatus().isSettable()) {
                    // Offloading and onloading are the server's business; nothing to ask for.
                    return Resolution.refused(
                        "Tenant \"" + tenant.getName() + "\" is "
                            + tenant.getTenantStatus().getLabel()
                            + ", which only the server can change.");
                }
                tenants.add(tenant);
            }
        }
        if (tenants.isEmpty()) {
            return Resolution.refused("The selection holds no tenants.");
        }
        return Resolution.of(tenants);
    }

    /** Whether this node is a Multi-Tenancy or Tenants folder of a multi-tenant collection. */
    private static boolean isTenancyFolder(@NotNull DBNNode node) {
        return node instanceof DBNDatabaseFolder folder
            && TENANCY_FOLDERS.contains(folder.getNodeId())
            && WeaviateTenancyNodes.multiTenantCollection(node) != null;
    }

    @NotNull
    private static List<WeaviateTenantNode> tenantsUnder(
        @NotNull DBNNode node, @Nullable DBRProgressMonitor monitor
    ) {
        if (node instanceof DBNDatabaseNode databaseNode
            && databaseNode.getObject() instanceof WeaviateTenantNode tenant
        ) {
            return List.of(tenant);
        }
        if (!isTenancyFolder(node)) {
            return List.of();
        }
        WeaviateCollection collection = WeaviateTenancyNodes.multiTenantCollection(node);
        if (collection == null) {
            return List.of();
        }
        if (monitor == null) {
            List<WeaviateTenantNode> loaded = collection.getLoadedTenantNodes();
            return loaded == null ? List.of() : loaded;
        }
        try {
            return collection.getTenantNodes(monitor);
        } catch (Exception e) {
            log.debug("Cannot read tenants of " + collection.getName(), e);
            return List.of();
        }
    }

    @Override
    public void setEnabled(Object evaluationContext) {
        ISelection selection = WeaviateTenancyNodes.selectionOf(evaluationContext);
        if (selection == null) {
            setBaseEnabled(false);
            return;
        }
        List<DBNNode> nodes = NavigatorUtils.getSelectedNodes(selection);
        if (nodes.isEmpty()) {
            setBaseEnabled(false);
            return;
        }
        // Shape only. Whether a folder holds tenants worth changing needs them read, and a folder
        // that has not been expanded would have to be fetched to find out -- on the UI thread,
        // while the menu is being built.
        for (DBNNode node : nodes) {
            boolean tenantNode = node instanceof DBNDatabaseNode databaseNode
                && databaseNode.getObject() instanceof WeaviateTenantNode;
            if (!tenantNode && !isTenancyFolder(node)) {
                setBaseEnabled(false);
                return;
            }
        }
        setBaseEnabled(true);
    }

    @Override
    public Object execute(ExecutionEvent event) {
        Resolution[] holder = new Resolution[1];
        try {
            UIUtils.runInProgressService(monitor ->
                holder[0] = subjects(HandlerUtil.getCurrentSelection(event), monitor));
        } catch (InvocationTargetException e) {
            log.error("Cannot read tenants", e.getTargetException());
            DBWorkbench.getPlatformUI().showError(
                WeaviateUIMessages.tenant_manage_title_plain,
                "Cannot read the tenant list",
                e.getTargetException());
            return null;
        } catch (InterruptedException e) {
            return null;
        }
        Resolution resolution = holder[0];
        if (resolution == null || resolution.tenants().isEmpty()) {
            String reason = resolution == null ? "The tenant list could not be read."
                : CommonUtils.notEmpty(resolution.reason());
            DBWorkbench.getPlatformUI().showMessageBox(
                WeaviateUIMessages.tenant_manage_title_plain, reason, true);
            return null;
        }
        List<WeaviateTenantNode> nodes = resolution.tenants();
        WeaviateCollection collection = (WeaviateCollection) nodes.get(0).getParentObject();
        boolean activate = wouldActivate(nodes);
        WeaviateTenantStatus target =
            activate ? WeaviateTenantStatus.ACTIVE : WeaviateTenantStatus.INACTIVE;

        List<WeaviateTenant> tenants = new ArrayList<>(nodes.size());
        for (WeaviateTenantNode node : nodes) {
            tenants.add(new WeaviateTenant(node.getName(), node.getTenantStatus()));
        }

        int[] changed = {0};
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    changed[0] = collection.setTenantStatus(monitor, tenants, target);
                    WeaviateNavigatorRefresh.afterTenantChange(monitor, collection);
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot change tenant state", e.getTargetException());
            DBWorkbench.getPlatformUI().showError(
                WeaviateUIMessages.tenant_manage_title_plain,
                WeaviateUIMessages.tenant_manage_failed,
                e.getTargetException());
            return null;
        } catch (InterruptedException e) {
            return null;
        }
        if (changed[0] == 0) {
            // setTenantStatus sends nothing for a tenant already in the wanted state, which it
            // judges from what the tree holds. A zero here therefore means the tree and the server
            // disagreed, and saying so is better than a dialog that closes with nothing changed.
            DBWorkbench.getPlatformUI().showMessageBox(
                WeaviateUIMessages.tenant_manage_title_plain,
                MessageFormat.format(
                    "No tenant was changed: all {0} already looked {1} to the navigator. "
                        + "Refresh {2} and try again if the server disagrees.",
                    tenants.size(), target.getLabel(), collection.getName()),
                true);
        }
        return null;
    }

    /**
     * Names the direction and matches the icon to it, both read from the current selection.
     */
    @Override
    public void updateElement(UIElement element, Map parameters) {
        IWorkbenchWindow window = element.getServiceLocator().getService(IWorkbenchWindow.class);
        if (window == null || window.getSelectionService() == null) {
            return;
        }
        List<WeaviateTenantNode> tenants =
            subjects(window.getSelectionService().getSelection(), null).tenants();
        if (tenants.isEmpty()) {
            return;
        }
        boolean activate = wouldActivate(tenants);
        String label = activate
            ? WeaviateUIMessages.tenant_state_activate
            : WeaviateUIMessages.tenant_state_deactivate;
        element.setText(tenants.size() == 1
            ? label
            : MessageFormat.format(WeaviateUIMessages.tenant_state_many, label, tenants.size()));
        // A green bullet for the tenant that will answer afterwards, a dark one for the tenant
        // that will not -- the same on/off reading the status column gives, rather than a tick
        // and a cross, which would say "confirm/abort" instead of naming a state.
        element.setIcon(DBeaverIcons.getImageDescriptor(
            activate ? UIIcon.BULLET_GREEN : UIIcon.BULLET_BLACK));
    }
}
