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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A group of permissions that share a scope, which is how a person thinks about them.
 * <p>
 * Weaviate stores one permission per action: read, create, update and delete on the same
 * collection are four separate entries that happen to carry identical scopes. Editing them
 * individually would mean typing that collection name four times and keeping the four copies in
 * step, so the editor groups them -- "on collection Orders, tenant *: read and update" is one
 * rule with two actions ticked.
 * <p>
 * The grouping is exact, not approximate: {@link #fromPermissions} splits on the whole scope, so
 * two data rules at different collections stay two rules. That case is the one a
 * one-rule-per-kind editor silently merges, and merging it would quietly widen or narrow
 * somebody's access.
 */
public record WeaviateRoleRule(
    @Nullable String kind,
    @NotNull Map<String, String> scope,
    @NotNull Set<String> actions
) {

    public WeaviateRoleRule {
        scope = scope == null ? Map.of() : Map.copyOf(scope);
        actions = actions == null ? Set.of() : new LinkedHashSet<>(actions);
    }

    /** The permissions of a role, grouped into rules, preserving the order they arrived in. */
    @NotNull
    public static List<WeaviateRoleRule> fromPermissions(
        @NotNull List<WeaviateRbacRest.PermissionInfo> permissions
    ) {
        Map<String, List<WeaviateRbacRest.PermissionInfo>> grouped = new LinkedHashMap<>();
        for (WeaviateRbacRest.PermissionInfo permission : permissions) {
            grouped.computeIfAbsent(scopeKey(permission), k -> new ArrayList<>()).add(permission);
        }
        List<WeaviateRoleRule> rules = new ArrayList<>(grouped.size());
        for (List<WeaviateRbacRest.PermissionInfo> group : grouped.values()) {
            Set<String> actions = new LinkedHashSet<>();
            for (WeaviateRbacRest.PermissionInfo permission : group) {
                actions.add(permission.action());
            }
            rules.add(new WeaviateRoleRule(group.get(0).kind(), group.get(0).scope(), actions));
        }
        return rules;
    }

    /** The permissions a set of rules stands for: one per action, all sharing the rule's scope. */
    @NotNull
    public static List<WeaviateRbacRest.PermissionInfo> toPermissions(
        @NotNull List<WeaviateRoleRule> rules
    ) {
        List<WeaviateRbacRest.PermissionInfo> permissions = new ArrayList<>();
        for (WeaviateRoleRule rule : rules) {
            for (String action : rule.actions()) {
                permissions.add(
                    new WeaviateRbacRest.PermissionInfo(action, rule.kind(), rule.scope()));
            }
        }
        return permissions;
    }

    /**
     * A rule for a kind, with every scope field defaulted to {@code *}.
     * <p>
     * {@code *} rather than blank because that is what the server means by an omitted scope, and
     * a box that looks empty when it means "everything" is the kind of thing people grant by
     * accident.
     */
    @NotNull
    public static WeaviateRoleRule blank(@Nullable String kind) {
        Map<String, String> scope = new LinkedHashMap<>();
        for (String field : WeaviateRbacKind.fieldsOf(kind)) {
            scope.put(field, defaultFor(field));
        }
        return new WeaviateRoleRule(kind, scope, Set.of());
    }

    /** What a scope field starts as. The fixed-value fields have a server default of their own. */
    @NotNull
    public static String defaultFor(@NotNull String field) {
        return switch (field) {
            case "verbosity" -> "minimal";
            case "scope" -> "match";
            case "groupType" -> "oidc";
            default -> "*";
        };
    }

    /** The values a fixed-value field accepts, or empty when it takes free text. */
    @NotNull
    public static List<String> choicesFor(@NotNull String field) {
        return switch (field) {
            case "verbosity" -> List.of("minimal", "verbose");
            case "scope" -> List.of("match", "all");
            case "groupType" -> List.of("oidc");
            default -> List.of();
        };
    }

    /** Whether this build can lay out an editor for the rule, as opposed to only showing it. */
    public boolean isEditable() {
        if (kind != null && !WeaviateRbacKind.isKnown(kind)) {
            return false;
        }
        for (String action : actions) {
            if (!WeaviateRbacAction.isKnown(action) || WeaviateRbacAction.isLegacy(action)) {
                return false;
            }
        }
        return true;
    }

    /** The domain whose actions this rule may hold, for the checkbox list. */
    @NotNull
    public String domain() {
        if (kind != null) {
            return kind;
        }
        return actions.isEmpty()
            ? WeaviateRbacAction.DOMAIN_UNKNOWN
            : WeaviateRbacAction.domainOf(actions.iterator().next());
    }

    @NotNull
    public String describeScope() {
        if (scope.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(scope).entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    @NotNull
    public String describeActions() {
        List<String> verbs = new ArrayList<>();
        for (String action : actions) {
            verbs.add(WeaviateRbacAction.verbOf(action));
        }
        return String.join(", ", verbs);
    }

    /** Identity for grouping: the kind plus every scope field, order-independent. */
    @NotNull
    private static String scopeKey(@NotNull WeaviateRbacRest.PermissionInfo permission) {
        StringBuilder sb = new StringBuilder(permission.kind() == null ? "" : permission.kind());
        for (Map.Entry<String, String> e : new TreeMap<>(permission.scope()).entrySet()) {
            sb.append('|').append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
