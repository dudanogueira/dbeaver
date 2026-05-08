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
     * against well-known synthetic columns (uuid/score/distance) first, then the
     * Weaviate object's properties map.
     */
    @NotNull
    public static Object[] toRow(
        @NotNull List<String> columns,
        @NotNull WeaviateObject<Map<String, Object>> obj
    ) {
        Object[] row = new Object[columns.size()];
        Map<String, Object> properties = obj.properties();
        QueryMetadata meta = obj.queryMetadata();
        for (int i = 0; i < columns.size(); i++) {
            row[i] = readColumn(columns.get(i), obj, properties, meta);
        }
        return row;
    }

    @Nullable
    private static Object readColumn(
        @NotNull String column,
        @NotNull WeaviateObject<Map<String, Object>> obj,
        @Nullable Map<String, Object> properties,
        @Nullable QueryMetadata meta
    ) {
        switch (column) {
            case WeaviateColumns.UUID:
                return obj.uuid();
            case WeaviateColumns.SCORE:
                return meta == null ? null : meta.score();
            case WeaviateColumns.DISTANCE:
                return meta == null ? null : meta.distance();
            default:
                return properties == null ? null : properties.get(column);
        }
    }
}
