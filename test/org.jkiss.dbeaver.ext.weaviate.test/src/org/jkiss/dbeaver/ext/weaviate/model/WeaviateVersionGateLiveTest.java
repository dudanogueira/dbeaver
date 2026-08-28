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
import io.weaviate.client6.v1.api.collections.Tokenization;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Checks the version gate's predictions against a server that actually answers.
 * <p>
 * The unit tests assert that {@link WeaviateServerFeature}'s minimums match the numbers
 * weaviate-studio carries. That is a useful consistency check and a worthless correctness one:
 * both projects would agree just as happily if the number were wrong, or if Weaviate backported a
 * feature. Only a real server settles whether a minimum is right, and the interesting server is an
 * <em>old</em> one, because on a current server every gate says yes and a broken gate is
 * indistinguishable from a working one.
 * <p>
 * Point {@code WEAVIATE_OLD_FIXTURE_URL} at any Weaviate and this asserts that the gate's answer
 * and the server's behaviour agree. Below a feature's minimum both should refuse; at or above it
 * both should allow. Skipped when unset, so the reactor stays hermetic.
 * <pre>
 * docker run -d -p 8090:8080 -p 50061:50051 \
 *     -e AUTHENTICATION_ANONYMOUS_ACCESS_ENABLED=true \
 *     -e PERSISTENCE_DATA_PATH=/var/lib/weaviate \
 *     cr.weaviate.io/semitechnologies/weaviate:1.36.0
 *
 * WEAVIATE_OLD_FIXTURE_URL=http://localhost:8090 mvn verify ...
 * </pre>
 * 1.36.0 is a good choice because it sits one minor below {@link WeaviateServerFeature#TOKENIZE},
 * so the tokenize assertions below exercise the refusing side rather than the permissive one.
 */
public class WeaviateVersionGateLiveTest extends DBeaverUnitTest {

    private static final String URL_VAR = "WEAVIATE_OLD_FIXTURE_URL";

    /**
     * Skip unless a server is configured. Called first in every test rather than from a helper:
     * an abort raised deeper down would be caught by the helper's own error handling and reported
     * as a failure instead of a skip.
     */
    private static void requireServer() {
        String url = System.getenv(URL_VAR);
        Assumptions.assumeTrue(url != null && !url.isBlank(),
            "set " + URL_VAR + " to a Weaviate (ideally an old one) to run the version gate tests");
    }

    /**
     * Unlike the other live tests, the ports are read from the URL rather than hardcoded: the
     * whole point is to reach a second, older server, which will not be on 8080.
     */
    private static WeaviateClient connect() {
        String url = System.getenv(URL_VAR).replaceFirst("^https?://", "");
        String host = url.replaceFirst(":.*$", "");
        int httpPort = url.contains(":")
            ? Integer.parseInt(url.replaceFirst("^[^:]*:", "").replaceFirst("/.*$", ""))
            : 8080;
        // gRPC is conventionally offset the same way the HTTP port is from 8080, which is how the
        // docker recipe above maps it. Nothing here needs gRPC, but the client connects eagerly.
        int grpcPort = 50051 + (httpPort - 8080);
        // Auth is opt-in here, unlike the fixture tests which always key into the lab server.
        // This one is pointed at whatever old server is to hand, and an anonymous Weaviate
        // answers 401 to a request that carries an Authorization header at all -- "no
        // authentication scheme is configured, but an 'Authorization' header was provided".
        String apiKey = System.getenv("WEAVIATE_API_KEY");
        boolean authenticated = apiKey != null && !apiKey.isBlank();
        return WeaviateClient.connectToCustom(c -> {
            c.scheme("http");
            c.httpHost(host);
            c.httpPort(httpPort);
            c.grpcHost(host);
            c.grpcPort(grpcPort);
            if (authenticated) {
                c.authentication(Authentication.apiKey(apiKey));
            }
            return c;
        });
    }

    private static String serverVersion() {
        try (WeaviateClient client = connect()) {
            return client.meta().version();
        } catch (Exception e) {
            throw new IllegalStateException("cannot read version from " + URL_VAR + ": " + e.getMessage(), e);
        }
    }

    /** Whether the server will actually tokenize, as opposed to whether the gate thinks it will. */
    private static boolean serverTokenizes() {
        try (WeaviateClient client = connect()) {
            client.tokenize.text("Red-Maple Leaf", b -> b.tokenization(Tokenization.WORD));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The assertion this class exists for: the gate's prediction and the server's behaviour must
     * be the same. Stated as an equality rather than two separate cases so it holds whichever
     * server it is pointed at -- an old one exercises the refusal, a current one the acceptance.
     */
    @Test
    public void theGateAgreesWithTheServerAboutTokenize() {
        requireServer();
        String version = serverVersion();
        boolean gateSaysYes = WeaviateServerFeature.TOKENIZE.isSupportedBy(version);
        boolean serverSaysYes = serverTokenizes();

        Assertions.assertEquals(gateSaysYes, serverSaysYes,
            () -> "server " + version + " " + (serverSaysYes ? "accepts" : "refuses")
                + " /v1/tokenize, but the gate says " + (gateSaysYes ? "available" : "unavailable")
                + ". TOKENIZE's minimum of " + WeaviateServerFeature.TOKENIZE.getMinVersion()
                + " is wrong, or the endpoint moved.");
    }

    /**
     * Health is deliberately ungated, and it has to keep working on whatever old server this is
     * pointed at -- the liveness endpoint predates every version the plugin would meet.
     */
    @Test
    public void healthNeedsNoGate() {
        requireServer();
        try (WeaviateClient client = connect()) {
            Assertions.assertTrue(client.isLive(),
                "liveness failed on " + serverVersion() + "; it predates 1.20 and should always answer");
            Assertions.assertTrue(client.isReady(), "readiness failed");
        } catch (Exception e) {
            Assertions.fail("health probe threw against " + URL_VAR + ": " + e.getMessage(), e);
        }
    }

    /**
     * The parser must recognise whatever the server actually calls itself. A version it cannot
     * read is treated as permissive, which is the right default but would silently mask a real
     * server reporting something unexpected.
     */
    @Test
    public void theServersOwnVersionStringParses() {
        requireServer();
        String version = serverVersion();
        Assertions.assertNotNull(WeaviateVersions.parse(version),
            () -> "the server reports \"" + version + "\", which WeaviateVersions cannot parse. "
                + "Every gate would silently pass on this server.");
    }
}
