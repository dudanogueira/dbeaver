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
        try {
            if (client != null && !client.isReady()) {
                throw new DBException("Weaviate server is not ready");
            }
        } catch (IOException e) {
            throw new DBException("Weaviate connectivity check failed", e);
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
        return metadataFields;
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
