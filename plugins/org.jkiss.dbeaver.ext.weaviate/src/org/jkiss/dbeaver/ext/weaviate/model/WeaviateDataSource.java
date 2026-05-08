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
import io.weaviate.client6.v1.api.WeaviateClient;
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
import org.jkiss.dbeaver.model.qm.QMUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLDialect;
import org.jkiss.dbeaver.model.struct.DBSInstance;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class WeaviateDataSource extends AbstractDataSource
    implements DBSInstance, DBCExecutionContext, DBSObjectContainer, DBPAdaptable {

    private WeaviateClient client;
    private volatile List<WeaviateCollection> collections;
    private final long id;
    private final DBPExclusiveResource exclusiveLock = new SimpleExclusiveLock();

    public WeaviateDataSource(@NotNull DBPDataSourceContainer container) {
        super(container);
        this.id = AbstractExecutionContext.generateContextId();
        QMUtils.getDefaultHandler().handleContextOpen(this, false);
    }

    @NotNull
    @Override
    public DBPDataSourceInfo getInfo() {
        return new WeaviateDataSourceInfo();
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
    ) throws DBException {
        return this;
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
        String apiKey = cfg.getProviderProperty(WeaviateConstants.PROP_API_KEY);
        if (apiKey == null || apiKey.isEmpty()) {
            throw new DBException("API Key is required for Weaviate Cloud connections");
        }
        client = WeaviateClient.connectToWeaviateCloud(cloudUrl, apiKey);
    }

    private void connectToCustom(@NotNull DBPConnectionConfiguration cfg) {
        String scheme = cfg.getProviderProperty(WeaviateConstants.PROP_SCHEME);
        if (scheme == null || scheme.isEmpty()) scheme = WeaviateConstants.DEFAULT_SCHEME;

        String httpHost = cfg.getHostName();
        if (httpHost == null || httpHost.isEmpty()) httpHost = WeaviateConstants.DEFAULT_HTTP_HOST;

        int httpPort = WeaviateConstants.DEFAULT_HTTP_PORT;
        try {
            String portStr = cfg.getHostPort();
            if (portStr != null && !portStr.isEmpty()) httpPort = Integer.parseInt(portStr);
        } catch (NumberFormatException ignored) {
        }

        String grpcHost = cfg.getProviderProperty(WeaviateConstants.PROP_GRPC_HOST);
        if (grpcHost == null || grpcHost.isEmpty()) grpcHost = WeaviateConstants.DEFAULT_GRPC_HOST;

        int grpcPort = WeaviateConstants.DEFAULT_GRPC_PORT;
        try {
            String grpcPortStr = cfg.getProviderProperty(WeaviateConstants.PROP_GRPC_PORT);
            if (grpcPortStr != null && !grpcPortStr.isEmpty()) grpcPort = Integer.parseInt(grpcPortStr);
        } catch (NumberFormatException ignored) {
        }

        final Authentication auth = buildAuthentication(cfg);

        final String finalScheme = scheme;
        final String finalHttpHost = httpHost;
        final int finalHttpPort = httpPort;
        final String finalGrpcHost = grpcHost;
        final int finalGrpcPort = grpcPort;

        client = WeaviateClient.connectToCustom(c -> {
            c.scheme(finalScheme);
            c.httpHost(finalHttpHost);
            c.httpPort(finalHttpPort);
            c.grpcHost(finalGrpcHost);
            c.grpcPort(finalGrpcPort);
            if (auth != null) {
                c.authentication(auth);
            }
            return c;
        });
    }

    @Nullable
    private Authentication buildAuthentication(@NotNull DBPConnectionConfiguration cfg) {
        String authType = cfg.getProviderProperty(WeaviateConstants.PROP_AUTH_TYPE);
        if (authType == null || authType.isEmpty()) {
            // Backwards-compatible: if API key is set, use API key auth
            String apiKey = cfg.getProviderProperty(WeaviateConstants.PROP_API_KEY);
            return (apiKey != null && !apiKey.isEmpty()) ? Authentication.apiKey(apiKey) : null;
        }
        switch (authType) {
            case WeaviateConstants.AUTH_API_KEY: {
                String apiKey = cfg.getProviderProperty(WeaviateConstants.PROP_API_KEY);
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

    public WeaviateClient getClient() {
        return client;
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
