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

import io.weaviate.client6.v1.api.Authentication;
import io.weaviate.client6.v1.api.InstanceMetadata;
import io.weaviate.client6.v1.api.WeaviateClient;
import io.weaviate.client6.v1.api.cluster.Node;
import io.weaviate.client6.v1.api.cluster.NodeVerbosity;
import io.weaviate.client6.v1.api.collections.CollectionConfig;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.weaviate.WeaviateConstants;
import org.jkiss.dbeaver.model.DBPAdaptable;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPDataSourceInfo;
import org.jkiss.dbeaver.model.DBPExclusiveResource;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionContextDefaults;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCInvalidatePhase;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.impl.AbstractDataSource;
import org.jkiss.dbeaver.model.impl.AbstractExecutionContext;
import org.jkiss.dbeaver.model.impl.SimpleExclusiveLock;
import org.jkiss.dbeaver.model.meta.Association;
import org.jkiss.dbeaver.model.meta.ForTest;
import org.jkiss.dbeaver.model.qm.QMUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLDialect;
import org.jkiss.dbeaver.model.struct.DBSInstance;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.jkiss.utils.CommonUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WeaviateDataSource extends AbstractDataSource
    implements DBSInstance, DBCExecutionContext, DBSObjectContainer, DBPAdaptable {

    private WeaviateClient client;
    private volatile List<WeaviateCollection> collections;
    private volatile List<WeaviateNode> nodes;
    private volatile InstanceMetadata cachedMetadata;
    private volatile List<WeaviateMetadataField> metadataFields;
    private volatile List<WeaviateModule> modules;
    private volatile List<WeaviateBackupEntry> backupEntries;
    private final long id;
    private final DBPExclusiveResource exclusiveLock = new SimpleExclusiveLock();
    /**
     * Per-collection query settings from the query panel, keyed by collection name.
     * <p>
     * Held here rather than on {@link WeaviateCollection} because the collection cache is
     * discarded and rebuilt on every refresh/invalidate, which would otherwise silently reset
     * the user's search mode and filters.
     */
    private final Map<String, WeaviateQuerySpec> querySpecs = new ConcurrentHashMap<>();

    public WeaviateDataSource(@NotNull DBPDataSourceContainer container) {
        super(container);
        this.id = AbstractExecutionContext.generateContextId();
        QMUtils.getDefaultHandler().handleContextOpen(this, false);
    }

    /**
     * The server version if it has already been fetched, else null.
     * <p>
     * Deliberately non-blocking, like {@link #getInfo()}. Gating runs from menu enablement, which
     * the navigator re-evaluates on every right-click -- a round-trip there would freeze the UI on
     * each one. Null is a safe answer: an unknown version means "available" (see
     * {@link WeaviateVersions}).
     */
    @Nullable
    private String knownServerVersion() {
        InstanceMetadata metadata = cachedMetadata;
        return metadata == null ? null : metadata.version();
    }

    /**
     * Whether the connected server is at least {@code major.minor}.
     * <p>
     * Named after the platform-wide convention ({@code JDBCDataSource#isServerVersionAtLeast},
     * with callers in mssql, postgresql, gaussdb and tidb). It differs from that one in what it
     * does when the version is not known: the platform returns false, this returns <b>true</b>.
     * That is right for JDBC, where the driver reports a version at connect time, and wrong here,
     * where the version arrives on a lazy {@code meta()} fetch and release candidates do not parse
     * at all -- so a strict reading would hide working features routinely rather than
     * exceptionally. See {@link WeaviateVersions} for the evidence.
     */
    public boolean isServerVersionAtLeast(int major, int minor) {
        return WeaviateVersions.isAtLeast(knownServerVersion(), major, minor, 0);
    }

    /** Whether the connected server has {@code feature}. Unknown version means yes. */
    public boolean supports(@NotNull WeaviateServerFeature feature) {
        return feature.isSupportedBy(knownServerVersion());
    }

    @NotNull
    @Override
    public DBPDataSourceInfo getInfo() {
        // Report the live server version when we already have it. getInfo() can be called
        // before/while connecting, so never trigger a round-trip here - fall back to the
        // "unknown" form instead of blocking or throwing.
        InstanceMetadata metadata = cachedMetadata;
        return new WeaviateDataSourceInfo(metadata == null ? null : metadata.version());
    }

    @NotNull
    @Override
    public DBCExecutionContext getDefaultContext(@NotNull DBRProgressMonitor monitor, boolean meta) {
        return this;
    }

    @NotNull
    @Override
    public DBCExecutionContext[] getAllContexts() {
        return new DBCExecutionContext[]{this};
    }

    @Override
    public long getContextId() {
        return id;
    }

    @NotNull
    @Override
    public String getContextName() {
        return "Weaviate Data Source";
    }

    @NotNull
    @Override
    public DBSInstance getOwnerInstance() {
        return this;
    }

    @Override
    public boolean isConnected() {
        return client != null;
    }

    @NotNull
    @Override
    public DBCSession openSession(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionPurpose purpose,
        @NotNull String task
    ) {
        return new WeaviateSession(monitor, purpose, task, this);
    }

    @Override
    public void checkContextAlive(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (client == null) {
            return;
        }
        // Liveness first, so the message can say which of the two failed. Asking only about
        // readiness reports a server that is down and a server that is still starting with the
        // same words, and those want opposite responses -- fix the address, versus wait.
        WeaviateHealth health = WeaviateHealth.probe(client);
        if (!health.isHealthy()) {
            throw new DBException("Weaviate server check failed: " + health.getSummary());
        }
    }

    @NotNull
    @Override
    public DBCExecutionContext openIsolatedContext(
        @NotNull DBRProgressMonitor monitor,
        @NotNull String purpose,
        @Nullable DBCExecutionContext initFrom
    ) {
        // Must NOT return `this`: the caller owns what it opens and will close it, which on the
        // data source itself disposes the shared client. Data transfer does precisely that, so
        // exporting a collection used to leave the connection dead.
        return new WeaviateSharedContext(this, purpose);
    }

    @Override
    public void invalidateContext(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCInvalidatePhase phase
    ) throws DBException {
        close();
        initialize(monitor);
    }

    @Nullable
    @Override
    public DBCExecutionContextDefaults getContextDefaults() {
        return null;
    }

    @Override
    public void initialize(@NotNull DBRProgressMonitor monitor) throws DBException {
        final DBPConnectionConfiguration cfg = container.getActualConnectionConfiguration();
        final String connType = cfg.getProviderProperty(WeaviateConstants.PROP_CONNECTION_TYPE);
        try {
            if (WeaviateConstants.CONN_TYPE_CLOUD.equals(connType)) {
                connectToCloud(cfg);
            } else {
                connectToCustom(cfg);
            }
            if (!client.isReady()) {
                throw new DBException("Weaviate server is not ready");
            }
        } catch (DBException e) {
            throw e;
        } catch (Exception e) {
            throw new DBException("Failed to connect to Weaviate: " + e.getMessage(), e);
        }
    }

    private void connectToCloud(@NotNull DBPConnectionConfiguration cfg) throws DBException {
        String cloudUrl = cfg.getProviderProperty(WeaviateConstants.PROP_CLOUD_URL);
        if (cloudUrl == null || cloudUrl.isEmpty()) {
            throw new DBException("Weaviate Cloud URL is required");
        }
        String apiKey = readApiKey(cfg);
        if (apiKey == null || apiKey.isEmpty()) {
            throw new DBException("API Key is required for Weaviate Cloud connections");
        }
        Map<String, String> modelHeaders = WeaviateModelHeaders.resolve(cfg);
        client = WeaviateClient.connectToWeaviateCloud(cloudUrl, apiKey, c -> {
            if (!modelHeaders.isEmpty()) {
                c.setHeaders(modelHeaders);
            }
            return c;
        });
    }

    private void connectToCustom(@NotNull DBPConnectionConfiguration cfg) throws DBException {
        String scheme = cfg.getProviderProperty(WeaviateConstants.PROP_SCHEME);
        if (scheme == null || scheme.isEmpty()) scheme = WeaviateConstants.DEFAULT_SCHEME;

        String httpHost = cfg.getHostName();
        if (httpHost == null || httpHost.isEmpty()) httpHost = WeaviateConstants.DEFAULT_HTTP_HOST;

        int httpPort = parsePort(cfg.getHostPort(), WeaviateConstants.DEFAULT_HTTP_PORT, "HTTP");

        String grpcHost = cfg.getProviderProperty(WeaviateConstants.PROP_GRPC_HOST);
        if (grpcHost == null || grpcHost.isEmpty()) grpcHost = WeaviateConstants.DEFAULT_GRPC_HOST;

        int grpcPort = parsePort(
            cfg.getProviderProperty(WeaviateConstants.PROP_GRPC_PORT),
            WeaviateConstants.DEFAULT_GRPC_PORT, "gRPC");

        final Authentication auth = buildAuthentication(cfg);

        final String finalScheme = scheme;
        final String finalHttpHost = httpHost;
        final int finalHttpPort = httpPort;
        final String finalGrpcHost = grpcHost;
        final int finalGrpcPort = grpcPort;

        final Map<String, String> modelHeaders = WeaviateModelHeaders.resolve(cfg);

        client = WeaviateClient.connectToCustom(c -> {
            c.scheme(finalScheme);
            c.httpHost(finalHttpHost);
            c.httpPort(finalHttpPort);
            c.grpcHost(finalGrpcHost);
            c.grpcPort(finalGrpcPort);
            if (auth != null) {
                c.authentication(auth);
            }
            if (!modelHeaders.isEmpty()) {
                c.setHeaders(modelHeaders);
            }
            return c;
        });
    }

    /**
     * Parse a port, falling back to {@code defaultPort} when unset.
     * <p>
     * A malformed or out-of-range value is rejected rather than silently replaced: connecting to
     * a different port than the user typed produces a confusing "connection refused" far from
     * the real cause.
     */
    @ForTest
    static int parsePort(@Nullable String value, int defaultPort, @NotNull String label) throws DBException {
        if (value == null || value.isBlank()) {
            return defaultPort;
        }
        int port;
        try {
            port = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new DBException("Invalid " + label + " port \"" + value + "\": not a number");
        }
        if (port < 1 || port > 65535) {
            throw new DBException("Invalid " + label + " port " + port + ": must be between 1 and 65535");
        }
        return port;
    }

    /**
     * Read the Weaviate API key, preferring the secrets store.
     * <p>
     * The key used to live in a provider property, which is serialized in clear text into
     * {@code data-sources.json}. It now lives in an auth property (secrets), but connections
     * saved by an older build still carry the plaintext one, so that location stays readable.
     * It is rewritten to the secure location the next time the connection is saved.
     */
    @Nullable
    static String readApiKey(@NotNull DBPConnectionConfiguration cfg) {
        String secure = cfg.getAuthProperty(WeaviateConstants.PROP_API_KEY);
        if (secure != null && !secure.isEmpty()) {
            return secure;
        }
        return cfg.getProviderProperty(WeaviateConstants.PROP_API_KEY);
    }

    @Nullable
    private Authentication buildAuthentication(@NotNull DBPConnectionConfiguration cfg) {
        String authType = cfg.getProviderProperty(WeaviateConstants.PROP_AUTH_TYPE);
        if (authType == null || authType.isEmpty()) {
            // Backwards-compatible: if API key is set, use API key auth
            String apiKey = readApiKey(cfg);
            return (apiKey != null && !apiKey.isEmpty()) ? Authentication.apiKey(apiKey) : null;
        }
        switch (authType) {
            case WeaviateConstants.AUTH_API_KEY: {
                String apiKey = readApiKey(cfg);
                return (apiKey != null && !apiKey.isEmpty()) ? Authentication.apiKey(apiKey) : null;
            }
            case WeaviateConstants.AUTH_USER_PASSWORD: {
                String user = cfg.getUserName();
                String password = cfg.getUserPassword();
                if (user != null && !user.isEmpty() && password != null && !password.isEmpty()) {
                    return Authentication.resourceOwnerPassword(user, password, null);
                }
                return null;
            }
            case WeaviateConstants.AUTH_NONE:
            default:
                return null;
        }
    }

    @Override
    public void close() {
        collections = null;
        nodes = null;
        cachedMetadata = null;
        metadataFields = null;
        modules = null;
        backupEntries = null;
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
            client = null;
        }
        QMUtils.getDefaultHandler().handleContextClose(this);
    }

    @NotNull
    @Override
    public DBSInstance getDefaultInstance() {
        return this;
    }

    @NotNull
    @Override
    public Collection<? extends DBSInstance> getAvailableInstances() {
        return Collections.singletonList(this);
    }

    @Override
    public void shutdown(@NotNull DBRProgressMonitor monitor) {
        close();
    }

    @NotNull
    @Override
    public DBPExclusiveResource getExclusiveLock() {
        return exclusiveLock;
    }

    /**
     * Collections whose next reads may execute the full remembered spec. Armed by the Query
     * panel's Run and disarmed when a fresh viewer opens, so a remembered search or generative
     * task never re-fires just because its tab was reopened -- see WeaviateQuerySpec#isAutoRunSafe.
     * Deliberately not persisted: "the user just pressed Run" is only ever true in the moment.
     */
    private final java.util.Set<String> armedRuns = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public void armRun(@NotNull String collectionName) {
        armedRuns.add(collectionName);
    }

    public void disarmRun(@NotNull String collectionName) {
        armedRuns.remove(collectionName);
    }

    public boolean isRunArmed(@NotNull String collectionName) {
        return armedRuns.contains(collectionName);
    }

    /**
     * Query settings previously chosen for {@code collectionName}, or a plain fetch if none.
     */
    @NotNull
    WeaviateQuerySpec getQuerySpec(@NotNull String collectionName) {
        WeaviateQuerySpec spec = querySpecs.get(collectionName);
        if (spec != null) {
            return spec;
        }
        // No panel interaction for this collection yet. Vectors stay out of this default:
        // embeddings are hundreds of columns wide and make the grid unreadable. Export is not
        // affected -- WeaviateCollection#readData restores the connection setting for
        // non-interactive reads, which is where the embeddings actually matter.
        return WeaviateQuerySpec.builder(WeaviateQueryMode.FETCH)
            .includeVector(false)
            .build();
    }

    /**
     * Whether the Query panel has set a spec for this collection, i.e. the user made an explicit
     * choice rather than getting the default.
     */
    boolean hasQuerySpec(@NotNull String collectionName) {
        return querySpecs.get(collectionName) != null;
    }

    /**
     * Whether this connection asks for embeddings by default (connection setting, off unless set).
     */
    public boolean isIncludeVectorsByDefault() {
        // getBoolean(value, default) honours an explicit "false" and only falls back when the
        // property was never set - so turning the setting off on a connection sticks.
        return CommonUtils.getBoolean(
            container.getActualConnectionConfiguration()
                .getProviderProperty(WeaviateConstants.PROP_INCLUDE_VECTORS),
            WeaviateConstants.DEFAULT_INCLUDE_VECTORS);
    }

    void setQuerySpec(@NotNull String collectionName, @Nullable WeaviateQuerySpec spec) {
        if (spec == null) {
            querySpecs.remove(collectionName);
        } else {
            querySpecs.put(collectionName, spec);
        }
    }

    /**
     * Base URL for direct REST calls, without a trailing slash.
     * <p>
     * Needed by {@link WeaviateSchemaRest}, which bypasses the client to send a schema document
     * unmodified. Derived from the same connection settings the client itself was built from.
     */
    @NotNull
    String getRestBaseUrl() throws DBException {
        DBPConnectionConfiguration cfg = container.getActualConnectionConfiguration();
        if (WeaviateConstants.CONN_TYPE_CLOUD.equals(
            cfg.getProviderProperty(WeaviateConstants.PROP_CONNECTION_TYPE))) {
            String cloudUrl = cfg.getProviderProperty(WeaviateConstants.PROP_CLOUD_URL);
            if (cloudUrl == null || cloudUrl.isBlank()) {
                throw new DBException("Weaviate Cloud URL is not configured");
            }
            String trimmed = cloudUrl.strip();
            // Cloud URLs are often pasted without a scheme.
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                trimmed = "https://" + trimmed;
            }
            return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        }
        String scheme = cfg.getProviderProperty(WeaviateConstants.PROP_SCHEME);
        if (scheme == null || scheme.isEmpty()) {
            scheme = WeaviateConstants.DEFAULT_SCHEME;
        }
        String host = cfg.getHostName();
        if (host == null || host.isEmpty()) {
            host = WeaviateConstants.DEFAULT_HTTP_HOST;
        }
        int port = parsePort(cfg.getHostPort(), WeaviateConstants.DEFAULT_HTTP_PORT, "HTTP");
        return scheme + "://" + host + ":" + port;
    }

    /**
     * Whether direct REST calls can authenticate on this connection.
     * <p>
     * False for OIDC (username/password), whose tokens are minted inside the client with no public
     * accessor. Callers that only need to <em>read</em> should fall back to the client API rather
     * than fail - see {@code WeaviateCollection#getDefinitionNodes}.
     */
    boolean isRestApiAvailable() {
        DBPConnectionConfiguration cfg = container.getActualConnectionConfiguration();
        return !WeaviateConstants.AUTH_USER_PASSWORD.equals(
            cfg.getProviderProperty(WeaviateConstants.PROP_AUTH_TYPE));
    }

    /**
     * {@code Authorization} header value for direct REST calls, or {@code null} when the
     * connection is anonymous.
     * <p>
     * Only API-key authentication is covered. OIDC modes (username/password) mint short-lived
     * tokens through machinery the client keeps internal and does not expose, so there is no
     * supported way to reuse that credential here.
     *
     * @throws DBException if the connection uses an auth mode this path cannot satisfy
     */
    @Nullable
    String getRestAuthorizationHeader() throws DBException {
        DBPConnectionConfiguration cfg = container.getActualConnectionConfiguration();
        String authType = cfg.getProviderProperty(WeaviateConstants.PROP_AUTH_TYPE);
        boolean cloud = WeaviateConstants.CONN_TYPE_CLOUD.equals(
            cfg.getProviderProperty(WeaviateConstants.PROP_CONNECTION_TYPE));
        String apiKey = readApiKey(cfg);

        if (cloud || WeaviateConstants.AUTH_API_KEY.equals(authType)
            || (authType == null && apiKey != null && !apiKey.isEmpty())) {
            if (apiKey == null || apiKey.isEmpty()) {
                throw new DBException("API key is not configured for this connection");
            }
            return "Bearer " + apiKey;
        }
        if (WeaviateConstants.AUTH_USER_PASSWORD.equals(authType)) {
            throw new DBException(
                "Creating a collection from a raw definition is not supported on connections using"
                    + " username/password (OIDC) authentication. Use API key authentication instead.");
        }
        return null;
    }

    /**
     * Add a newly created collection to the cached list, keeping that exact instance.
     * <p>
     * Deliberately not an {@code invalidateCollections()} + reload: the navigator resolves the
     * node for a new object by identity, so the instance handed back from
     * {@code WeaviateCollectionManager#createNewObject} has to be the one that ends up in the
     * tree. Reloading would substitute an equal-but-different object and break that lookup.
     * <p>
     * No-op if the cache has not been populated yet - the collection will simply be picked up by
     * the first load.
     */
    public void addCollection(@NotNull WeaviateCollection collection) {
        synchronized (this) {
            List<WeaviateCollection> current = collections;
            if (current == null) {
                return;
            }
            List<WeaviateCollection> updated = new ArrayList<>(current);
            updated.removeIf(c -> collection.getName().equals(c.getName()));
            updated.add(collection);
            DBUtils.orderObjects(updated);
            collections = updated;
        }
    }

    @Association
    public List<WeaviateCollection> getCollections(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (collections == null) {
            synchronized (this) {
                if (collections == null) {
                    collections = loadCollections();
                }
            }
        }
        return collections;
    }

    private List<WeaviateCollection> loadCollections() throws DBException {
        try {
            List<CollectionConfig> configs = client.collections.list();
            List<WeaviateCollection> result = new ArrayList<>(configs.size());
            for (CollectionConfig config : configs) {
                result.add(new WeaviateCollection(this, config));
            }
            DBUtils.orderObjects(result);
            return result;
        } catch (IOException e) {
            throw new DBException("Failed to list Weaviate collections", e);
        }
    }

    @Association
    public List<WeaviateNode> getNodes(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (nodes == null) {
            synchronized (this) {
                if (nodes == null) {
                    nodes = loadNodes();
                }
            }
        }
        return nodes;
    }

    private List<WeaviateNode> loadNodes() throws DBException {
        try {
            List<Node> rawNodes = client.cluster.listNodes(b -> b.verbosity(NodeVerbosity.VERBOSE));
            List<WeaviateNode> result = new ArrayList<>(rawNodes.size());
            for (Node n : rawNodes) {
                result.add(new WeaviateNode(this, n));
            }
            DBUtils.orderObjects(result);
            return result;
        } catch (IOException e) {
            throw new DBException("Failed to list Weaviate cluster nodes", e);
        }
    }

    private InstanceMetadata getInstanceMetadata() throws DBException {
        if (cachedMetadata == null) {
            synchronized (this) {
                if (cachedMetadata == null) {
                    try {
                        cachedMetadata = client.meta();
                    } catch (IOException e) {
                        throw new DBException("Failed to fetch Weaviate server metadata", e);
                    }
                }
            }
        }
        return cachedMetadata;
    }

    @Association
    public List<WeaviateMetadataField> getMetadataFields(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (metadataFields == null) {
            synchronized (this) {
                if (metadataFields == null) {
                    InstanceMetadata m = getInstanceMetadata();
                    // Reflection-based extraction picks up future InstanceMetadata fields automatically.
                    // We exclude `modules` here because it has its own folder.
                    List<WeaviateMetadataField> all = WeaviateRecordIntrospect.toFields(this, m);
                    List<WeaviateMetadataField> filtered = new ArrayList<>(all.size());
                    for (WeaviateMetadataField f : all) {
                        if (!f.getFieldName().startsWith("modules")) {
                            filtered.add(f);
                        }
                    }
                    metadataFields = filtered;
                }
            }
        }
        // Health goes on top, and is probed on every call rather than joining the cache above.
        // meta() is cached because a version and a module list do not change; liveness does, and a
        // remembered "ready: true" is not stale so much as untrue. Both probes are auth-free and
        // answer in about 2ms, so re-asking costs nothing worth saving.
        List<WeaviateMetadataField> withHealth = new ArrayList<>(metadataFields.size() + 2);
        withHealth.addAll(healthFields());
        withHealth.addAll(metadataFields);
        return withHealth;
    }

    /**
     * The live/ready rows shown at the top of the Server Metadata folder.
     * <p>
     * Empty when there is no client to ask -- the folder is only reachable on a live connection,
     * but a disconnect racing a refresh should show nothing rather than claim the server is down.
     */
    @NotNull
    private List<WeaviateMetadataField> healthFields() {
        WeaviateClient current = client;
        if (current == null) {
            return Collections.emptyList();
        }
        WeaviateHealth health = WeaviateHealth.probe(current);
        List<WeaviateMetadataField> fields = new ArrayList<>(2);
        fields.add(new WeaviateMetadataField(this, "live", String.valueOf(health.isLive())));
        fields.add(new WeaviateMetadataField(this, "ready", String.valueOf(health.isReady())));
        return fields;
    }

    /**
     * A point-in-time health probe, for the Server Status action.
     *
     * @throws DBException when the connection is not open; a server that is merely down is a
     *                     result, not an error
     */
    @NotNull
    public WeaviateHealth probeHealth(@NotNull DBRProgressMonitor monitor) throws DBException {
        WeaviateClient current = client;
        if (current == null) {
            throw new DBException("Not connected to Weaviate");
        }
        return WeaviateHealth.probe(current);
    }

    @Association
    public List<WeaviateModule> getModules(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (modules == null) {
            synchronized (this) {
                if (modules == null) {
                    InstanceMetadata m = getInstanceMetadata();
                    List<WeaviateModule> result = new ArrayList<>();
                    if (m != null && m.modules() != null) {
                        java.util.Map<String, Object> sorted = new java.util.TreeMap<>(m.modules());
                        for (java.util.Map.Entry<String, Object> e : sorted.entrySet()) {
                            result.add(new WeaviateModule(this, e.getKey(), e.getValue()));
                        }
                    }
                    modules = result;
                }
            }
        }
        return modules;
    }

    /**
     * The backup backends, always all four, plus one advisory row when none of them is enabled.
     * <p>
     * Nothing here is gated on a module being present -- the folder has to appear on a server
     * with no backup module at all, because "backups are not set up" is a thing worth being told
     * and an absent folder tells nobody anything.
     */
    @Association
    public List<WeaviateBackupEntry> getBackupEntries(@NotNull DBRProgressMonitor monitor)
        throws DBException {
        if (backupEntries == null) {
            synchronized (this) {
                if (backupEntries == null) {
                    InstanceMetadata m = getInstanceMetadata();
                    java.util.Map<String, Object> modules =
                        m == null || m.modules() == null ? java.util.Map.of() : m.modules();

                    List<WeaviateBackupEntry> result = new ArrayList<>();
                    boolean anyAvailable = false;
                    for (String backendId : WeaviateBackupBackend.KNOWN) {
                        Object module = modules.get(WeaviateBackupBackend.moduleNameFor(backendId));
                        boolean available = module != null;
                        anyAvailable |= available;
                        result.add(new WeaviateBackupBackend(
                            this, backendId, available, destinationOf(module)));
                    }
                    if (!anyAvailable) {
                        result.add(new WeaviateBackupAdvice(this));
                    }
                    // Read each enabled backend's listing now, so the row can carry its count.
                    // One request per enabled backend, and on nearly every server that is one.
                    for (WeaviateBackupEntry entry : result) {
                        if (entry instanceof WeaviateBackupBackend backend) {
                            backend.primeBackups(monitor);
                        }
                    }
                    backupEntries = result;
                }
            }
        }
        return backupEntries;
    }

    /**
     * Where a backend writes, as it describes itself in {@code /v1/meta}.
     * <p>
     * Each backup module reports its own destination under its own key -- filesystem says
     * {@code backupsPath}, the object stores say {@code bucketName} -- so this reads the first one
     * that is there rather than assuming a shape. Null is a fine answer; the row just shows no
     * destination.
     */
    @Nullable
    private static String destinationOf(@Nullable Object module) {
        if (!(module instanceof java.util.Map<?, ?> map)) {
            return null;
        }
        for (String key : new String[]{"backupsPath", "bucketName", "bucket", "container", "path"}) {
            Object value = map.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return null;
    }

    /**
     * Backups held by one backend, newest first, which is the server's own default ordering.
     */
    @NotNull
    public List<WeaviateBackup> listBackups(
        @NotNull DBRProgressMonitor monitor, @NotNull String backendId
    ) throws DBException {
        monitor.subTask("Read backups from " + backendId);
        try {
            List<WeaviateBackup> result = new ArrayList<>();
            for (io.weaviate.client6.v1.api.backup.Backup b : getClient().backup.list(backendId)) {
                result.add(toModel(b, backendId));
            }
            return result;
        } catch (Exception e) {
            throw new DBException(
                "Cannot list backups of " + backendId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Translates the client's backup record across the bundle boundary.
     * <p>
     * The status is resolved from the enum's <em>name</em> rather than switched on, because the
     * client's enum is missing TRANSFERRED and Gson answers null for a name it does not know --
     * see {@link WeaviateBackupStatus}. Reading {@code status()} and calling a method on it would
     * throw partway through every restore.
     */
    @NotNull
    static WeaviateBackup toModel(
        @NotNull io.weaviate.client6.v1.api.backup.Backup b, @NotNull String backendId
    ) {
        return new WeaviateBackup(
            b.id(),
            b.backend() == null ? backendId : b.backend(),
            b.path(),
            b.includesCollections() == null ? List.of() : b.includesCollections(),
            WeaviateBackupStatus.fromName(b.status() == null ? null : b.status().name()),
            b.error(),
            b.startedAt(),
            b.completedAt(),
            b.sizeGiB());
    }

    /**
     * Starts a backup and returns as soon as the server has accepted it.
     * <p>
     * Deliberately does not wait. The client offers {@code Backup.waitForCompletion}, which
     * blocks inside the client with no way to see which phase it is in, cannot be cancelled from
     * outside, and gives up after a fixed hour -- so a large backup that is still running would
     * report as failed. Callers poll {@link #getBackupStatus} instead, which costs a loop and buys
     * a progress line and a Cancel that reaches the server.
     *
     * @param include collections to back up, or empty for all
     * @param exclude collections to skip; the server refuses a request carrying both
     */
    @NotNull
    public WeaviateBackup startBackup(
        @NotNull DBRProgressMonitor monitor,
        @NotNull String backendId,
        @NotNull String backupId,
        @NotNull List<String> include,
        @NotNull List<String> exclude,
        @Nullable Integer cpuPercentage
    ) throws DBException {
        if (!include.isEmpty() && !exclude.isEmpty()) {
            // The server says "malformed request: 'include' and 'exclude' cannot both contain
            // values"; saying it here keeps the round trip out of an obvious mistake.
            throw new DBException("A backup can name collections to include or to exclude, not both");
        }
        monitor.subTask("Start backup " + backupId);
        try {
            io.weaviate.client6.v1.api.backup.Backup started =
                getClient().backup.create(backupId, backendId, b -> {
                    if (!include.isEmpty()) b.includeCollections(include);
                    if (!exclude.isEmpty()) b.excludeCollections(exclude);
                    if (cpuPercentage != null) b.cpuPercentage(cpuPercentage);
                    return b;
                });
            return toModel(started, backendId);
        } catch (Exception e) {
            throw new DBException("Cannot start backup " + backupId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Starts a restore. Same no-wait reasoning as {@link #startBackup}.
     * <p>
     * Restoring a collection that already exists fails, and fails after the data has been staged
     * rather than up front -- the server treats it as a per-class failure during the schema apply.
     * Callers are expected to have said so before getting here.
     */
    @NotNull
    public WeaviateBackup startRestore(
        @NotNull DBRProgressMonitor monitor,
        @NotNull String backendId,
        @NotNull String backupId,
        @NotNull List<String> include,
        @NotNull List<String> exclude,
        @Nullable Integer cpuPercentage
    ) throws DBException {
        if (!include.isEmpty() && !exclude.isEmpty()) {
            throw new DBException("A restore can name collections to include or to exclude, not both");
        }
        monitor.subTask("Start restore of " + backupId);
        try {
            io.weaviate.client6.v1.api.backup.Backup started =
                getClient().backup.restore(backupId, backendId, b -> {
                    if (!include.isEmpty()) b.includeCollections(include);
                    if (!exclude.isEmpty()) b.excludeCollections(exclude);
                    if (cpuPercentage != null) b.cpuPercentage(cpuPercentage);
                    return b;
                });
            return toModel(started, backendId);
        } catch (Exception e) {
            throw new DBException("Cannot restore " + backupId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Current state of a create or a restore.
     * <p>
     * Empty is a real answer, not a failure: a backup that has never been restored has no restore
     * status, and the server says so with a 404 that the client models as an empty Optional.
     */
    @NotNull
    public java.util.Optional<WeaviateBackup> getBackupStatus(
        @NotNull DBRProgressMonitor monitor,
        @NotNull String backendId,
        @NotNull String backupId,
        boolean restore
    ) throws DBException {
        try {
            java.util.Optional<io.weaviate.client6.v1.api.backup.Backup> found = restore
                ? getClient().backup.getRestoreStatus(backupId, backendId)
                : getClient().backup.getCreateStatus(backupId, backendId);
            return found.map(b -> toModel(b, backendId));
        } catch (Exception e) {
            throw new DBException(
                "Cannot read the status of " + backupId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Asks the server to stop a running create or restore.
     * <p>
     * The two are separate calls on purpose. {@code Backup.cancel(client)} looks like it would do
     * either, but it forwards to the create endpoint whatever the operation is, so cancelling a
     * restore through it silently addresses the wrong thing.
     */
    public void cancelBackup(
        @NotNull DBRProgressMonitor monitor,
        @NotNull String backendId,
        @NotNull String backupId,
        boolean restore
    ) throws DBException {
        monitor.subTask("Cancel " + backupId);
        try {
            if (restore) {
                getClient().backup.cancelRestore(backupId, backendId);
            } else {
                getClient().backup.cancelCreate(backupId, backendId);
            }
        } catch (Exception e) {
            throw new DBException("Cannot cancel " + backupId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Whether the server has any backend to write a backup to.
     * <p>
     * Reads the module list, which is already cached, rather than the backup entries -- this is
     * asked while building a context menu, and listing backups there would put a request on the
     * paint path.
     */
    public boolean hasAvailableBackupBackend() {
        InstanceMetadata m;
        try {
            m = getInstanceMetadata();
        } catch (DBException e) {
            return false;
        }
        if (m == null || m.modules() == null) {
            return false;
        }
        for (String backendId : WeaviateBackupBackend.KNOWN) {
            if (m.modules().containsKey(WeaviateBackupBackend.moduleNameFor(backendId))) {
                return true;
            }
        }
        return false;
    }

    /** The first backend that can actually be written to, or null when there is none. */
    @Nullable
    public WeaviateBackupBackend getDefaultBackupBackend(@NotNull DBRProgressMonitor monitor)
        throws DBException {
        for (WeaviateBackupEntry entry : getBackupEntries(monitor)) {
            if (entry instanceof WeaviateBackupBackend backend && backend.isAvailable()) {
                return backend;
            }
        }
        return null;
    }

    /**
     * Deletes several collections as one operation.
     * <p>
     * Weaviate has no endpoint that takes a list, so this is still a request per collection -- but
     * one command, one confirmation and one progress bar, rather than the platform's per-object
     * delete which stages a separate command for each and reports them one at a time.
     * <p>
     * Failures are collected rather than thrown at the first one. Stopping halfway through a bulk
     * delete leaves the user to work out which half, and the ones that did go are already gone.
     *
     * @return the names that could not be deleted, with the reason
     */
    @NotNull
    public java.util.Map<String, String> deleteCollections(
        @NotNull DBRProgressMonitor monitor, @NotNull List<String> names
    ) {
        java.util.Map<String, String> failed = new java.util.LinkedHashMap<>();
        monitor.beginTask("Delete " + names.size() + " collections", names.size());
        try {
            for (String name : names) {
                if (monitor.isCanceled()) {
                    break;
                }
                monitor.subTask(name);
                try {
                    getClient().collections.delete(name);
                } catch (Exception e) {
                    failed.put(name, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
                monitor.worked(1);
            }
        } finally {
            invalidateCollections();
            monitor.done();
        }
        return failed;
    }

    /**
     * Deletes every collection, in one request.
     * <p>
     * The client has a {@code deleteAll} for exactly this, so "delete everything" is one call
     * rather than a loop that could half-succeed. It also means a collection created between the
     * listing and the delete goes too, which is what "all" was asked to mean.
     */
    public void deleteAllCollections(@NotNull DBRProgressMonitor monitor) throws DBException {
        monitor.subTask("Delete all collections");
        try {
            getClient().collections.deleteAll();
        } catch (Exception e) {
            throw new DBException("Cannot delete all collections: " + e.getMessage(), e);
        } finally {
            invalidateCollections();
        }
    }

    /** Forgets cached backup listings, so a refresh shows what the server now holds. */
    public void resetBackupCache() {
        List<WeaviateBackupEntry> entries = backupEntries;
        if (entries != null) {
            for (WeaviateBackupEntry entry : entries) {
                if (entry instanceof WeaviateBackupBackend backend) {
                    backend.resetBackupCache();
                }
            }
        }
    }

    public WeaviateClient getClient() {
        return client;
    }

    public void invalidateCollections() {
        synchronized (this) {
            collections = null;
        }
    }

    // DBSObjectContainer

    @NotNull
    @Override
    public Collection<WeaviateCollection> getChildren(@NotNull DBRProgressMonitor monitor) throws DBException {
        return getCollections(monitor);
    }

    @Override
    public WeaviateCollection getChild(@NotNull DBRProgressMonitor monitor, @NotNull String childName) throws DBException {
        return DBUtils.findObject(getCollections(monitor), childName);
    }

    @NotNull
    @Override
    public Class<? extends DBSObject> getPrimaryChildType(@Nullable DBRProgressMonitor monitor) {
        return WeaviateCollection.class;
    }

    @Override
    public void cacheStructure(@NotNull DBRProgressMonitor monitor, int scope) throws DBException {
        getCollections(monitor);
    }

    @Override
    public <T> T getAdapter(@NotNull Class<T> adapter) {
        if (adapter == DBSObjectContainer.class) {
            return adapter.cast(this);
        }
        return null;
    }

    @NotNull
    @Override
    public SQLDialect getSQLDialect() {
        return new WeaviateDialect();
    }
}
