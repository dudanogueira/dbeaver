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

import io.weaviate.client6.v1.api.collections.Vectors;
import io.weaviate.client6.v1.api.collections.WeaviateObject;
import io.weaviate.client6.v1.api.collections.query.QueryMetadata;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.List;
import java.util.Map;

public final class WeaviateRowMapper {

    private WeaviateRowMapper() {
    }

    /**
     * Build a row whose ordinals match {@code columns}. Column names are matched
     * against well-known synthetic columns (uuid/score/distance/vector) first, then the
     * Weaviate object's properties map.
     */
    @NotNull
    public static Object[] toRow(
        @NotNull List<String> columns,
        @NotNull WeaviateObject<Map<String, Object>> obj
    ) {
        return toRow(columns, obj, null);
    }

    /**
     * @param defaultVectorName named vector rendered in the plain {@code _vector} column, or
     *                          {@code null} when the collection declares no vectors
     */
    @NotNull
    public static Object[] toRow(
        @NotNull List<String> columns,
        @NotNull WeaviateObject<Map<String, Object>> obj,
        @Nullable String defaultVectorName
    ) {
        Object[] row = new Object[columns.size()];
        Map<String, Object> properties = obj.properties();
        QueryMetadata meta = obj.queryMetadata();
        for (int i = 0; i < columns.size(); i++) {
            row[i] = readColumn(columns.get(i), obj, properties, meta, defaultVectorName);
        }
        return row;
    }

    @Nullable
    private static Object readColumn(
        @NotNull String column,
        @NotNull WeaviateObject<Map<String, Object>> obj,
        @Nullable Map<String, Object> properties,
        @Nullable QueryMetadata meta,
        @Nullable String defaultVectorName
    ) {
        switch (column) {
            case WeaviateColumns.UUID:
                return obj.uuid();
            case WeaviateColumns.SCORE:
                return meta == null ? null : meta.score();
            case WeaviateColumns.DISTANCE:
                return meta == null ? null : meta.distance();
            case WeaviateColumns.EXPLAIN_SCORE:
                return meta == null ? null : meta.explainScore();
            default:
                String vectorName = WeaviateColumns.vectorNameOf(column, defaultVectorName);
                if (vectorName != null) {
                    return readVector(obj, vectorName);
                }
                return properties == null ? null : properties.get(column);
        }
    }

    /**
     * Render a named embedding as text. Vectors are shown rather than returned as {@code float[]}
     * because the result grid has no renderer for a primitive array, and a formatted string is
     * what a user would copy out anyway.
     * <p>
     * Returns {@code null} when the query did not request vectors, so an un-requested vector is
     * visibly empty instead of misreported as a zero-length one.
     */
    @Nullable
    private static String readVector(@NotNull WeaviateObject<Map<String, Object>> obj, @NotNull String vectorName) {
        Vectors vectors = obj.vectors();
        if (vectors == null || !vectors.contains(vectorName)) {
            return null;
        }
        float[] single = vectors.getSingle(vectorName);
        if (single != null) {
            return WeaviateVectorParser.format(single);
        }
        float[][] multi = vectors.getMulti(vectorName);
        if (multi == null) {
            return null;
        }
        // Multi-vector (ColBERT-style) embeddings: one bracketed vector per token.
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < multi.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append('[').append(WeaviateVectorParser.format(multi[i])).append(']');
        }
        return sb.append(']').toString();
    }
}
