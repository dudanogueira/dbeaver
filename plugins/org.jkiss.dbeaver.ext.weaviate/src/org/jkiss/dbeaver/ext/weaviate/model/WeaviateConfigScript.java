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
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders a set of configuration changes as Java client code, for the save preview.
 * <p>
 * <b>This is not the call the plugin makes, and the snippet says so.</b> DBeaver's save flow shows
 * a script before it sends anything, which is worth having for a database with no DDL: it is what
 * lists the pending changes and lets someone see what a save will do. Java client code is the
 * readable form of "what is being changed", so that is what is shown -- but the plugin sends
 * {@code PUT /v1/schema/{name}} with the collection's own definition edited in place, because the
 * client's {@code config.update} rewrites the whole configuration from its object model and drops
 * whatever that model cannot represent. Showing a snippet without saying so would be showing a
 * call that, run as written, could do something else.
 * <p>
 * Presentation lives here rather than on {@link WeaviateConfigSetting}, which is about where a
 * setting sits on the wire. The two differ more than they look: {@code stopwords.additions} is
 * read as {@code additions()} and written as {@code add()}, and a quantizer is read out of
 * {@code vectorIndexConfig} but written on the vectorizer.
 */
public final class WeaviateConfigScript {

    /**
     * One change to render.
     *
     * @param vectorName the vector whose index this belongs to, or empty for anything else
     * @param value      the new value, already as text
     */
    public record Change(
        @NotNull WeaviateConfigSetting setting,
        @NotNull String vectorName,
        @NotNull String value
    ) {
    }

    private WeaviateConfigScript() {
    }

    @NotNull
    public static String render(@NotNull String collection, @NotNull List<Change> changes) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Update a collection using the Java client v6.\n")
            .append("//\n")
            .append("// Shown for reading. DBeaver sends PUT /v1/schema/").append(collection)
            .append(" with the collection's own\n")
            .append("// definition edited in place: the call below rewrites the whole configuration\n")
            .append("// from the client's object model, which drops anything that model cannot hold.\n\n");
        if (changes.isEmpty()) {
            sb.append("// Nothing changed.\n");
            return sb.toString();
        }
        sb.append("client.collections.use(\"").append(escape(collection)).append("\")\n")
            .append("    .config.update(u -> u\n");

        List<String> lines = new ArrayList<>();
        String description = valueOf(changes, WeaviateConfigSetting.DESCRIPTION);
        if (description != null) {
            lines.add("        .description(\"" + escape(description) + "\")");
        }
        appendInvertedIndex(changes, lines);
        appendReplication(changes, lines);
        appendMultiTenancy(changes, lines);
        appendVectors(changes, lines);

