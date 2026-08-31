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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateRbacRest.PermissionInfo;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Parsing a permission, using payloads taken verbatim off a running 1.39.0.
 * <p>
 * Every string below was captured from {@code GET /v1/authz/roles/admin} rather than written by
 * hand, because the shapes are the point: one sub-object whose key names the kind, except for the
 * two families that carry no sub-object at all.
 */
public class WeaviateRbacPermissionTest extends DBeaverUnitTest {

    /** One permission of each kind the built-in admin role carries on 1.39.0. */
    private static final List<String> REAL_PAYLOADS = List.of(
        "{\"action\": \"create_roles\", \"roles\": {\"role\": \"*\", \"scope\": \"all\"}}",
        "{\"action\": \"manage_backups\", \"backups\": {\"collection\": \"*\"}}",
        "{\"action\": \"manage_namespaces\", \"namespaces\": {\"namespace\": \"*\"}}",
        "{\"action\": \"assign_and_revoke_users\", \"users\": {\"users\": \"*\"}}",
        "{\"action\": \"read_cluster\"}",
        "{\"action\": \"assign_and_revoke_groups\", \"groups\": {\"group\": \"*\", \"groupType\": \"oidc\"}}",
        "{\"action\": \"read_nodes\", \"nodes\": {\"collection\": \"*\", \"verbosity\": \"verbose\"}}",
        "{\"action\": \"create_collections\", \"collections\": {\"collection\": \"*\"}}",
        "{\"action\": \"create_data\", \"data\": {\"collection\": \"*\", \"object\": \"*\", \"tenant\": \"*\"}}",
        "{\"action\": \"create_tenants\", \"tenants\": {\"collection\": \"*\", \"tenant\": \"*\"}}",
        "{\"action\": \"create_replicate\", \"replicate\": {\"collection\": \"*\", \"shard\": \"*\"}}",
        "{\"action\": \"create_aliases\", \"aliases\": {\"alias\": \"*\", \"collection\": \"*\"}}");

    private static PermissionInfo parse(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        return WeaviateRbacRest.parsePermission(o);
    }

    @Test
    public void everyShapeTheAdminRoleCarriesParses() {
        for (String payload : REAL_PAYLOADS) {
            PermissionInfo permission = parse(payload);
            Assertions.assertNotNull(permission, () -> "did not parse: " + payload);
            Assertions.assertFalse(permission.action().isBlank(), () -> "no action: " + payload);
        }
    }

    @Test
    public void theNamespacesPermissionSurvives() {
        // The one that makes client.roles.list() throw with
        //   IllegalArgumentException: Permission$Kind does not have a member with
        //   jsonValue=namespaces
        // Reading it as text costs nothing and cannot fail that way.
        PermissionInfo permission =
            parse("{\"action\": \"manage_namespaces\", \"namespaces\": {\"namespace\": \"*\"}}");
        Assertions.assertEquals("manage_namespaces", permission.action());
        Assertions.assertEquals("namespaces", permission.kind());
        Assertions.assertEquals("*", permission.scope().get("namespace"));
    }

    @Test
    public void aKindFromAFutureServerIsStillReadable() {
        // The whole design goal: something this plugin has never heard of must survive being
        // listed, so it can also survive being left alone by an edit.
        PermissionInfo permission =
            parse("{\"action\": \"read_hyperspace\", \"hyperspace\": {\"lane\": \"7\"}}");
        Assertions.assertEquals("read_hyperspace", permission.action());
        Assertions.assertEquals("hyperspace", permission.kind());
        Assertions.assertEquals("7", permission.scope().get("lane"));
        Assertions.assertEquals(WeaviateRbacAction.DOMAIN_UNKNOWN,
            WeaviateRbacAction.domainOf(permission.action()));
        Assertions.assertFalse(WeaviateRbacKind.isKnown(permission.kind()));
    }

    @Test
    public void scopelessActionsParseWithNoKind() {
        for (String json : new String[]{
            "{\"action\": \"read_cluster\"}",
            "{\"action\": \"create_mcp\"}"}) {
            PermissionInfo permission = parse(json);
            Assertions.assertNull(permission.kind(), json);
            Assertions.assertTrue(permission.scope().isEmpty(), json);
        }
    }

    @Test
    public void aPermissionWithNoActionIsDropped() {
        Assertions.assertNull(parse("{\"data\": {\"collection\": \"*\"}}"));
    }

    @Test
    public void theKeyIgnoresFieldOrder() {
        // The diff-based save depends on this: two permissions meaning the same thing must key
        // the same however the server or the editor happened to order their fields.
        PermissionInfo a = parse(
            "{\"action\":\"create_data\",\"data\":{\"collection\":\"C\",\"tenant\":\"t\",\"object\":\"*\"}}");
        PermissionInfo b = parse(
            "{\"action\":\"create_data\",\"data\":{\"object\":\"*\",\"tenant\":\"t\",\"collection\":\"C\"}}");
        Assertions.assertEquals(a.key(), b.key());
    }

    @Test
    public void theKeySeparatesDifferentScopes() {
        PermissionInfo wide = parse(
            "{\"action\":\"read_data\",\"data\":{\"collection\":\"*\",\"tenant\":\"*\"}}");
        PermissionInfo narrow = parse(
            "{\"action\":\"read_data\",\"data\":{\"collection\":\"Orders\",\"tenant\":\"*\"}}");
        Assertions.assertNotEquals(wide.key(), narrow.key());
    }

    @Test
    public void theKeySeparatesDifferentActionsAtTheSameScope() {
        PermissionInfo read = parse("{\"action\":\"read_data\",\"data\":{\"collection\":\"C\"}}");
        PermissionInfo write = parse("{\"action\":\"update_data\",\"data\":{\"collection\":\"C\"}}");
        Assertions.assertNotEquals(read.key(), write.key());
    }

    @Test
    public void theScopeIsDescribedInAStableOrder() {
        PermissionInfo permission = parse(
            "{\"action\":\"create_data\",\"data\":{\"tenant\":\"t\",\"collection\":\"C\"}}");
        Assertions.assertEquals("collection=C, tenant=t", permission.describeScope());
        Assertions.assertEquals("", parse("{\"action\":\"read_cluster\"}").describeScope());
    }

    @Test
    public void theScopeMapIsNotSharedWithTheCaller() {
        // The editor hands these around; a permission that could be mutated behind the diff would
        // make the add/remove sets wrong in a way nothing would report.
        PermissionInfo permission = new PermissionInfo("read_data", "data",
            new java.util.HashMap<>(Map.of("collection", "C")));
        Assertions.assertThrows(UnsupportedOperationException.class,
            () -> permission.scope().put("tenant", "t"));
    }
}
