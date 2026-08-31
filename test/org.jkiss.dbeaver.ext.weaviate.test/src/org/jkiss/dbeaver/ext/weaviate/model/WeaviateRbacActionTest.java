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

/**
 * The action vocabulary is resolved from strings rather than an enum, and this is why.
 */
public class WeaviateRbacActionTest extends DBeaverUnitTest {

    /**
     * Every action the server defines, from {@code usecases/auth/authorization/types.go}.
     * <p>
     * The list is the point of the test. The bundled client models this vocabulary as closed
     * enums and it fails two different ways on it: {@code Permission.Kind} has no
     * {@code namespaces} and <em>throws</em> when a role carries one, and
     * {@code McpPermission.Action} has no {@code manage_mcp} and maps it to null. Every server
     * from 1.38 puts {@code manage_namespaces} into its built-in admin and root roles, so that
     * first failure is every cluster on the first request, not an edge case.
     */
    private static final List<String> SERVER_ACTIONS = List.of(
        "manage_backups", "read_cluster",
        "create_data", "read_data", "update_data", "delete_data",
        "read_nodes",
        "create_roles", "read_roles", "update_roles", "delete_roles",
        "create_collections", "read_collections", "update_collections", "delete_collections",
        "assign_and_revoke_users", "create_users", "read_users", "update_users", "delete_users",
        "create_tenants", "read_tenants", "update_tenants", "delete_tenants",
        "create_replicate", "read_replicate", "update_replicate", "delete_replicate",
        "create_aliases", "read_aliases", "update_aliases", "delete_aliases",
        "assign_and_revoke_groups", "read_groups",
        "create_mcp", "read_mcp", "update_mcp",
        "manage_namespaces");

    @Test
    public void theServerDefinesThirtyEightActions() {
        // A guard on the fixture itself: if the server grows one and this list is updated without
        // WeaviateRbacAction being updated, the next test is what fails, and it names the action.
        Assertions.assertEquals(38, SERVER_ACTIONS.size());
    }

    @Test
    public void everyActionTheServerDefinesIsKnown() {
        for (String action : SERVER_ACTIONS) {
            Assertions.assertTrue(WeaviateRbacAction.isKnown(action),
                () -> action + " is an action the server defines and this plugin cannot place it");
        }
    }

    @Test
    public void thisPluginClaimsNoActionTheServerDoesNot() {
        // The other direction. An action here that the server dropped would be offered in the
        // editor and refused on save, which is a worse failure than not offering it.
        for (String action : WeaviateRbacAction.all()) {
            Assertions.assertTrue(SERVER_ACTIONS.contains(action),
                () -> action + " is offered by this plugin and the server does not define it");
        }
    }

    @Test
    public void namespacesIsNotLost() {
        // The specific gap, and the reason none of this goes through the client.
        Assertions.assertEquals(WeaviateRbacAction.DOMAIN_NAMESPACES,
            WeaviateRbacAction.domainOf("manage_namespaces"));
    }

    @Test
    public void anUnknownActionIsPlacedRatherThanRejected() {
        for (String odd : new String[]{null, "", "   ", "teleport_data", "manage_quantum"}) {
            Assertions.assertEquals(WeaviateRbacAction.DOMAIN_UNKNOWN,
                WeaviateRbacAction.domainOf(odd),
                () -> "expected UNKNOWN for [" + odd + "]");
        }
    }

    @Test
    public void legacyActionsStillNameTheirDomain() {
        // 1.28 wrote manage_collections/manage_data/manage_roles, and the server still reads them
        // back out of old policies. A role carrying one has to remain readable.
        Assertions.assertEquals(WeaviateRbacAction.DOMAIN_COLLECTIONS,
            WeaviateRbacAction.domainOf("manage_collections"));
        Assertions.assertEquals(WeaviateRbacAction.DOMAIN_DATA,
            WeaviateRbacAction.domainOf("manage_data"));
        Assertions.assertEquals(WeaviateRbacAction.DOMAIN_ROLES,
            WeaviateRbacAction.domainOf("manage_roles"));
        Assertions.assertEquals(WeaviateRbacAction.DOMAIN_MCP,
            WeaviateRbacAction.domainOf("manage_mcp"));
        for (String legacy : new String[]{"manage_collections", "manage_data", "manage_roles"}) {
            Assertions.assertTrue(WeaviateRbacAction.isLegacy(legacy), legacy);
        }
        Assertions.assertFalse(WeaviateRbacAction.isLegacy("read_data"));
    }

    @Test
    public void everyDomainHasAtLeastOneAction() {
        for (String domain : WeaviateRbacAction.domains()) {
            Assertions.assertFalse(WeaviateRbacAction.actionsOf(domain).isEmpty(),
                () -> domain + " has no actions");
        }
        Assertions.assertTrue(WeaviateRbacAction.actionsOf("nonsense").isEmpty());
        Assertions.assertTrue(WeaviateRbacAction.actionsOf(null).isEmpty());
    }

    @Test
    public void verbsReadAsCheckboxLabels() {
        Assertions.assertEquals("Create", WeaviateRbacAction.verbOf("create_data"));
        Assertions.assertEquals("Read", WeaviateRbacAction.verbOf("read_cluster"));
        Assertions.assertEquals("Manage", WeaviateRbacAction.verbOf("manage_backups"));
        Assertions.assertEquals("Assign and revoke",
            WeaviateRbacAction.verbOf("assign_and_revoke_users"));
    }

    @Test
    public void displayNamesAreReadable() {
        Assertions.assertEquals("Manage backups", WeaviateRbacAction.displayName("manage_backups"));
        Assertions.assertEquals("Read data", WeaviateRbacAction.displayName("read_data"));
    }
}
