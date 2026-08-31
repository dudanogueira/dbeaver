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

import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * The scope fields the editor lays out per permission kind.
 */
public class WeaviateRbacKindTest extends DBeaverUnitTest {

    /**
     * The sub-objects of {@code Permission} and their fields, from the swagger definition.
     * Checked against a live 1.39.0, where these are exactly the shapes the admin role carries.
     */
    private static final Map<String, List<String>> SERVER_KINDS = Map.ofEntries(
        Map.entry("aliases", List.of("collection", "alias")),
        Map.entry("backups", List.of("collection")),
        Map.entry("collections", List.of("collection")),
        Map.entry("data", List.of("collection", "tenant", "object")),
        Map.entry("groups", List.of("group", "groupType")),
        Map.entry("namespaces", List.of("namespace")),
        Map.entry("nodes", List.of("verbosity", "collection")),
        Map.entry("replicate", List.of("collection", "shard")),
        Map.entry("roles", List.of("role", "scope")),
        Map.entry("tenants", List.of("collection", "tenant")),
        Map.entry("users", List.of("users")));

    @Test
    public void everyKindTheServerDefinesIsKnown() {
        for (String kind : SERVER_KINDS.keySet()) {
            Assertions.assertTrue(WeaviateRbacKind.isKnown(kind),
                () -> kind + " is a permission kind the server defines and this plugin lacks it");
        }
    }

    @Test
    public void everyKindCarriesTheFieldsTheServerDoes() {
        SERVER_KINDS.forEach((kind, fields) -> Assertions.assertEquals(
            fields.stream().sorted().toList(),
            WeaviateRbacKind.fieldsOf(kind).stream().sorted().toList(),
            () -> "scope fields of " + kind));
    }

    @Test
    public void anUnknownKindDegradesRatherThanThrowing() {
        for (String odd : new String[]{null, "", "  ", "hyperspace"}) {
            Assertions.assertFalse(WeaviateRbacKind.isKnown(odd), () -> "[" + odd + "]");
            Assertions.assertTrue(WeaviateRbacKind.fieldsOf(odd).isEmpty(), () -> "[" + odd + "]");
        }
    }

    @Test
    public void scopelessActionsMapToNoKind() {
        Assertions.assertNull(WeaviateRbacKind.forAction("read_cluster"));
        Assertions.assertNull(WeaviateRbacKind.forAction("create_mcp"));
        Assertions.assertNull(WeaviateRbacKind.forAction("read_hyperspace"));
    }

    @Test
    public void scopedActionsMapToTheirKind() {
        Assertions.assertEquals("data", WeaviateRbacKind.forAction("read_data"));
        Assertions.assertEquals("tenants", WeaviateRbacKind.forAction("delete_tenants"));
        Assertions.assertEquals("namespaces", WeaviateRbacKind.forAction("manage_namespaces"));
    }

    @Test
    public void editableKindsAreAllRealKinds() {
        for (String kind : WeaviateRbacKind.editable()) {
            Assertions.assertTrue(SERVER_KINDS.containsKey(kind),
                () -> kind + " is offered for editing and the server does not define it");
        }
    }

    @Test
    public void namespacesIsShownButNotEdited() {
        // It needs the server started with NAMESPACES_ENABLED, so an editor section for it would
        // only produce writes the server refuses. It stays readable.
        Assertions.assertTrue(WeaviateRbacKind.isKnown("namespaces"));
        Assertions.assertFalse(WeaviateRbacKind.editable().contains("namespaces"));
    }

    @Test
    public void fixedFieldsAreNotOfferedAsFreeText() {
        // The server takes one of a fixed pair for these; a wildcard box would invite refusals.
        Assertions.assertFalse(WeaviateRbacKind.isFreeText("verbosity"));
        Assertions.assertFalse(WeaviateRbacKind.isFreeText("scope"));
        Assertions.assertFalse(WeaviateRbacKind.isFreeText("groupType"));
        Assertions.assertTrue(WeaviateRbacKind.isFreeText("collection"));
        Assertions.assertTrue(WeaviateRbacKind.isFreeText("tenant"));
    }

    @Test
    public void fieldLabelsAreReadable() {
        Assertions.assertEquals("Collection", WeaviateRbacKind.displayName("collection"));
        Assertions.assertEquals("Group type", WeaviateRbacKind.displayName("groupType"));
    }
}
