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
import java.util.Map;

/**
 * Renders pending row changes as Java client code, for "Generate SQL" and the save preview.
 * <p>
 * There is no SQL behind Weaviate, so the three batches used to answer this with a line of prose
 * per row -- {@code -- Delete object 9a834a78-... from FilterGroup}, repeated. That is a listing,
 * not a script: it says what will happen but not how, so it cannot be read to check a call, run
 * elsewhere, or pasted into an application. The Java client is the notation this plugin already
 * uses for the same job in {@link WeaviateConfigScript}, so it is what is shown here too.
 * <p>
 * Unlike that one, these snippets <b>are</b> the calls the plugin makes. The configuration editor
 * has to warn that its snippet differs from what DBeaver sends, because a config write goes over
 * REST to avoid the client's lossy round trip; a row write has no such problem and goes through
 * the client exactly as written.
 * <p>
 * Values are rendered as Java literals from the same maps the batch will send, so what is on
 * screen is what will go over the wire. Embeddings are the one exception -- a thousand-dimension
 * vector would bury everything else -- and the elision says so rather than quietly shortening.
 */
final class WeaviateDataScript {

    /** How many of a vector's numbers to show before eliding the rest. */
    private static final int VECTOR_PREVIEW = 4;

    private WeaviateDataScript() {
    }

    /**
     * The handle every snippet starts from, carrying the tenant when there is one -- it is part
     * of the call, and a snippet without it would address a different collection.
     */
    @NotNull
    private static String handle(@NotNull String collection, @Nullable String tenant) {
        String use = "client.collections.use(\"" + escape(collection) + "\"";
        return tenant == null || tenant.isBlank()
            ? use + ")"
            : use + ", b -> b.tenant(\"" + escape(tenant) + "\"))";
    }

    @NotNull
    static String renderDelete(
        @NotNull String collection, @Nullable String tenant, @NotNull List<String> ids
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Delete ").append(count(ids.size(), "object"))
            .append(" from ").append(collection).append(" using the Java client v6.\n")
            .append("// One filtered call rather than one per row, so a large selection is a\n")
            .append("// single request. verbose() is what makes the reply name the failures.\n");
        sb.append(handle(collection, tenant)).append("\n")
            .append("    .data.deleteMany(\n")
            .append("        Filter.uuid().containsAny(\n");
        List<String> quoted = new ArrayList<>(ids.size());
        for (String id : ids) {
            quoted.add("            \"" + escape(id) + "\"");
        }
        sb.append(String.join(",\n", quoted)).append("),\n")
            .append("        b -> b.verbose(true));\n");
        return sb.toString();
    }

