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
import com.google.gson.JsonPrimitive;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.meta.Association;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One entry of a JSON document, rendered as a navigator node.
 * <p>
 * Exists so the tree can show a collection's definition in full. The typed folders alongside it
 * (Properties, Vectorizers, Replication, ...) are built by reflecting over the client's
 * {@code CollectionConfig}, so they can only ever surface what that model represents - anything the
 * bundled client does not know about, including settings from a newer server, is invisible there.
 * These nodes are built straight from the server's JSON instead, so nothing is left out.
 * <p>
 * Objects and arrays become expandable nodes; everything else is a leaf labelled {@code key: value}.
 */
public class WeaviateJsonNode implements DBSObject {

    private static final int MAX_INLINE_VALUE = 120;

    private final DBSObject parent;
    private final DBPDataSource dataSource;
    private final String key;
    private final JsonElement value;
    private volatile List<WeaviateJsonNode> children;

    public WeaviateJsonNode(
        @NotNull DBSObject parent,
        @NotNull DBPDataSource dataSource,
        @NotNull String key,
        @NotNull JsonElement value
    ) {
        this.parent = parent;
        this.dataSource = dataSource;
        this.key = key;
        this.value = value;
    }

    /**
     * Top-level entries of {@code document}, sorted so the tree order is stable between refreshes.
     */
    @NotNull
    public static List<WeaviateJsonNode> fromObject(
        @NotNull DBSObject parent,
        @NotNull DBPDataSource dataSource,
        @NotNull JsonObject document
    ) {
        List<WeaviateJsonNode> result = new ArrayList<>(document.size());
        List<String> keys = new ArrayList<>(document.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            JsonElement child = document.get(key);
            if (child != null) {
                result.add(new WeaviateJsonNode(parent, dataSource, key, child));
            }
        }
        return result;
    }

    /**
     * Label: the bare key for containers, {@code key: value} for scalars, so a leaf reads as a
     * complete fact without having to open a properties view.
     */
    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        if (value.isJsonObject() || value.isJsonArray()) {
            return key;
        }
        return key + ": " + formatScalar(value);
    }

    /** The key on its own, for the properties view. */
    @NotNull
    @Property(viewable = true, order = 2)
    public String getKey() {
        return key;
    }

    /**
     * The value as text. Containers are summarised by size rather than dumped, since their contents
     * are reachable as child nodes.
     */
    @NotNull
    @Property(viewable = true, order = 3)
    public String getValue() {
        if (value.isJsonObject()) {
            return "{" + value.getAsJsonObject().size() + " entries}";
        }
        if (value.isJsonArray()) {
            return "[" + value.getAsJsonArray().size() + " items]";
        }
        return formatScalar(value);
    }

    /** JSON type name, so the properties view distinguishes "true" the boolean from "true" the string. */
    @NotNull
    @Property(viewable = true, order = 4)
    public String getType() {
        if (value.isJsonObject()) {
            return "object";
        }
        if (value.isJsonArray()) {
            return "array";
        }
        if (value.isJsonNull()) {
            return "null";
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return "boolean";
        }
        if (primitive.isNumber()) {
            return "number";
        }
        return "string";
    }

    @Association
    @NotNull
    public List<WeaviateJsonNode> getChildren(@NotNull DBRProgressMonitor monitor) {
        if (children == null) {
            synchronized (this) {
                if (children == null) {
                    children = loadChildren();
                }
            }
        }
        return children;
    }

    @NotNull
    private List<WeaviateJsonNode> loadChildren() {
        if (value.isJsonObject()) {
            List<WeaviateJsonNode> result = new ArrayList<>();
            List<String> keys = new ArrayList<>(value.getAsJsonObject().keySet());
            Collections.sort(keys);
            for (String childKey : keys) {
                JsonElement child = value.getAsJsonObject().get(childKey);
                if (child != null) {
                    result.add(new WeaviateJsonNode(this, dataSource, childKey, child));
                }
            }
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            List<WeaviateJsonNode> result = new ArrayList<>(array.size());
            for (int i = 0; i < array.size(); i++) {
                JsonElement item = array.get(i);
                if (item != null) {
                    // Name array entries by the field that identifies them where there is one -
                    // "title" reads better than "[0]" for a property list.
                    result.add(new WeaviateJsonNode(this, dataSource, arrayEntryLabel(item, i), item));
                }
            }
            return result;
        }
        return Collections.emptyList();
    }

    @NotNull
    private static String arrayEntryLabel(@NotNull JsonElement item, int index) {
        if (item.isJsonObject()) {
            for (String candidate : new String[]{"name", "class", "id"}) {
                JsonElement label = item.getAsJsonObject().get(candidate);
                if (label != null && label.isJsonPrimitive()) {
                    return label.getAsString();
                }
            }
        }
        return "[" + index + "]";
    }

    @NotNull
    private static String formatScalar(@NotNull JsonElement element) {
        if (element.isJsonNull()) {
            return "null";
        }
        String text = element.isJsonPrimitive() ? element.getAsString() : element.toString();
        if (text.length() > MAX_INLINE_VALUE) {
            return text.substring(0, MAX_INLINE_VALUE) + "...";
        }
        return text;
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
    }

    @Override
    public DBSObject getParentObject() {
        return parent;
    }

    @NotNull
    @Override
    public DBPDataSource getDataSource() {
        return dataSource;
    }

    @Override
    public boolean isPersisted() {
        return true;
    }

    /** Raw JSON of this entry, for copying out of the properties view. */
    @NotNull
    public String toJson() {
        return value.toString();
    }

    @NotNull
    Map.Entry<String, JsonElement> asEntry() {
        return Map.entry(key, value);
    }
}
