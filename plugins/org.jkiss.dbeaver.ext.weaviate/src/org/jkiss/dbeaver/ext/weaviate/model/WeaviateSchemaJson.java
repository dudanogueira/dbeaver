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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import io.weaviate.client6.v1.api.collections.CollectionConfig;
import io.weaviate.client6.v1.internal.json.JSON;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

/**
 * Converts a Weaviate collection definition to and from its REST schema JSON.
 * <p>
 * This is the only place shaded {@code io.weaviate.*} types are touched for JSON purposes: the UI
 * bundle cannot see them (its classloader has no visibility into this bundle's {@code Bundle-ClassPath}),
 * so everything crossing the bundle boundary is a plain {@link String}.
 * <p>
 * The wire format is the same one Weaviate's {@code /v1/schema} endpoint accepts - {@code class},
 * {@code properties[].dataType[]}, {@code vectorConfig}, and so on. {@link CollectionConfig} carries
 * Gson {@code @SerializedName} annotations mapping its record components onto exactly those names
 * ({@code collectionName} to {@code class}, {@code vectors} to {@code vectorConfig}, ...), which is
 * why definitions produced by the <a href="https://weaviate.github.io/weaviate-add-collection/">
 * weaviate-add-collection</a> tool can be imported without any translation layer.
 */
public final class WeaviateSchemaJson {

    /** Gson used only to pretty-print; parsing and generation both go through the client's own {@link JSON}. */
    private static final Gson PRETTY = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private static final String FIELD_CLASS = "class";

    private WeaviateSchemaJson() {
    }

    /**
     * Serialize a collection definition to indented REST schema JSON.
     */
    @NotNull
    public static String toJson(@NotNull CollectionConfig config) {
        // Round-trip through the client's serializer so the output matches the wire format
        // exactly, then re-render it indented for readability.
        JsonElement element = JSON.toJsonElement(JSON.serialize(config));
        return PRETTY.toJson(element);
    }

    /**
     * Parse REST schema JSON into the client's object model.
     * <p>
     * Only for reading definitions the client itself produced. Creation does <em>not</em> go
     * through here - see {@link WeaviateSchemaRest} - because this conversion is lossy and cannot
     * represent every configuration Weaviate accepts.
     *
     * @throws DBException if the text is not valid JSON, is not a JSON object, has no usable
     *                     {@code class}, or describes something the client cannot model
     */
    @NotNull
    public static CollectionConfig fromJson(@Nullable String json) throws DBException {
        parseDocument(json);
        CollectionConfig config;
        try {
            config = JSON.deserialize(json, CollectionConfig.class);
        } catch (RuntimeException e) {
            // Deliberately broad. The client's Gson adapters throw NullPointerException (among
            // others) on definitions they cannot map, and letting that escape surfaces as a bare
            // "Internal error (NPE)" with nothing the user can act on.
            throw new DBException("Cannot read collection definition: "
                + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : " - " + e.getMessage()), e);
        }
        if (config == null) {
            throw new DBException("Cannot read collection definition");
        }
        return config;
    }


    /**
     * Check that {@code json} is a definition this plugin can create, without exposing the parsed
     * result.
     * <p>
     * Exists so the UI bundle can validate before closing its dialog: its classloader cannot see
     * the shaded {@code io.weaviate.*} classes, so it must not call {@link #fromJson} (whose return
     * type is one of them). Keeping the signature free of shaded types is what makes it callable.
     *
     * @throws DBException with a user-facing explanation of what is wrong
     */
    public static void validate(@Nullable String json) throws DBException {
        parseDocument(json);
    }

    /**
     * Check that {@code json} is a JSON object naming a collection, and return it parsed.
     * <p>
     * This is the whole of client-side validation. The definition is <em>not</em> interpreted
     * against the client's object model, because anything it cannot represent would then be
     * impossible to create even when the server accepts it. Whether the configuration is
     * meaningful is the server's call - see {@link WeaviateSchemaRest}.
     */
    @NotNull
    static JsonObject parseDocument(@Nullable String json) throws DBException {
        if (CommonUtils.isEmptyTrimmed(json)) {
            throw new DBException("Collection definition is empty");
        }
        JsonElement element;
        try {
            element = JSON.toJsonElement(json);
        } catch (JsonParseException | IllegalStateException e) {
            throw new DBException("Collection definition is not valid JSON: " + e.getMessage(), e);
        }
        if (element == null || !element.isJsonObject()) {
            throw new DBException("Collection definition must be a JSON object");
        }
        JsonObject object = element.getAsJsonObject();
        // The name is the one field this plugin genuinely needs: it identifies the navigator node
        // and the collection to read back after creation.
        if (CommonUtils.isEmptyTrimmed(asStringOrNull(object.get(FIELD_CLASS)))) {
            throw new DBException(
                "Collection definition must contain a non-empty \"" + FIELD_CLASS + "\" (the collection name)");
        }
        return object;
    }

    /**
     * A minimal, valid starter definition for a new collection, used to seed the create dialog.
     * Kept deliberately small: a single text property and no vectorizer, so it succeeds against
     * any server without requiring module credentials.
     */
    @NotNull
    public static String newTemplate(@NotNull String collectionName) {
        JsonObject property = new JsonObject();
        property.addProperty("name", "title");
        JsonArray dataType = new JsonArray();
        dataType.add("text");
        property.add("dataType", dataType);

        JsonArray properties = new JsonArray();
        properties.add(property);

        JsonObject root = new JsonObject();
        root.addProperty(FIELD_CLASS, collectionName);
        root.addProperty("description", "");
        root.add("properties", properties);
        return PRETTY.toJson(root);
    }

    /**
     * Return {@code json} with its collection name replaced. Used when duplicating a collection,
     * where reusing the source name would collide on the server.
     *
     * @return the rewritten document, or the input unchanged if it is not a JSON object
     */
    @NotNull
    public static String withCollectionName(@NotNull String json, @NotNull String collectionName) {
        try {
            JsonElement element = JSON.toJsonElement(json);
            if (element == null || !element.isJsonObject()) {
                return json;
            }
            JsonObject object = element.getAsJsonObject();
            object.addProperty(FIELD_CLASS, collectionName);
            return PRETTY.toJson(object);
        } catch (JsonParseException | IllegalStateException e) {
            return json;
        }
    }

    /**
     * Collection name declared by a schema document, or {@code null} if absent/unreadable.
     * Used to pre-fill UI without committing to a full parse.
     */
    @Nullable
    public static String readCollectionName(@Nullable String json) {
        if (CommonUtils.isEmptyTrimmed(json)) {
            return null;
        }
        try {
            JsonElement element = JSON.toJsonElement(json);
            if (element == null || !element.isJsonObject()) {
                return null;
            }
            return CommonUtils.nullIfEmpty(asStringOrNull(element.getAsJsonObject().get(FIELD_CLASS)));
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    @Nullable
    private static String asStringOrNull(@Nullable JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }
}
