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
import java.util.List;
import java.util.regex.Pattern;

/**
 * Selects tenants by name, for both the search box and the bulk actions that follow it.
 * <p>
 * Two behaviours behind one input, chosen by whether the text contains a wildcard:
 * <ul>
 *   <li><b>No wildcard</b> -- a case-insensitive <i>substring</i> match. Typing {@code acme}
 *       while hunting for a tenant should show every acme tenant, not nothing.</li>
 *   <li><b>With {@code *} or {@code ?}</b> -- a case-insensitive <i>glob</i> matched against the
 *       whole name. Typing {@code acme-eu-*} means those and no others.</li>
 * </ul>
 * The split matters because the same box drives a bulk deactivate. Substring is the forgiving
 * default for finding something; the moment a pattern is written, it is being used to define a
 * set, and a set that quietly included extra members would be acted on.
 * <p>
 * Anchoring follows from the same reasoning. {@code acme-*} as a substring would also match
 * {@code globex-acme-1}; as an anchored glob it does not, which is what someone writing a
 * pattern before pressing "Deactivate" means.
 */
public final class WeaviateTenantFilter {

    private static final WeaviateTenantFilter MATCH_ALL = new WeaviateTenantFilter("", null, false);

    private final String text;
    /** Null when this is a plain substring filter, or when it matches everything. */
    @Nullable
    private final Pattern glob;
    private final boolean wildcard;

    private WeaviateTenantFilter(@NotNull String text, @Nullable Pattern glob, boolean wildcard) {
        this.text = text;
        this.glob = glob;
        this.wildcard = wildcard;
    }

    /**
     * Builds a filter from what the user typed. Blank text matches everything.
     */
    @NotNull
    public static WeaviateTenantFilter of(@Nullable String pattern) {
        String trimmed = pattern == null ? "" : pattern.trim();
        if (trimmed.isEmpty()) {
            return MATCH_ALL;
        }
        boolean hasWildcard = trimmed.indexOf('*') >= 0 || trimmed.indexOf('?') >= 0;
        if (!hasWildcard) {
            return new WeaviateTenantFilter(trimmed.toLowerCase(java.util.Locale.ROOT), null, false);
        }
        // A pattern of nothing but wildcards is the same as no filter, and saying so keeps the
        // count label honest rather than reporting a glob that excludes nothing.
        if (trimmed.chars().allMatch(c -> c == '*')) {
            return MATCH_ALL;
        }
        return new WeaviateTenantFilter(trimmed, Pattern.compile(
            toRegex(trimmed), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), true);
    }

    /**
     * Translates a glob into an anchored regex.
     * <p>
     * Literal runs go through {@link Pattern#quote}, which is the only safe way to do this: tenant
     * names are arbitrary strings, and escaping character by character breaks on any non-ASCII
     * letter, where Java rejects a backslash before an alphabetic character it does not recognise.
     */
    @NotNull
    private static String toRegex(@NotNull String glob) {
        StringBuilder regex = new StringBuilder(glob.length() + 8);
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c != '*' && c != '?') {
                literal.append(c);
                continue;
            }
            if (literal.length() > 0) {
                regex.append(Pattern.quote(literal.toString()));
                literal.setLength(0);
            }
            regex.append(c == '*' ? ".*" : ".");
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return regex.toString();
    }

    /**
     * Whether the text was read as a glob. The dialog says which of the two readings it used,
     * since the difference decides what a bulk action is about to touch.
     */
    public boolean isWildcard() {
        return wildcard;
    }

    public boolean isMatchAll() {
        return glob == null && text.isEmpty();
    }

    public boolean matches(@Nullable String name) {
        if (name == null) {
            return false;
        }
        if (isMatchAll()) {
            return true;
        }
        if (glob != null) {
            return glob.matcher(name).matches();
        }
        return name.toLowerCase(java.util.Locale.ROOT).contains(text);
    }

    @NotNull
    public List<WeaviateTenant> filter(@NotNull List<WeaviateTenant> tenants) {
        if (isMatchAll()) {
            return tenants;
        }
        List<WeaviateTenant> matched = new ArrayList<>();
        for (WeaviateTenant tenant : tenants) {
            if (matches(tenant.name())) {
                matched.add(tenant);
            }
        }
        return matched;
    }
}
