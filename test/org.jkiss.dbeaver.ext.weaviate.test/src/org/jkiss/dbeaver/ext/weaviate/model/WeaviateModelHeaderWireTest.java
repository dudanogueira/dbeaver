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

import com.sun.net.httpserver.HttpServer;
import io.weaviate.client6.v1.api.WeaviateClient;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * That the model-provider headers actually reach the wire.
 * <p>
 * The plugin hands them to the client once, at construction, and trusts it to attach them to every
 * request. That trust is worth a test rather than a reading: a key that never leaves DBeaver looks
 * exactly like a key the server rejected -- the collection simply refuses to vectorize -- and the
 * error comes back from the model provider, or from Weaviate complaining there is no key, in
 * neither case pointing at the client that dropped it.
 * <p>
 * Runs against a throwaway HTTP server in this process rather than a live Weaviate, because the
 * question is what the client sends, not what a server does with it. What a server does with it is
 * separately known: Weaviate looks for the header on data and query requests, not on schema
 * writes, and forwards it to the provider.
 */
public class WeaviateModelHeaderWireTest extends DBeaverUnitTest {

    @Test
    public void theClientPutsConfiguredHeadersOnEveryRequest() throws Exception {
        Map<String, String> seen = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestHeaders().forEach((name, values) -> {
                if (!values.isEmpty()) {
                    seen.put(name.toLowerCase(java.util.Locale.ROOT), values.get(0));
                }
            });
            byte[] body = "true".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();

        try (WeaviateClient client = WeaviateClient.connectToCustom(c -> {
            c.scheme("http");
            c.httpHost("127.0.0.1");
            c.httpPort(port);
            // Never dialled: this test only makes an HTTP call, and the gRPC channel is lazy.
            c.grpcHost("127.0.0.1");
            c.grpcPort(port);
            c.setHeaders(Map.of(
                "X-OpenAI-Api-Key", "sk-from-dbeaver",
                "X-Cohere-Api-Key", "co-from-dbeaver"));
            return c;
        })) {
            try {
                client.isLive();
            } catch (Exception e) {
                // The stub answers "true" to everything, so a parse failure further in is fine --
                // the request has already been made and recorded by then.
            }
        } catch (Exception e) {
            // The stub is not a Weaviate: whatever it answers, the client may fail to parse it.
            // By then the request has been made and recorded, which is the whole question.
        } finally {
            server.stop(0);
        }

        Assertions.assertFalse(seen.isEmpty(), "the client made no request at all");
        Assertions.assertEquals("sk-from-dbeaver", seen.get("x-openai-api-key"),
            "the OpenAI key never left the client. Seen: " + seen.keySet());
        Assertions.assertEquals("co-from-dbeaver", seen.get("x-cohere-api-key"),
            "the Cohere key never left the client. Seen: " + seen.keySet());
    }

    /**
     * The header names this plugin sends are the ones the server looks for.
     * <p>
     * Checked against the server's own error text, which names the header it wanted:
     * <em>no api key found neither in request header: X-Openai-Api-Key nor in environment variable
     * under OPENAI_APIKEY</em>. HTTP header names are case-insensitive, so the spelling difference
     * is not one -- but the stem has to match, and a provider added with the wrong stem would fail
     * exactly like a missing key.
     */
    @Test
    public void providerHeadersMatchWhatTheServerAsksFor() {
        Map<WeaviateModelProvider, String> expected = Map.of(
            WeaviateModelProvider.OPENAI, "X-OpenAI-Api-Key",
            WeaviateModelProvider.COHERE, "X-Cohere-Api-Key");
        for (Map.Entry<WeaviateModelProvider, String> entry : expected.entrySet()) {
            Assertions.assertEquals(
                entry.getValue().toLowerCase(java.util.Locale.ROOT),
                entry.getKey().getHeader().toLowerCase(java.util.Locale.ROOT),
                entry.getKey() + " sends a header the server does not read");
        }
        // Every provider must at least be shaped like one of these, or it cannot work.
        for (WeaviateModelProvider provider : WeaviateModelProvider.values()) {
            String header = provider.getHeader();
            Assertions.assertTrue(
                header.toLowerCase(java.util.Locale.ROOT).startsWith("x-"),
                provider + " has header " + header);
            Assertions.assertFalse(List.of("").contains(header), provider + " has no header");
        }
    }
}
