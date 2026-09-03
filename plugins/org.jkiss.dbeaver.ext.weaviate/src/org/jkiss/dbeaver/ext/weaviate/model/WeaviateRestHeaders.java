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

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;

import java.net.http.HttpRequest;
import java.util.Map;

/**
 * The headers every request to Weaviate carries, whoever is sending it.
 * <p>
 * There are two senders. The bundled client sets them once on its transports, so every call it
 * makes carries them; the plugin's own REST helpers build their requests by hand and, until this
 * existed, carried only {@code Authorization}. That difference was invisible until a collection
 * used a module: a vectorizer, reranker or generative provider authenticates with a key the
 * <em>server</em> forwards, taken from a header on the request that reached it. Creating or
 * updating a collection that names such a module goes over REST here, so it went without the key
 * and the server refused it -- or, worse, accepted a definition it could not later use.
 * <p>
 * Applying them everywhere the plugin calls REST, rather than only where a module could matter, is
 * deliberate: it is what the client does, an unrecognised header costs nothing, and a rule with an
 * exception is a rule someone has to remember. The alternative was deciding per endpoint which
 * ones might one day touch a module, which is a question that changes with every release.
 *
 * @see WeaviateModelHeaders for where the values come from and how they are ordered
 */
final class WeaviateRestHeaders {

    private WeaviateRestHeaders() {
    }

    /**
     * Add {@code Authorization} and every configured model header to a request under construction.
     *
     * @throws DBException when the connection cannot produce an authorization header at all, which
     *                     is the OIDC case: the token is minted inside the client with no public
     *                     accessor, so a REST call cannot be signed
     */
    static void applyTo(
        @NotNull HttpRequest.Builder request, @NotNull WeaviateDataSource dataSource
    ) throws DBException {
        String authorization = dataSource.getRestAuthorizationHeader();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        for (Map.Entry<String, String> header : dataSource.getModelHeaders().entrySet()) {
            request.header(header.getKey(), header.getValue());
        }
    }
}
