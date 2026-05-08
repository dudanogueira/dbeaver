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
package org.jkiss.dbeaver.ext.weaviate;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.connection.DBPDriver;
import org.jkiss.dbeaver.model.impl.AbstractDataSourceProvider;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

public class WeaviateDataSourceProvider extends AbstractDataSourceProvider<WeaviateDataSource> {

    public WeaviateDataSourceProvider() {
        super(WeaviateDataSource.class);
    }

    @Override
    public long getFeatures() {
        return 0;
    }

    @Override
    public String getConnectionURL(DBPDriver driver, DBPConnectionConfiguration connectionInfo) {
        String connType = connectionInfo.getProviderProperty(WeaviateConstants.PROP_CONNECTION_TYPE);
        if (WeaviateConstants.CONN_TYPE_CLOUD.equals(connType)) {
            String cloudUrl = connectionInfo.getProviderProperty(WeaviateConstants.PROP_CLOUD_URL);
            return (cloudUrl == null || cloudUrl.isEmpty()) ? "weaviate://cloud" : cloudUrl;
        }
        String scheme = connectionInfo.getProviderProperty(WeaviateConstants.PROP_SCHEME);
        if (scheme == null || scheme.isEmpty()) scheme = WeaviateConstants.DEFAULT_SCHEME;
        String host = connectionInfo.getHostName();
        if (host == null || host.isEmpty()) host = WeaviateConstants.DEFAULT_HTTP_HOST;
        String port = connectionInfo.getHostPort();
        if (port == null || port.isEmpty()) port = String.valueOf(WeaviateConstants.DEFAULT_HTTP_PORT);
        return scheme + "://" + host + ":" + port;
    }

    @NotNull
    @Override
    public WeaviateDataSource openDataSource(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container
    ) throws DBException {
        return new WeaviateDataSource(container);
    }
}
