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
import io.weaviate.client6.v1.api.WeaviateApiException;
import io.weaviate.client6.v1.api.WeaviateClient;
import io.weaviate.client6.v1.api.alias.Alias;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

/**
 * The alias client against a real Weaviate.
 * <p>
 * These are live tests because every one of them checks something a signature cannot show. The
 * client's {@code create} takes its arguments in the opposite order to the rest of the namespace
 * and the jar carries no parameter names to say so; its list filter is called
 * {@code collection(...)} and goes on the wire as {@code class}; and the server normalises an
 * alias name on the way in, so what comes back is not what was sent. Nothing catches any of that
 * except a real call.
 * <p>
 * Skipped unless {@code WEAVIATE_ALIAS_FIXTURE_URL} is set, so the reactor stays hermetic. Seed
 * with {@code testdata/seed_alias_fixture.py}, then:
 * <pre>
 * WEAVIATE_ALIAS_FIXTURE_URL=http://localhost:8080 mvn clean verify -T 1C
 * </pre>
 */
public class WeaviateAliasLiveTest extends DBeaverUnitTest {

    /** Collections from the fixture. Two of them, so a repoint has somewhere to go. */
    private static final String COLLECTION = "ProductsV3";
    private static final String OTHER_COLLECTION = "ProductsV2";

    /**
     * Aliases this test creates and removes itself, kept away from the fixture's own names so a
     * failed run cannot leave the fixture looking wrong.
     */
    private static final String TEMP = "DBeaverLiveAliasTemp";
    private static final String MISSING = "DBeaverLiveAliasNoSuchThing";

    /**
     * Skip unless a fixture server is configured. Called first in every test rather than from the
     * client helper: an abort raised deeper down would be caught by the helper's error handling
     * and reported as a failure instead of a skip.
     */
    private static void requireFixture() {
        String url = System.getenv("WEAVIATE_ALIAS_FIXTURE_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
            "set WEAVIATE_ALIAS_FIXTURE_URL to run the live alias tests");
    }

    private static WeaviateClient connect() {
        String url = System.getenv("WEAVIATE_ALIAS_FIXTURE_URL");
        String host = url.replaceFirst("^https?://", "").replaceFirst(":.*$", "");
        String apiKey = System.getenv().getOrDefault("WEAVIATE_API_KEY", "root-user-key");
        return WeaviateClient.connectToCustom(c -> {
            c.scheme("http");
            c.httpHost(host);
            c.httpPort(8080);
            c.grpcHost(host);
            c.grpcPort(50051);
            c.authentication(Authentication.apiKey(apiKey));
            return c;
        });
    }

    /** Best-effort removal, so one failed assertion does not poison every later test. */
    private static void drop(WeaviateClient client, String alias) {
        try {
            client.alias.delete(alias);
        } catch (Exception e) {
            // Nothing to do: the next create will report it if it really is still there.
        }
    }

    /**
     * The argument order, which is the trap.
     * <p>
     * {@code create} is {@code (collection, alias)} while {@code get}, {@code update} and
     * {@code delete} are alias-first, and the jar has no {@code MethodParameters} attribute, so
     * both read as {@code arg0} in an IDE. Transposed, this would create an alias named
     * {@code ProductsV3} pointing at {@code DBeaverLiveAliasTemp} -- which the server would refuse,
     * since a collection already holds that name. Asserting the resulting target is what makes the
     * order visible rather than merely survivable.
     */
    @Test
    public void createTakesTheCollectionFirst() {
        requireFixture();
        try (WeaviateClient client = connect()) {
            drop(client, TEMP);
            client.alias.create(COLLECTION, TEMP);
            Optional<Alias> created = client.alias.get(TEMP);
            Assertions.assertTrue(created.isPresent(), TEMP + " was not created");
            Assertions.assertEquals(COLLECTION, created.get().collection(),
                "create() is (collection, alias); a swapped call would target the alias name");
            drop(client, TEMP);
        } catch (Exception e) {
            throw new IllegalStateException("live alias create failed: " + deepest(e), e);
        }
    }

    /**
     * The server capitalises an alias name, exactly as it does a collection name.
     * <p>
     * So the name in the tree is not the name that was typed, and anything comparing the two has
     * to ignore case -- which is why {@code WeaviateAlias.pointsAt} does. Lookups accept either
     * spelling; only the stored form is normalised.
     */
    @Test
    public void theServerCapitalisesAnAliasName() {
        requireFixture();
        String typed = "dbeaverLiveAliasCase";
        try (WeaviateClient client = connect()) {
            drop(client, typed);
            client.alias.create(COLLECTION, typed);
            Optional<Alias> stored = client.alias.get(typed);
            Assertions.assertTrue(stored.isPresent(), "lookup by the typed spelling should work");
            Assertions.assertEquals("DbeaverLiveAliasCase", stored.get().alias(),
                "Weaviate normalises an alias name the way it normalises a collection name");
            drop(client, typed);
        } catch (Exception e) {
            throw new IllegalStateException("live alias case check failed: " + deepest(e), e);
        }
    }

