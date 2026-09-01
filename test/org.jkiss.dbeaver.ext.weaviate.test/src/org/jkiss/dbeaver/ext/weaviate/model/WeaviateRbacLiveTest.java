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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The seam between what Weaviate sends and what this plugin reads.
 * <p>
 * The unit tests pin the vocabularies against a list copied out of the server source, which
 * catches a rename in this plugin but not one on the server. These tests ask a running server and
 * fail when the two drift -- which is the failure mode that produced this whole feature: the
 * bundled client's permission vocabulary fell behind the server's, and nothing noticed until
 * {@code roles.list()} threw.
 * <p>
 * Deliberately read-only. Roles and users are cluster-wide state, and a test that created them
 * would be a test that leaves them behind when it fails. What it needs seeded is in
 * {@code testdata/seed_rbac_fixture.py}.
 * <p>
 * Requires a server started with {@code AUTHORIZATION_RBAC_ENABLED=true}.
 */
public class WeaviateRbacLiveTest extends DBeaverUnitTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static String baseUrl() {
        String url = System.getenv("WEAVIATE_RBAC_FIXTURE_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
            "set WEAVIATE_RBAC_FIXTURE_URL to run the live RBAC tests");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Called first in every test rather than from a helper, so an abort is not swallowed. */
    private static String get(String path) {
        String base = baseUrl();
        String key = System.getenv().getOrDefault("WEAVIATE_API_KEY", "root-user-key");
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(TIMEOUT)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + key)
            .GET()
            .build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Assertions.assertEquals(200, response.statusCode(),
                () -> "GET " + path + " -> " + response.statusCode() + " " + response.body());
            return response.body();
        } catch (Exception e) {
            throw new AssertionError("GET " + path + " failed: " + e.getMessage(), e);
        }
    }

    private static List<WeaviateRbacRest.RoleInfo> roles() {
        List<WeaviateRbacRest.RoleInfo> roles = new ArrayList<>();
        for (JsonElement e : JsonParser.parseString(get("/v1/authz/roles")).getAsJsonArray()) {
            roles.add(WeaviateRbacRest.parseRole(e.getAsJsonObject()));
        }
        Assumptions.assumeFalse(roles.isEmpty(),
            "no roles on this server -- is AUTHORIZATION_RBAC_ENABLED set?");
        return roles;
    }

    @Test
    public void theBuiltInRolesAreThere() {
        List<String> names = roles().stream().map(WeaviateRbacRest.RoleInfo::name).toList();
        // viewer and admin are assignable through the API; root and read-only come from env vars,
        // and root is only visible at all to a root user, so it is not required here.
        for (String expected : new String[]{"admin", "viewer"}) {
            Assertions.assertTrue(names.contains(expected),
                () -> "expected a built-in role " + expected + ", got " + names);
        }
    }

    @Test
    public void everyActionTheServerUsesIsOneThisPluginKnows() {
        // The seam that matters. A new action in a Weaviate release shows up here as a failure
        // naming the action, rather than as an unlabelled row in someone's tree.
        List<String> unknown = new ArrayList<>();
        for (WeaviateRbacRest.RoleInfo role : roles()) {
            for (WeaviateRbacRest.PermissionInfo permission : role.permissions()) {
                if (!WeaviateRbacAction.isKnown(permission.action())) {
                    unknown.add(role.name() + ": " + permission.action());
                }
            }
        }
        Assertions.assertTrue(unknown.isEmpty(),
            () -> "actions this plugin cannot place: " + unknown);
    }

    @Test
    public void everyScopeKindTheServerUsesIsOneThisPluginKnows() {
        List<String> unknown = new ArrayList<>();
        for (WeaviateRbacRest.RoleInfo role : roles()) {
            for (WeaviateRbacRest.PermissionInfo permission : role.permissions()) {
                if (permission.kind() != null && !WeaviateRbacKind.isKnown(permission.kind())) {
                    unknown.add(role.name() + ": " + permission.kind());
                }
            }
        }
        Assertions.assertTrue(unknown.isEmpty(),
            () -> "permission kinds this plugin cannot lay out: " + unknown);
    }

    @Test
    public void everyScopeFieldTheServerSendsIsOneThisPluginExpects() {
        // Catches a field added to an existing kind, which would otherwise vanish from the editor
        // and be dropped the next time that permission was saved.
        List<String> unexpected = new ArrayList<>();
        for (WeaviateRbacRest.RoleInfo role : roles()) {
            for (WeaviateRbacRest.PermissionInfo permission : role.permissions()) {
                if (permission.kind() == null) {
                    continue;
                }
                List<String> known = WeaviateRbacKind.fieldsOf(permission.kind());
                for (String field : permission.scope().keySet()) {
                    if (!known.contains(field)) {
                        unexpected.add(permission.kind() + "." + field);
                    }
                }
            }
        }
        Assertions.assertTrue(unexpected.isEmpty(),
            () -> "scope fields this plugin does not model: " + unexpected);
    }

    @Test
    public void theAdminRoleCarriesTheKindThatBreaksTheBundledClient() {
        // The specific reason none of this goes through client.roles: admin holds
        // manage_namespaces on any 1.38+ server, and Permission$Kind throws on it. If a future
        // server stops sending it this test should be revisited, not deleted -- the reasoning in
        // WeaviateRbacRest depends on it.
        WeaviateRbacRest.RoleInfo admin = roles().stream()
            .filter(r -> "admin".equals(r.name())).findFirst().orElse(null);
        Assumptions.assumeTrue(admin != null, "no admin role visible to this user");
        boolean hasNamespaces = admin.permissions().stream()
            .anyMatch(p -> "namespaces".equals(p.kind()));
        Assertions.assertTrue(hasNamespaces,
            "admin no longer carries a namespaces permission; re-check whether the bundled "
                + "client can be used after all");
    }

    @Test
    public void everyPermissionKeyIsDistinctWithinARole() {
        // The diff-based save relies on the key identifying a permission. Two permissions of one
        // role keying the same would make an edit remove something it meant to keep.
        for (WeaviateRbacRest.RoleInfo role : roles()) {
            List<String> keys = role.permissions().stream()
                .map(WeaviateRbacRest.PermissionInfo::key).toList();
            Assertions.assertEquals(keys.size(), keys.stream().distinct().count(),
                () -> "duplicate permission keys in role " + role.name());
        }
    }

    @Test
    public void databaseUsersReportTheirTypeAndState() {
        JsonArray users =
            JsonParser.parseString(get("/v1/users/db?includeLastUsedTime=true")).getAsJsonArray();
        Assumptions.assumeFalse(users.isEmpty(),
            "no database users -- run testdata/seed_rbac_fixture.py");
        boolean sawEnvUser = false;
        for (JsonElement e : users) {
            WeaviateRbacRest.DbUserInfo user = WeaviateRbacRest.parseUser(e.getAsJsonObject());
            Assertions.assertFalse(user.userId().isBlank(), "a user with no id");
            Assertions.assertNotNull(user.dbUserType(), () -> user.userId() + " has no type");
            sawEnvUser |= user.isEnvUser();
        }
        // The root user comes from AUTHENTICATION_APIKEY_USERS, so it arrives as db_env_user --
        // the distinction the bundled client's UserType cannot represent.
        Assertions.assertTrue(sawEnvUser,
            "expected at least one db_env_user; the API-key root user should be one");
    }

    @Test
    public void ownInfoNamesTheCallerAndItsRoles() {
        JsonObject own = JsonParser.parseString(get("/v1/users/own-info")).getAsJsonObject();
        Assertions.assertTrue(own.has("username"), "own-info carries no username");
        // roles is null exactly when RBAC is off, which is the signal the Security folder uses.
        Assertions.assertTrue(own.has("roles") && own.get("roles").isJsonArray(),
            "own-info reports no roles, so RBAC looks disabled on this server");
    }

    @Test
    public void aRoleReportsWhoHoldsIt() {
        // What the delete confirmation lists, so that "anyone holding this loses what it granted"
        // stops being a warning about an unknown. Reads /user-assignments rather than the older
        // /authz/roles/{id}/users, which answers 410 on a namespace-enabled cluster.
        List<String> names = roles().stream().map(WeaviateRbacRest.RoleInfo::name).toList();
        Assumptions.assumeTrue(names.contains("dbeaver-data-reader"),
            "run testdata/seed_rbac_fixture.py to seed the RBAC fixture");
        JsonArray assignments = JsonParser.parseString(
            get("/v1/authz/roles/dbeaver-data-reader/user-assignments")).getAsJsonArray();
        Assertions.assertFalse(assignments.isEmpty(),
            "the fixture assigns dbeaver-data-reader to a user, and the server reports nobody");
        List<String> holders = new ArrayList<>();
        for (JsonElement e : assignments) {
            JsonObject o = e.getAsJsonObject();
            Assertions.assertTrue(o.has("userId"),
                () -> "an assignment with no userId: " + o);
            holders.add(o.get("userId").getAsString());
        }
        Assertions.assertTrue(holders.contains("dbeaver-active-user"),
            () -> "expected dbeaver-active-user among the holders, got " + holders);
    }

    @Test
    public void theSeededFixtureIsPresent() {
        List<String> names = roles().stream().map(WeaviateRbacRest.RoleInfo::name).toList();
        Assumptions.assumeTrue(names.contains("dbeaver-multi-rule"),
            "run testdata/seed_rbac_fixture.py to seed the RBAC fixture");
        WeaviateRbacRest.RoleInfo role = roles().stream()
            .filter(r -> "dbeaver-multi-rule".equals(r.name())).findFirst().orElseThrow();
        // Two data rules at different scopes: the case a one-rule-per-section editor gets wrong.
        long dataRules = role.permissions().stream()
            .filter(p -> "data".equals(p.kind())).count();
        Assertions.assertEquals(2, dataRules,
            () -> "expected two data permissions at different scopes, got " + role.permissions());
    }
}
