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
 * The scope object a permission carries, and the fields inside it.
 * <p>
 * A permission is an action plus at most one sub-object saying what it applies to:
 * {@code {"action":"read_data","data":{"collection":"*","tenant":"*"}}}. The sub-object's key is
 * the kind and this class knows which fields each kind holds, so the editor can offer a Collection
 * and a Tenant box for Data but only a Collection box for Backups.
 * <p>
 * Two kinds carry nothing: {@code read_cluster} and the {@code *_mcp} actions arrive as an action
 * on its own. And one kind, {@code namespaces}, exists on the server and is not editable here --
 * it needs {@code NAMESPACES_ENABLED} - so it is listed for display and left out of
 * {@link #editable()}.
 * <p>
 * Unknown kinds are the point. {@link #fieldsOf} answers an empty list rather than throwing, and
 * the editor renders such a permission read-only instead of dropping it. That is the failure the
 * bundled client turns into an exception.
 */
public final class WeaviateRbacKind {

    /** Scope fields per kind, in the order the editor should lay them out. */
    private static final Map<String, List<String>> FIELDS = Map.ofEntries(
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

    /**
     * Fields the server fills in and the editor must not offer as free text.
     * <p>
     * {@code nodes.verbosity} is chosen by which Nodes section a rule sits in, {@code roles.scope}
     * and {@code groups.groupType} take one of a fixed pair. Offering them as a wildcard box would
     * invite values the server rejects.
     */
    private static final List<String> FIXED_FIELDS = List.of("verbosity", "scope", "groupType");

    private WeaviateRbacKind() {
    }

    /** The scope kind for an action, or null when the action carries no scope. */
    @Nullable
    public static String forAction(@Nullable String action) {
        String domain = WeaviateRbacAction.domainOf(action);
        if (WeaviateRbacAction.DOMAIN_CLUSTER.equals(domain)
            || WeaviateRbacAction.DOMAIN_MCP.equals(domain)
            || WeaviateRbacAction.DOMAIN_UNKNOWN.equals(domain)
        ) {
            return null;
        }
        return domain;
    }

    /** The fields of a kind, or empty for one this plugin does not model. */
    @NotNull
    public static List<String> fieldsOf(@Nullable String kind) {
        List<String> fields = kind == null ? null : FIELDS.get(kind.trim().toLowerCase(Locale.ROOT));
        return fields == null ? List.of() : fields;
    }

    /** Whether this plugin can lay out an editor for the kind. */
    public static boolean isKnown(@Nullable String kind) {
        return kind != null && FIELDS.containsKey(kind.trim().toLowerCase(Locale.ROOT));
    }

    /** Whether a field takes free text with a {@code *} default, as opposed to a fixed value. */
    public static boolean isFreeText(@NotNull String field) {
        return !FIXED_FIELDS.contains(field);
    }

    /**
     * Kinds the role editor offers sections for.
     * <p>
     * {@code namespaces} is excluded: it needs the server started with {@code NAMESPACES_ENABLED},
     * and a section nobody on a normal cluster can use is a section that only confuses. Permissions
     * of that kind are still listed in the tree and still survive an edit untouched.
     */
    @NotNull
    public static List<String> editable() {
        return List.of("aliases", "backups", "collections", "data", "groups", "nodes",
            "replicate", "roles", "tenants", "users");
    }

    /** The label for a scope field, e.g. {@code groupType} as {@code Group type}. */
    @NotNull
    public static String displayName(@NotNull String field) {
        StringBuilder sb = new StringBuilder();
        for (char c : field.toCharArray()) {
            if (Character.isUpperCase(c) && sb.length() > 0) {
                sb.append(' ').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        String name = sb.toString();
        return name.isEmpty() ? field : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