    /**
     * The filter really filters.
     * <p>
     * Its builder method is {@code collection(...)} and the query parameter it sends is
     * {@code class}. A parameter the server does not recognise is ignored rather than refused, so
     * a rename on either side would leave this returning everything -- silently, and looking
     * entirely plausible under a collection's own folder. This plugin filters locally for that
     * reason; the test is here so the day it changes is a failure naming it rather than a
     * discovery.
     */
    @Test
    public void theCollectionFilterFilters() {
        requireFixture();
        try (WeaviateClient client = connect()) {
            drop(client, TEMP);
            client.alias.create(OTHER_COLLECTION, TEMP);
            List<Alias> all = client.alias.list();
            List<Alias> filtered = client.alias.list(b -> b.collection(OTHER_COLLECTION));
            Assertions.assertNotNull(all, "list() should never be null");
            Assertions.assertNotNull(filtered, "a filtered list() should never be null");
            Assertions.assertTrue(filtered.size() < all.size(),
                "the filter returned everything, so it is not filtering");
            for (Alias alias : filtered) {
                Assertions.assertEquals(OTHER_COLLECTION, alias.collection());
            }
            drop(client, TEMP);
        } catch (Exception e) {
            throw new IllegalStateException("live alias filter failed: " + deepest(e), e);
        }
    }

    /**
     * Absence is a value for delete and an exception for update.
     * <p>
     * {@code BooleanEndpoint} treats 404 as a result and {@code SimpleEndpoint.sideEffect} does
     * not, so the same missing alias produces false from one call and a throw from the other. Both
     * paths in this plugin are written to that split, which makes it worth pinning.
     */
    @Test
    public void aMissingAliasDeletesFalseAndUpdatesThrow() {
        requireFixture();
        try (WeaviateClient client = connect()) {
            Assertions.assertFalse(client.alias.delete(MISSING),
                "deleting an alias that is not there should report false, not throw");
            Assertions.assertTrue(client.alias.get(MISSING).isEmpty(),
                "get() turns a 404 into an empty Optional");
            Assertions.assertThrows(WeaviateApiException.class,
                () -> client.alias.update(MISSING, COLLECTION),
                "update() does not special-case 404, unlike delete()");
        } catch (Exception e) {
            throw new IllegalStateException("live alias absence check failed: " + deepest(e), e);
        }
    }

    /** A duplicate name is refused with an explanation, which is the message the UI shows. */
    @Test
    public void aDuplicateNameIsRefusedWithAReadableReason() {
        requireFixture();
        try (WeaviateClient client = connect()) {
            drop(client, TEMP);
            client.alias.create(COLLECTION, TEMP);
            WeaviateApiException refusal = Assertions.assertThrows(WeaviateApiException.class,
                () -> client.alias.create(OTHER_COLLECTION, TEMP));
            String error = refusal.getError();
            Assertions.assertNotNull(error, "the server's explanation is what gets shown");
            Assertions.assertTrue(error.toLowerCase().contains("already exists"),
                "unexpected refusal: " + error);
            drop(client, TEMP);
        } catch (Exception e) {
            throw new IllegalStateException("live alias duplicate check failed: " + deepest(e), e);
        }
    }

    /** Create, repoint, drop -- the whole cycle the navigator drives. */
    @Test
    public void theFullCycleWorks() {
        requireFixture();
        try (WeaviateClient client = connect()) {
            drop(client, TEMP);
            client.alias.create(COLLECTION, TEMP);
            Assertions.assertEquals(COLLECTION, client.alias.get(TEMP).orElseThrow().collection());

            client.alias.update(TEMP, OTHER_COLLECTION);
            Assertions.assertEquals(OTHER_COLLECTION,
                client.alias.get(TEMP).orElseThrow().collection(),
                "a repoint changes the target and nothing else");

            Assertions.assertTrue(client.alias.delete(TEMP));
            Assertions.assertTrue(client.alias.get(TEMP).isEmpty(), TEMP + " survived its delete");
        } catch (Exception e) {
            throw new IllegalStateException("live alias cycle failed: " + deepest(e), e);
        }
    }

    private static String deepest(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
