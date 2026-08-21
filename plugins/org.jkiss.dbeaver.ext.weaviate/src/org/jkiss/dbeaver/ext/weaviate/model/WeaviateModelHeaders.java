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
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds the HTTP headers sent with every Weaviate request so that vectorizer, reranker and
 * generative modules can authenticate against their model provider.
 * <p>
 * Values are stored as <b>auth properties</b>, not provider properties: auth properties go to
 * DBeaver's secrets store, while provider properties are serialized in clear text into
 * {@code data-sources.json}. These are third-party API keys, so they belong in the former.
 * <p>
 * Precedence, highest first:
 * <ol>
 *   <li>a custom header row -- an explicit header name always wins, including over a provider
 *       row for the same header, so a user can override anything this class does not model;</li>
 *   <li>the provider's configured key;</li>
 *   <li>the first non-empty environment variable named by the provider.</li>
 * </ol>
 * Sending no header at all is the remaining case, which leaves the server's own configuration
 * in charge -- the pre-existing behaviour.
 */
public class WeaviateModelHeaders {

    /** Auth-property key prefix for a provider's key, e.g. {@code modelKey.openai}. */
    public static final String KEY_PREFIX = "modelKey.";

    /** Auth-property key prefix for a custom header, e.g. {@code modelHeader.X-Custom}. */
    public static final String HEADER_PREFIX = "modelHeader.";

    private WeaviateModelHeaders() {
        // Utility class
    }

    @NotNull
    public static String keyProperty(@NotNull WeaviateModelProvider provider) {
        return KEY_PREFIX + provider.getId();
    }

    @NotNull
    public static String headerProperty(@NotNull String headerName) {
        return HEADER_PREFIX + headerName;
    }

    /**
     * Resolve the headers to send for this connection. Never returns null; an empty map means
     * "send nothing extra", which leaves the server's own environment in charge.
     */
    @NotNull
    public static Map<String, String> resolve(@NotNull DBPConnectionConfiguration cfg) {
        return resolve(cfg, System::getenv);
    }

    /**
     * @param env environment lookup, injected so the precedence rules are testable without
     *            mutating the real process environment.
     */
    @NotNull
    public static Map<String, String> resolve(
        @NotNull DBPConnectionConfiguration cfg,
        @NotNull Function<String, String> env
    ) {
        Map<String, String> headers = new LinkedHashMap<>();

        for (WeaviateModelProvider provider : WeaviateModelProvider.values()) {
            String value = trimToNull(cfg.getAuthProperty(keyProperty(provider)));
            if (value == null) {
                value = firstEnvValue(provider, env);
            }
            if (value != null) {
                headers.put(provider.getHeader(), value);
            }
        }

        // Applied last so an explicit header name overrides a provider row for the same header.
        for (Map.Entry<String, String> e : cfg.getAuthProperties().entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(HEADER_PREFIX)) {
                continue;
            }
            String name = key.substring(HEADER_PREFIX.length()).trim();
            String value = trimToNull(e.getValue());
            if (!name.isEmpty() && value != null) {
                headers.put(name, value);
            }
        }
        return headers;
    }

    @Nullable
    private static String firstEnvValue(
        @NotNull WeaviateModelProvider provider,
        @NotNull Function<String, String> env
    ) {
        for (String name : provider.getEnvVars()) {
            String value = trimToNull(env.apply(name));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Which environment variable a provider's key would come from, or null if none is set.
     * Used by the UI to show what a blank field falls back to, so the effective key is never
     * invisible to the user.
     */
    @Nullable
    public static String envSourceFor(@NotNull WeaviateModelProvider provider) {
        for (String name : provider.getEnvVars()) {
            if (trimToNull(System.getenv(name)) != null) {
                return name;
            }
        }
        return null;
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