    /**
     * @param ids the id each object will be stored under, chosen by the plugin when the grid left
     *            the cell blank -- see {@code WeaviateInsertBatch#assignId}
     */
    @NotNull
    static String renderInsert(
        @NotNull String collection,
        @Nullable String tenant,
        @NotNull List<String> ids,
        @NotNull List<Map<String, Object>> properties,
        @NotNull List<Map<String, float[]>> vectors
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Add ").append(count(ids.size(), "object"))
            .append(" to ").append(collection).append(" using the Java client v6.\n")
            .append("// The ids are chosen here rather than by the server: insertMany reports the\n")
            .append("// ids of the objects it was given, so an object sent without one comes back\n")
            .append("// without one, and the grid would have nothing to show for the new row.\n");
        sb.append(handle(collection, tenant)).append("\n")
            .append("    .data.insertMany(List.of(\n");
        List<String> objects = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            objects.add(renderObject(
                ids.get(i),
                i < properties.size() ? properties.get(i) : Map.of(),
                i < vectors.size() ? vectors.get(i) : Map.of(),
                tenant));
        }
        sb.append(String.join(",\n", objects)).append("));\n");
        return sb.toString();
    }

    @NotNull
    private static String renderObject(
        @NotNull String id,
        @NotNull Map<String, Object> properties,
        @NotNull Map<String, float[]> vectors,
        @Nullable String tenant
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("        WeaviateObject.of(o -> o\n")
            .append("            .uuid(\"").append(escape(id)).append("\")\n");
        if (tenant != null && !tenant.isBlank()) {
            sb.append("            .tenant(\"").append(escape(tenant)).append("\")\n");
        }
        sb.append("            .properties(").append(renderMap(properties)).append(")");
        for (Map.Entry<String, float[]> vector : vectors.entrySet()) {
            sb.append("\n            .vectors(Vectors.of(\"").append(escape(vector.getKey()))
                .append("\", ").append(renderVector(vector.getValue())).append("))");
        }
        return sb.append(")").toString();
    }

    /**
     * One {@code update} per row, which is what a merge costs.
     * <p>
     * The comment earns its place: {@code update} PATCHes the fields it is given and leaves the
     * rest alone, which is the whole reason editing one cell does not blank the row. {@code
     * replace} is the neighbouring method and would.
     */
    @NotNull
    static String renderUpdate(
        @NotNull String collection,
        @Nullable String tenant,
        @NotNull List<String> ids,
        @NotNull List<Map<String, Object>> properties,
        @NotNull List<Map<String, float[]>> vectors
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Merge ").append(count(ids.size(), "object"))
            .append(" in ").append(collection).append(" using the Java client v6.\n")
            .append("// Only the edited columns are sent; update() PATCHes those and leaves every\n")
            .append("// other property as it is. replace() would blank the ones not named.\n")
            .append("// A merge cannot clear a property: an unsent field and a null are the same\n")
            .append("// request, so emptying a cell leaves the stored value alone.\n")
            .append("// One call per row -- the batch endpoint only replaces, never merges.\n");
        String handle = handle(collection, tenant);
        for (int i = 0; i < ids.size(); i++) {
            Map<String, Object> props = i < properties.size() ? properties.get(i) : Map.of();
            Map<String, float[]> vecs = i < vectors.size() ? vectors.get(i) : Map.of();
            sb.append(handle).append("\n")
                .append("    .data.update(\"").append(escape(ids.get(i))).append("\", u -> u");
            if (!props.isEmpty()) {
                sb.append("\n        .properties(").append(renderMap(props)).append(")");
            }
            for (Map.Entry<String, float[]> vector : vecs.entrySet()) {
                sb.append("\n        .vectors(Vectors.of(\"").append(escape(vector.getKey()))
                    .append("\", ").append(renderVector(vector.getValue())).append("))");
            }
            sb.append(");\n");
        }
        return sb.toString();
    }

    @NotNull
    private static String renderMap(@NotNull Map<String, Object> values) {
        if (values.isEmpty()) {
            return "Map.of()";
        }
        List<String> entries = new ArrayList<>(values.size());
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            entries.add("\"" + escape(entry.getKey()) + "\", " + renderValue(entry.getValue()));
        }
        // Map.of is capped at ten pairs; ofEntries has no limit, so it is used past that rather
        // than emitting a snippet that would not compile.
        return values.size() <= 10
            ? "Map.of(" + String.join(", ", entries) + ")"
            : "Map.ofEntries(Map.entry(" + String.join("), Map.entry(", entries) + "))";
    }

    @NotNull
    private static String renderValue(@Nullable Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CharSequence || value instanceof Character) {
            return "\"" + escape(value.toString()) + "\"";
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            return value.toString();
        }
        if (value instanceof Float || value instanceof Double) {
            return value + (value instanceof Float ? "f" : "");
        }
        if (value instanceof List<?> list) {
            List<String> items = new ArrayList<>(list.size());
            for (Object item : list) {
                items.add(renderValue(item));
            }
            return "List.of(" + String.join(", ", items) + ")";
        }
        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                entries.add("\"" + escape(String.valueOf(entry.getKey())) + "\", "
                    + renderValue(entry.getValue()));
            }
            return "Map.of(" + String.join(", ", entries) + ")";
        }
        // Anything else was already coerced to a wire value, so its toString is what will be sent.
        return "\"" + escape(value.toString()) + "\"";
    }

    /**
     * A vector, shortened. Showing a thousand floats would push the rest of the call off screen,
     * and the count says what was left out so nobody reads the snippet as the whole embedding.
     */
    @NotNull
    private static String renderVector(@NotNull float[] vector) {
        StringBuilder sb = new StringBuilder("new float[]{");
        int shown = Math.min(VECTOR_PREVIEW, vector.length);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(vector[i]).append('f');
        }
        if (vector.length > shown) {
            sb.append(", /* ").append(vector.length - shown).append(" more of ")
                .append(vector.length).append(" */");
        }
        return sb.append('}').toString();
    }

    @NotNull
    private static String count(int n, @NotNull String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    @NotNull
    private static String escape(@NotNull String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