        sb.append(String.join("\n", lines)).append(");\n");
        return sb.toString();
    }

    private static void appendInvertedIndex(List<Change> changes, List<String> lines) {
        String b = valueOf(changes, WeaviateConfigSetting.BM25_B);
        String k1 = valueOf(changes, WeaviateConfigSetting.BM25_K1);
        String cleanup = valueOf(changes, WeaviateConfigSetting.INVERTED_CLEANUP);
        String preset = valueOf(changes, WeaviateConfigSetting.STOPWORD_PRESET);
        String additions = valueOf(changes, WeaviateConfigSetting.STOPWORD_ADDITIONS);
        String removals = valueOf(changes, WeaviateConfigSetting.STOPWORD_REMOVALS);
        if (b == null && k1 == null && cleanup == null
            && preset == null && additions == null && removals == null) {
            return;
        }
        lines.add("        .invertedIndex(ii -> ii");
        if (b != null || k1 != null) {
            StringBuilder bm25 = new StringBuilder("            .bm25(m -> m");
            if (b != null) bm25.append(".b(").append(asFloat(b)).append(")");
            if (k1 != null) bm25.append(".k1(").append(asFloat(k1)).append(")");
            lines.add(bm25.append(")").toString());
        }
        if (cleanup != null) {
            lines.add("            .cleanupIntervalSeconds(" + asInt(cleanup) + ")");
        }
        if (preset != null || additions != null || removals != null) {
            StringBuilder stop = new StringBuilder("            .stopwords(s -> s");
            if (preset != null) stop.append(".preset(\"").append(escape(preset)).append("\")");
            // Read as additions()/removals(), written as add()/remove(): the client's own asymmetry.
            if (additions != null) stop.append(".add(").append(asWords(additions)).append(")");
            if (removals != null) stop.append(".remove(").append(asWords(removals)).append(")");
            lines.add(stop.append(")").toString());
        }
        lines.set(lines.size() - 1, lines.get(lines.size() - 1) + ")");
    }

    private static void appendReplication(List<Change> changes, List<String> lines) {
        String strategy = valueOf(changes, WeaviateConfigSetting.DELETION_STRATEGY);
        if (strategy == null) {
            return;
        }
        lines.add("        .replication(r -> r.deletionStrategy("
            + "Replication.DeletionStrategy." + constant(strategy) + "))");
    }

    private static void appendMultiTenancy(List<Change> changes, List<String> lines) {
        String creation = valueOf(changes, WeaviateConfigSetting.AUTO_TENANT_CREATION);
        String activation = valueOf(changes, WeaviateConfigSetting.AUTO_TENANT_ACTIVATION);
        if (creation == null && activation == null) {
            return;
        }
        StringBuilder sb = new StringBuilder("        .multiTenancy(m -> m");
        // enabled is restated because the client's nested lambda setter starts from a fresh
        // builder, so a call that names only one field sends the rest as null -- and multi-tenancy
        // arriving as null would ask the server to switch it off, which it refuses anyway. The
        // plugin does not send this call, but the snippet has to be one someone could run.
        sb.append(".enabled(true)");
        if (creation != null) sb.append(".autoTenantCreation(").append(creation).append(")");
        if (activation != null) sb.append(".autoTenantActivation(").append(activation).append(")");
        lines.add(sb.append(")").toString());
    }

    private static void appendVectors(List<Change> changes, List<String> lines) {
        Map<String, List<Change>> byVector = new LinkedHashMap<>();
        for (Change change : changes) {
            if (change.setting().getGroup().isPerVector()) {
                byVector.computeIfAbsent(change.vectorName(), name -> new ArrayList<>()).add(change);
            }
        }
        for (Map.Entry<String, List<Change>> entry : byVector.entrySet()) {
            String name = entry.getKey();
            List<String> calls = new ArrayList<>();
            for (Change change : entry.getValue()) {
                calls.add(hnswCall(change));
            }
            // selfProvided is the vectorizer the fixtures use. A real snippet has to name whichever
            // one the collection has, and the whole VectorConfig is rebuilt either way -- the
            // client offers no way to change an index without restating its vectorizer.
            String target = name.isEmpty()
                ? "VectorConfig.selfProvided(v -> v"
                : "VectorConfig.selfProvided(\"" + escape(name) + "\", v -> v";
            lines.add("        .vectorConfig(" + target);
            lines.add("            .vectorIndex(Hnsw.of(h -> h");
            for (int i = 0; i < calls.size(); i++) {
                lines.add("                ." + calls.get(i) + (i == calls.size() - 1 ? "))))" : ""));
            }
        }
    }

    @NotNull
    private static String hnswCall(@NotNull Change change) {
        return switch (change.setting()) {
            case EF -> "ef(" + asInt(change.value()) + ")";
            case DYNAMIC_EF_MIN -> "dynamicEfMin(" + asInt(change.value()) + ")";
            case DYNAMIC_EF_MAX -> "dynamicEfMax(" + asInt(change.value()) + ")";
            case DYNAMIC_EF_FACTOR -> "dynamicEfFactor(" + asInt(change.value()) + ")";
            case FLAT_SEARCH_CUTOFF -> "flatSearchCutoff(" + asInt(change.value()) + ")";
            case VECTOR_CACHE_MAX -> "vectorCacheMaxObjects(" + asInt(change.value()) + "L)";
            case FILTER_STRATEGY -> "filterStrategy(Hnsw.FilterStrategy."
                + change.value().toUpperCase(Locale.ROOT) + ")";
            case SKIP -> "skipVectorization(" + change.value() + ")";
            // Not an Hnsw call at all: the client hangs quantization off the vectorizer, one level
            // up from the index, while the server reports it inside vectorIndexConfig. Rendered
            // here anyway so the snippet stays one readable block; see the note it carries.
            case QUANTIZER -> "/* .quantization(Quantization." + change.value() + "()) */";
            default -> change.setting().name().toLowerCase(Locale.ROOT) + "(" + change.value() + ")";
        };
    }

    @Nullable
    private static String valueOf(List<Change> changes, WeaviateConfigSetting setting) {
        for (Change change : changes) {
            if (change.setting() == setting) {
                return change.value();
            }
        }
        return null;
    }

    /** {@code TimeBasedResolution} as the client spells it: {@code TIME_BASED_RESOLUTION}. */
    @NotNull
    static String constant(@NotNull String camel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    @NotNull
    private static String asWords(@NotNull String commaSeparated) {
        List<String> quoted = new ArrayList<>();
        for (String word : commaSeparated.split(",")) {
            String trimmed = word.strip();
            if (!trimmed.isEmpty()) {
                quoted.add("\"" + escape(trimmed) + "\"");
            }
        }
        return String.join(", ", quoted);
    }

    @NotNull
    private static String asFloat(@NotNull String value) {
        return value.strip() + (value.contains(".") ? "f" : ".0f");
    }

    @NotNull
    private static String asInt(@NotNull String value) {
        String trimmed = value.strip();
        return trimmed.endsWith(".0") ? trimmed.substring(0, trimmed.length() - 2) : trimmed;
    }

    @NotNull
    private static String escape(@NotNull String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
