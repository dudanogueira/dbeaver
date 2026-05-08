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

import org.jkiss.dbeaver.ext.weaviate.WeaviateConstants;
import org.jkiss.dbeaver.ext.weaviate.WeaviateDataSourceProvider;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.Assert;
import org.junit.Test;

public class WeaviateConnectionUrlTest extends DBeaverUnitTest {

    private final WeaviateDataSourceProvider provider = new WeaviateDataSourceProvider();

    @Test
    public void defaultsProduceLocalhostUrl() {
        DBPConnectionConfiguration cfg = new DBPConnectionConfiguration();
        String url = provider.getConnectionURL(null, cfg);
        Assert.assertEquals("http://localhost:8080", url);
    }

    @Test
    public void customHostAndPortAreUsed() {
        DBPConnectionConfiguration cfg = new DBPConnectionConfiguration();
        cfg.setHostName("weaviate.example.com");
        cfg.setHostPort("9090");
        String url = provider.getConnectionURL(null, cfg);
        Assert.assertEquals("http://weaviate.example.com:9090", url);
    }

    @Test
    public void httpsSchemeIsRespected() {
        DBPConnectionConfiguration cfg = new DBPConnectionConfiguration();
        cfg.setHostName("cloud.weaviate.io");
        cfg.setHostPort("443");
        cfg.setProviderProperty(WeaviateConstants.PROP_SCHEME, "https");
        String url = provider.getConnectionURL(null, cfg);
        Assert.assertEquals("https://cloud.weaviate.io:443", url);
    }

    @Test
    public void emptySchemeUsesDefault() {
        DBPConnectionConfiguration cfg = new DBPConnectionConfiguration();
        cfg.setHostName("localhost");
        cfg.setHostPort("8080");
        cfg.setProviderProperty(WeaviateConstants.PROP_SCHEME, "");
        String url = provider.getConnectionURL(null, cfg);
        Assert.assertEquals("http://localhost:8080", url);
    }

    @Test
    public void defaultGrpcPort() {
        Assert.assertEquals(50051, WeaviateConstants.DEFAULT_GRPC_PORT);
    }

    @Test
    public void defaultHttpPort() {
        Assert.assertEquals(8080, WeaviateConstants.DEFAULT_HTTP_PORT);
    }
}
