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

public class WeaviateConstants {

    /**
     * Version of the bundled Weaviate Java client.
     * <p>
     * Must match the jar in {@code lib/} and the {@code Bundle-ClassPath} entry in
     * {@code META-INF/MANIFEST.MF}; those three are updated together when the client is upgraded.
     */
    public static final String CLIENT_VERSION = "6.3.1";

    public static final String DEFAULT_HTTP_HOST = "localhost";
    public static final int DEFAULT_HTTP_PORT = 8080;
    public static final String DEFAULT_GRPC_HOST = "localhost";
    public static final int DEFAULT_GRPC_PORT = 50051;
    public static final String DEFAULT_SCHEME = "http";

    public static final String PROP_CONNECTION_TYPE = "connectionType";
    public static final String PROP_GRPC_HOST = "grpcHost";
    public static final String PROP_GRPC_PORT = "grpcPort";
    public static final String PROP_SCHEME = "scheme";
    public static final String PROP_CLOUD_URL = "cloudUrl";
    public static final String PROP_AUTH_TYPE = "authType";
    /**
     * Whether reads return embeddings by default.
     * <p>
     * Needed as a connection setting, not just a query-panel toggle, because exporting a collection
     * from the navigator never opens the panel - there would otherwise be no way to ask for vectors
     * in exported data. The panel still overrides it per collection for the session.
     */
    public static final String PROP_INCLUDE_VECTORS = "includeVectors";

    /**
     * Whether reads return embeddings when the connection has not said otherwise.
     * <p>
     * On: the vectors are the point of a vector database, and a result set or export that silently
     * omits them is surprising. Vectors are large, so this costs bandwidth and file size - the
     * connection setting and the query panel both turn it off.
     */
    public static final boolean DEFAULT_INCLUDE_VECTORS = true;
    public static final String PROP_API_KEY = "apiKey";

    public static final String CONN_TYPE_CUSTOM = "custom";
    public static final String CONN_TYPE_CLOUD = "cloud";

    public static final String AUTH_NONE = "none";
    public static final String AUTH_API_KEY = "apiKey";
    public static final String AUTH_USER_PASSWORD = "userPassword";
}
