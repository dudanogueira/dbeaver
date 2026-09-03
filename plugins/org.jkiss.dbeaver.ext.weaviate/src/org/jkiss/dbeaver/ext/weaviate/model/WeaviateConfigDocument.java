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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A collection definition as the server stores it, addressed by dotted path.
 * <p>
 * The carrier for every configuration edit. A change is made by reading the document the server
 * returned, setting the paths that changed, and sending the whole thing back -- so anything this
 * build does not understand travels through untouched, because it was never parsed into a model
 * that could drop it. That is the difference between this and the bundled client's
 * {@code config.update}, which re-serializes its own object model and loses whatever that model
 * cannot represent.
 * <p>
 * <b>Nothing here exposes a JSON type.</b> The UI bundle's classloader can see neither the shaded
 * client nor, reliably, the JSON library, so every value crosses the boundary as a String, a
 * boxed primitive or a List of Strings -- the same rule {@link WeaviateSchemaJson#validate} was
 * written for.
 * <p>
 * Paths are dotted and address objects only: {@code invertedIndexConfig.bm25.b}. Array indexing is
 * deliberately absent. The one place a collection's definition holds an array worth editing is its
 * property list, and a property cannot be edited through this endpoint at all -- the server
 * refuses every property field except {@code description}.
 */
public class WeaviateConfigDocument {

    private final JsonObject root;

    private WeaviateConfigDocument(@NotNull JsonObject root) {
        this.root = root;
    }

    /**
     * Parse a definition.
     *
     * @throws DBException when the body is not a JSON object, which means the server answered with
     *                     something other than a collection and the caller should say so rather
     *                     than edit it
     */
    @NotNull
    public static WeaviateConfigDocument of(@NotNull String rawJson) throws DBException {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(rawJson);
        } catch (RuntimeException e) {
            throw new DBException("Weaviate returned an unreadable collection definition", e);
        }
        if (parsed == null || !parsed.isJsonObject()) {
            throw new DBException("Weaviate returned a collection definition that is not an object");
        }
        return new WeaviateConfigDocument(parsed.getAsJsonObject());
    }

    /** A deep copy, so an edit can be abandoned without having damaged the original. */
    @NotNull
    public WeaviateConfigDocument copy() {
        return new WeaviateConfigDocument(root.deepCopy());
    }

    @NotNull
    public String getCollectionName() {
        JsonElement name = root.get("class");
        return name != null && name.isJsonPrimitive() ? name.getAsString() : "";
    }

    /** Whether the path is present at all. Absent is not the same as false; see {@link #getBoolean}. */
    public boolean has(@NotNull String path) {
        return find(path) != null;
    }

    @Nullable
    public String getString(@NotNull String path) {
        JsonElement value = find(path);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    /**
     * @return null when the path is absent or does not hold a number -- never a substituted zero,
     *         since "the server did not report this" and "the server reported 0" are different
     *         answers and a form that conflates them invites someone to change a setting that is
     *         not there
     */
    @Nullable
    public Double getNumber(@NotNull String path) {
        JsonElement value = find(path);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        return value.getAsDouble();
    }

    @Nullable
    public Boolean getBoolean(@NotNull String path) {
        JsonElement value = find(path);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            return null;
        }
        return value.getAsBoolean();
    }

    /** @return an empty list when absent; an absent list and an empty one mean the same thing here */
    @NotNull
    public List<String> getStrings(@NotNull String path) {
        JsonElement value = find(path);
        List<String> result = new ArrayList<>();
        if (value == null || !value.isJsonArray()) {
            return result;
        }
        for (JsonElement element : value.getAsJsonArray()) {
            if (element.isJsonPrimitive()) {
                result.add(element.getAsString());
            }
        }
        return result;
    }

    /** The immediate child keys of an object path, or of the document when the path is empty. */
    @NotNull
    public List<String> getKeys(@NotNull String path) {
        JsonElement value = path.isEmpty() ? root : find(path);
        List<String> result = new ArrayList<>();
        if (value != null && value.isJsonObject()) {
            result.addAll(value.getAsJsonObject().keySet());
        }
        return result;
    }

    public void setString(@NotNull String path, @Nullable String value) {
        plant(path, value == null ? null : new JsonPrimitive(value));
    }

    public void setNumber(@NotNull String path, @Nullable Number value) {
        plant(path, value == null ? null : new JsonPrimitive(value));
    }

    public void setBoolean(@NotNull String path, @Nullable Boolean value) {
        plant(path, value == null ? null : new JsonPrimitive(value));
    }

    public void setStrings(@NotNull String path, @Nullable List<String> values) {
        if (values == null) {
            plant(path, null);
            return;
        }
        JsonArray array = new JsonArray(values.size());
        values.forEach(array::add);
        plant(path, array);
    }

    /** Replace an object-valued path wholesale, from plain values. For a module's settings. */
    public void setObject(@NotNull String path, @Nullable Map<String, String> entries) {
        if (entries == null) {
            plant(path, null);
            return;
        }
        JsonObject object = new JsonObject();
        entries.forEach(object::addProperty);
        plant(path, object);
    }

    /** The values at an object path, as strings. Non-primitive members are skipped. */
    @NotNull
    public Map<String, String> getObject(@NotNull String path) {
        Map<String, String> result = new LinkedHashMap<>();
        JsonElement value = find(path);
        if (value == null || !value.isJsonObject()) {
            return result;
        }
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                result.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return result;
    }

    public void remove(@NotNull String path) {
        plant(path, null);
    }

    /**
     * The paths at which this document differs from {@code original}, for the save confirmation
     * and for the script preview.
     * <p>
     * Compares only the paths given, not the whole document. A definition read back from the
     * server differs from the one sent in ways nobody asked for -- defaults filled in, fields
     * reordered -- and listing those as changes would bury the ones a person made.
     */
    @NotNull
    public List<String> changedAmong(
        @NotNull WeaviateConfigDocument original, @NotNull List<String> paths
    ) {
        List<String> changed = new ArrayList<>();
        for (String path : paths) {
            JsonElement mine = find(path);
            JsonElement theirs = original.find(path);
            if (mine == null ? theirs != null : !mine.equals(theirs)) {
                changed.add(path);
            }
        }
        return changed;
    }

    @NotNull
    public String toJson() {
        return root.toString();
    }

    /** The value at a path rendered for display, or {@code "(unset)"}. */
    @NotNull
    public String render(@NotNull String path) {
        JsonElement value = find(path);
        if (value == null || value.isJsonNull()) {
            return "(unset)";
        }
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    @Nullable
    private JsonElement find(@NotNull String path) {
        JsonElement node = root;
        for (String step : path.split("\\.")) {
            if (node == null || !node.isJsonObject()) {
                return null;
            }
            node = node.getAsJsonObject().get(step);
        }
        return node;
    }

    /**
     * Write a value at a path, creating the objects on the way; a null value removes it.
     * <p>
     * Creating intermediate objects matters for the settings the server omits until they are set:
     * {@code objectTtlConfig} is absent from a definition entirely until something enables it, and
     * an editor that could only overwrite existing paths could never turn one on.
     */
    private void plant(@NotNull String path, @Nullable JsonElement value) {
        String[] steps = path.split("\\.");
        JsonObject node = root;
        for (int i = 0; i < steps.length - 1; i++) {
            JsonElement child = node.get(steps[i]);
            if (child == null || !child.isJsonObject()) {
                if (value == null) {
                    // Removing something whose parent does not exist: already absent.
                    return;
                }
                child = new JsonObject();
                node.add(steps[i], child);
            }
            node = child.getAsJsonObject();
        }
        String leaf = steps[steps.length - 1];
        if (value == null) {
            node.remove(leaf);
        } else {
            node.add(leaf, value);
        }
    }
}
