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
import org.jkiss.code.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The permission actions Weaviate understands, and which domain each belongs to.
 * <p>
 * Deliberately constants and lookups rather than an enum. The bundled client models this
 * vocabulary as closed enums and it has cost twice already: {@code Permission.Kind} throws on
 * {@code namespaces}, which every 1.38+ server puts in its built-in roles, and
 * {@code McpPermission.Action} maps 1.37's {@code manage_mcp} to null. An action this plugin has
 * never seen has to remain displayable, so {@link #domainOf} answers {@link #DOMAIN_UNKNOWN}
 * rather than failing, and nothing here rejects a string for being unfamiliar.
 * <p>
 * The list mirrors {@code usecases/auth/authorization/types.go}. Actions arrived over time --
 * 15 in 1.28, tenants and users in 1.29, user CRUD in 1.30, replicate in 1.31, aliases in 1.32,
 * groups in 1.33, mcp in 1.37 and split in 1.38 alongside namespaces -- so what a given server
 * accepts depends on its version. See {@link WeaviateServerFeature}.
 */
public final class WeaviateRbacAction {

    public static final String DOMAIN_ALIASES = "aliases";
    public static final String DOMAIN_BACKUPS = "backups";
    public static final String DOMAIN_CLUSTER = "cluster";
    public static final String DOMAIN_COLLECTIONS = "collections";
    public static final String DOMAIN_DATA = "data";
    public static final String DOMAIN_GROUPS = "groups";
    public static final String DOMAIN_MCP = "mcp";
    public static final String DOMAIN_NAMESPACES = "namespaces";
    public static final String DOMAIN_NODES = "nodes";
    public static final String DOMAIN_REPLICATE = "replicate";
    public static final String DOMAIN_ROLES = "roles";
    public static final String DOMAIN_TENANTS = "tenants";
    public static final String DOMAIN_USERS = "users";
    /** An action from a server newer than this plugin. Shown, never rejected. */
    public static final String DOMAIN_UNKNOWN = "unknown";

    /**
     * Every action the server defines, by domain, in the order the editor should offer them.
     * <p>
     * CRUD is listed create/read/update/delete rather than alphabetically, because that is the
     * order people read a permission grid in.
     */
    private static final Map<String, List<String>> BY_DOMAIN = Map.ofEntries(
        Map.entry(DOMAIN_ALIASES,
            List.of("create_aliases", "read_aliases", "update_aliases", "delete_aliases")),
        Map.entry(DOMAIN_BACKUPS, List.of("manage_backups")),
        Map.entry(DOMAIN_CLUSTER, List.of("read_cluster")),
        Map.entry(DOMAIN_COLLECTIONS,
            List.of("create_collections", "read_collections", "update_collections",
                "delete_collections")),
        Map.entry(DOMAIN_DATA,
            List.of("create_data", "read_data", "update_data", "delete_data")),
        Map.entry(DOMAIN_GROUPS, List.of("read_groups", "assign_and_revoke_groups")),
        Map.entry(DOMAIN_MCP, List.of("create_mcp", "read_mcp", "update_mcp")),
        Map.entry(DOMAIN_NAMESPACES, List.of("manage_namespaces")),
        Map.entry(DOMAIN_NODES, List.of("read_nodes")),
        Map.entry(DOMAIN_REPLICATE,
            List.of("create_replicate", "read_replicate", "update_replicate", "delete_replicate")),
        Map.entry(DOMAIN_ROLES,
            List.of("create_roles", "read_roles", "update_roles", "delete_roles")),
        Map.entry(DOMAIN_TENANTS,
            List.of("create_tenants", "read_tenants", "update_tenants", "delete_tenants")),
        Map.entry(DOMAIN_USERS,
            List.of("create_users", "read_users", "update_users", "delete_users",
                "assign_and_revoke_users")));

    /**
     * Actions the server still reads from policies written by 1.28 but refuses on a write.
     * <p>
     * They can appear in a role and must survive being displayed and re-saved, which the
     * diff-based editor manages by never touching a permission the user did not edit.
     */
    private static final List<String> LEGACY =
        List.of("manage_collections", "manage_data", "manage_roles", "manage_mcp");

    private WeaviateRbacAction() {
    }

    /** The domains, in the order the role editor lists its sections. */
    @NotNull
    public static List<String> domains() {
        return List.of(DOMAIN_ALIASES, DOMAIN_BACKUPS, DOMAIN_CLUSTER, DOMAIN_COLLECTIONS,
            DOMAIN_DATA, DOMAIN_GROUPS, DOMAIN_MCP, DOMAIN_NAMESPACES, DOMAIN_NODES,
            DOMAIN_REPLICATE, DOMAIN_ROLES, DOMAIN_TENANTS, DOMAIN_USERS);
    }

    /** The actions of one domain, or an empty list for a domain this plugin does not know. */
    @NotNull
    public static List<String> actionsOf(@Nullable String domain) {
        List<String> actions = domain == null ? null : BY_DOMAIN.get(domain);
        return actions == null ? List.of() : actions;
    }

    /** Every action this plugin knows, across all domains. */
    @NotNull
    public static List<String> all() {
        return domains().stream().flatMap(d -> actionsOf(d).stream()).toList();
    }

    /**
     * Which domain an action belongs to, or {@link #DOMAIN_UNKNOWN}.
     * <p>
     * Never throws and never returns null. An action from a newer server lands in UNKNOWN and is
     * still shown; that is the whole difference between this and the client's enum.
     */
    @NotNull
    public static String domainOf(@Nullable String action) {
        if (action == null || action.isBlank()) {
            return DOMAIN_UNKNOWN;
        }
        String name = action.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> e : BY_DOMAIN.entrySet()) {
            if (e.getValue().contains(name)) {
                return e.getKey();
            }
        }
        // Legacy spellings still name their domain, which is what a reader needs to see.
        if (LEGACY.contains(name)) {
            int underscore = name.indexOf('_');
            String suffix = underscore < 0 ? name : name.substring(underscore + 1);
            return BY_DOMAIN.containsKey(suffix) ? suffix : DOMAIN_UNKNOWN;
        }
        return DOMAIN_UNKNOWN;
    }

    /** Whether this plugin knows the action, as opposed to merely being able to display it. */
    public static boolean isKnown(@Nullable String action) {
        return !DOMAIN_UNKNOWN.equals(domainOf(action));
    }

    /** Whether the server would refuse this action on a write, even though it may read it back. */
    public static boolean isLegacy(@Nullable String action) {
        return action != null && LEGACY.contains(action.trim().toLowerCase(Locale.ROOT));
    }

    /** {@code create_data} as {@code Create}, for a checkbox beside its siblings. */
    @NotNull
    public static String verbOf(@NotNull String action) {
        String name = action.trim().toLowerCase(Locale.ROOT);
        int underscore = name.lastIndexOf('_');
        String verb = underscore < 0 ? name : name.substring(0, underscore);
        if ("assign_and_revoke".equals(verb)) {
            return "Assign and revoke";
        }
        return verb.isEmpty() ? name : Character.toUpperCase(verb.charAt(0)) + verb.substring(1);
    }

    /** {@code manage_backups} as {@code Manage backups}, for a tree row or a tooltip. */
    @NotNull
    public static String displayName(@NotNull String action) {
        String name = action.trim().replace('_', ' ');
        return name.isEmpty() ? action : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
