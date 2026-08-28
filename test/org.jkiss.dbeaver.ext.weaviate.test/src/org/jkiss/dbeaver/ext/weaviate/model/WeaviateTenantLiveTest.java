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
import io.weaviate.client6.v1.api.collections.tenants.Tenant;
import io.weaviate.client6.v1.api.collections.tenants.WeaviateTenantsClient;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks the tenant model against a real server.
 * <p>
 * The unit tests cover the filter, which is ours end to end. What they cannot cover is the seam:
 * {@link WeaviateTenantStatus} is resolved from the name of the client's own enum, so a rename
 * there, or a server answering in the older HOT/COLD spelling, would turn every status in the
 * dialog into "Unknown" while every test still passed.
 * <p>
 * Skipped unless {@code WEAVIATE_TENANT_FIXTURE_URL} is set, so the reactor stays hermetic. Seed
 * the data first with {@code testdata/seed_tenant_fixture.py}, then:
 * <pre>
 * WEAVIATE_TENANT_FIXTURE_URL=http://localhost:8080 mvn verify ...
 * </pre>
 */
public class WeaviateTenantLiveTest extends DBeaverUnitTest {

    private static final String COLLECTION = "DBeaverTenantFixture";

    /**
     * Skip unless a fixture server is configured. Called first in every test rather than from a
     * helper, so an abort is not swallowed by the helper's own error handling.
     */
    private static void requireFixture() {
        String url = System.getenv("WEAVIATE_TENANT_FIXTURE_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
            "set WEAVIATE_TENANT_FIXTURE_URL to run the live tenant tests");
    }

    private static WeaviateClient connect() {
        String url = System.getenv("WEAVIATE_TENANT_FIXTURE_URL");
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

    private static WeaviateTenantsClient tenants(WeaviateClient client) {
        return client.collections.use(COLLECTION).tenants;
    }

    /** The same mapping {@link WeaviateCollection#listTenants} performs. */
    private static List<WeaviateTenant> asModel(List<Tenant> raw) {
        List<WeaviateTenant> mapped = new ArrayList<>();
        for (Tenant tenant : raw) {
            mapped.add(new WeaviateTenant(tenant.name(), WeaviateTenantStatus.fromName(
                tenant.status() == null ? null : tenant.status().name())));
        }
        return mapped;
    }

    private static WeaviateTenantStatus statusOf(WeaviateTenantsClient client, String name) {
        return client.get(name)
            .map(t -> WeaviateTenantStatus.fromName(t.status() == null ? null : t.status().name()))
            .orElse(null);
    }

    /**
     * The seam. Every state the server reports has to be one this plugin can name, or the dialog
     * shows a column of "Unknown" and the buttons cannot decide what to offer.
     */
    @Test
    public void everyStatusTheServerReportsIsRecognised() throws Exception {
        requireFixture();
        try (WeaviateClient client = connect()) {
            List<WeaviateTenant> mapped = asModel(tenants(client).list());
            Assertions.assertFalse(mapped.isEmpty(),
                "no tenants in " + COLLECTION + " -- run testdata/seed_tenant_fixture.py");
            for (WeaviateTenant tenant : mapped) {
                Assertions.assertNotEquals(WeaviateTenantStatus.UNKNOWN, tenant.status(),
                    () -> "server reported a state this plugin cannot name for " + tenant.name()
                        + ". The client's TenantStatus constants and WeaviateTenantStatus.fromName"
                        + " have drifted apart.");
            }
        }
    }

    /**
     * The fixture deliberately mixes states. If everything came back ACTIVE the seam above would
     * pass while still being unable to tell the two apart.
     */
    @Test
    public void theFixtureHasBothStatesAndTheyAreDistinguished() throws Exception {
        requireFixture();
        try (WeaviateClient client = connect()) {
            List<WeaviateTenant> mapped = asModel(tenants(client).list());
            long active = mapped.stream().filter(WeaviateTenant::isActive).count();
            long inactive = mapped.size() - active;
            Assertions.assertTrue(active > 0 && inactive > 0,
                () -> "expected a mix, got " + active + " active and " + inactive + " inactive."
                    + " Re-run testdata/seed_tenant_fixture.py");
        }
    }

    /**
     * The round trip, on one tenant, restored afterwards. Confirms that what the plugin calls
     * deactivating is what the server calls inactive, in both directions.
     */
    @Test
    public void deactivateAndActivateAreObservedByTheServer() throws Exception {
        requireFixture();
        try (WeaviateClient client = connect()) {
            WeaviateTenantsClient tenantsClient = tenants(client);
            String subject = asModel(tenantsClient.list()).stream()
                .filter(WeaviateTenant::isActive)
                .map(WeaviateTenant::name)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no active tenant to work with"));

            try {
                tenantsClient.deactivate(List.of(subject));
                Assertions.assertEquals(WeaviateTenantStatus.INACTIVE,
                    statusOf(tenantsClient, subject), subject + " should be inactive");

                tenantsClient.activate(List.of(subject));
                Assertions.assertEquals(WeaviateTenantStatus.ACTIVE,
                    statusOf(tenantsClient, subject), subject + " should be active again");
            } finally {
                // Leave the fixture as it was found, so the test can be run twice.
                tenantsClient.activate(List.of(subject));
            }
        }
    }

    /**
     * The filter against real names rather than invented ones, since it is what decides how many
     * tenants a bulk action touches.
     */
    @Test
    public void theWildcardSelectsAGroupOfRealTenants() throws Exception {
        requireFixture();
        try (WeaviateClient client = connect()) {
            List<WeaviateTenant> all = asModel(tenants(client).list());

            List<WeaviateTenant> acme = WeaviateTenantFilter.of("acme-*").filter(all);
            Assertions.assertFalse(acme.isEmpty(), "the fixture should have acme- tenants");
            for (WeaviateTenant tenant : acme) {
                Assertions.assertTrue(tenant.name().startsWith("acme-"),
                    () -> tenant.name() + " matched acme-* but does not start with it");
            }
            Assertions.assertTrue(acme.size() < all.size(),
                "acme-* should not select every tenant in the fixture");
        }
    }
}
