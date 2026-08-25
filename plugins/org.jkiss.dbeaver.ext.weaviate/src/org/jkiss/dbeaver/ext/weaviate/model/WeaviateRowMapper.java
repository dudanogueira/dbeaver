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
import io.weaviate.client6.v1.api.collections.generate.GenerativeObject;
import io.weaviate.client6.v1.api.collections.generate.TaskOutput;
import io.weaviate.client6.v1.api.collections.generative.ProviderMetadata;
import io.weaviate.client6.v1.api.collections.query.QueryMetadata;
import io.weaviate.client6.v1.api.collections.query.QueryObjectGrouped;
import io.weaviate.client6.v1.api.collections.generate.GenerativeResponseGroup;
import io.weaviate.client6.v1.api.collections.query.QueryResponseGroup;
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
        return toRow(columns, obj, defaultVectorName, null);
    }

    /**
     * @param rerankScore score for this object, read off the reply by the model rather than by
     *                    the client, or null when the search was not reranked
     */
    @NotNull
    public static Object[] toRow(
        @NotNull List<String> columns,
        @NotNull WeaviateObject<Map<String, Object>> obj,
        @Nullable String defaultVectorName,
        @Nullable Float rerankScore
    ) {
        return toRow(columns, RowSource.of(obj, rerankScore), defaultVectorName);
    }

    /**
     * The generative twin of the object overload. {@code GenerativeObject} is not a
     * {@code WeaviateObject} -- it adds the generated output but has no timestamps -- so the two
     * meet in {@link RowSource} rather than one adapting to the other.
     */
    @NotNull
    public static Object[] toRow(
        @NotNull List<String> columns,
        @NotNull GenerativeObject<Map<String, Object>> obj,
        @Nullable String defaultVectorName
    ) {
        return toRow(columns, obj, defaultVectorName, null);
    }

    /**
     * @param rerankScore score for this object, read off the reply by the model rather than by
     *                    the client, or null when the search was not reranked
     */
    @NotNull
    public static Object[] toRow(
        @NotNull List<String> columns,
        @NotNull GenerativeObject<Map<String, Object>> obj,
        @Nullable String defaultVectorName,
        @Nullable Float rerankScore
    ) {
        return toRow(columns, RowSource.of(obj, rerankScore), defaultVectorName);
    }

    /**
     * The grouped twin. {@code QueryObjectGrouped} is a third shape again -- it knows which group
     * it landed in but, like {@code GenerativeObject}, carries no timestamps.
     *
     * @param stats       the row's own group statistics, or null when the reply did not report a
     *                    group for it
     * @param generated   text generated for the row's group, or null. On a grouped generative
     *                    search the output belongs to the group, not the object
     * @param rerankScore score for this object, read off the reply by the model rather than by
     *                    the client, or null when the search was not reranked
     */
    @NotNull
    public static Object[] toRow(
        @NotNull List<String> columns,
        @NotNull QueryObjectGrouped<Map<String, Object>> obj,
        @Nullable String defaultVectorName,
        @Nullable GroupStats stats,
        @Nullable TaskOutput generated,
        @Nullable Float rerankScore
    ) {
        return toRow(columns, RowSource.of(obj, stats, generated, rerankScore), defaultVectorName);
    }

    /**
     * The per-group numbers, lifted off whichever group record the reply carried.
     * <p>
     * The plain and generative replies use two unrelated types -- {@code QueryResponseGroup} and
     * {@code GenerativeResponseGroup} -- with identical accessors and no common supertype. Rather
     * than give the mapper an overload per reply shape, both narrow to this on the way in.
     */
    public record GroupStats(
        @Nullable Long count,
        @Nullable Float minDistance,
        @Nullable Float maxDistance
    ) {
        @Nullable
        public static GroupStats of(@Nullable QueryResponseGroup<Map<String, Object>> group) {
            return group == null ? null
                : new GroupStats(group.numberOfObjects(), group.minDistance(), group.maxDistance());
        }

        @Nullable
        public static GroupStats of(@Nullable GenerativeResponseGroup<Map<String, Object>> group) {
            return group == null ? null
                : new GroupStats(group.numberOfObjects(), group.minDistance(), group.maxDistance());
        }
    }

    @NotNull
    private static Object[] toRow(
        @NotNull List<String> columns,
        @NotNull RowSource source,
        @Nullable String defaultVectorName
    ) {
        Object[] row = new Object[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            row[i] = readColumn(columns.get(i), source, defaultVectorName);
        }
        return row;
    }

    /**
     * Everything a row can be built from, regardless of which response type delivered it.
     * Timestamps are null on the generative path -- the client's GenerativeObject simply does
     * not carry them -- and the generated output is null on the plain one.
     */
    private record RowSource(
        @Nullable String uuid,
        @Nullable Map<String, Object> properties,
        @Nullable Vectors vectors,
        @Nullable QueryMetadata meta,
        @Nullable Long createdAt,
        @Nullable Long updatedAt,
        @Nullable TaskOutput generated,
        @Nullable Float rerankScore,
        @Nullable String group,
        @Nullable Long groupCount,
        @Nullable Float groupMinDistance,
        @Nullable Float groupMaxDistance
    ) {
        static RowSource of(@NotNull WeaviateObject<Map<String, Object>> obj, @Nullable Float rerankScore) {
            return new RowSource(obj.uuid(), obj.properties(), obj.vectors(), obj.queryMetadata(),
                obj.createdAt(), obj.lastUpdatedAt(), null, rerankScore,
                null, null, null, null);
        }

        static RowSource of(@NotNull GenerativeObject<Map<String, Object>> obj, @Nullable Float rerankScore) {
            return new RowSource(obj.uuid(), obj.properties(), obj.vectors(), obj.metadata(),
                null, null, obj.generative(), rerankScore,
                null, null, null, null);
        }

        static RowSource of(
            @NotNull QueryObjectGrouped<Map<String, Object>> obj,
            @Nullable GroupStats stats,
            @Nullable TaskOutput generated,
            @Nullable Float rerankScore
        ) {
            // The group name comes off the object rather than the stats: an object knows its own
            // bucket even when the caller could not resolve the group record to go with it.
            return new RowSource(obj.uuid(), obj.properties(), obj.vectors(), obj.metadata(),
                null, null, generated, rerankScore,
                obj.belongsToGroup(),
                stats == null ? null : stats.count(),
                stats == null ? null : stats.minDistance(),
                stats == null ? null : stats.maxDistance());
        }
    }

    @Nullable
    private static Object readColumn(
        @NotNull String column,
        @NotNull RowSource source,
        @Nullable String defaultVectorName
    ) {
        QueryMetadata meta = source.meta();
        switch (column) {
            case WeaviateColumns.UUID:
                return source.uuid();
            case WeaviateColumns.SCORE:
                return meta == null ? null : meta.score();
            case WeaviateColumns.DISTANCE:
                return meta == null ? null : meta.distance();
            case WeaviateColumns.EXPLAIN_SCORE:
                return meta == null ? null : meta.explainScore();
            case WeaviateColumns.CERTAINTY:
                return meta == null ? null : meta.certainty();
            case WeaviateColumns.CREATED:
                return formatTimestamp(source.createdAt());
            case WeaviateColumns.UPDATED:
                return formatTimestamp(source.updatedAt());
            case WeaviateColumns.RERANK_SCORE:
                return source.rerankScore();
            case WeaviateColumns.GROUP:
                return source.group();
            case WeaviateColumns.GROUP_COUNT:
                return source.groupCount();
            case WeaviateColumns.GROUP_MIN_DISTANCE:
                return source.groupMinDistance();
            case WeaviateColumns.GROUP_MAX_DISTANCE:
                return source.groupMaxDistance();
            case WeaviateColumns.GENERATED:
                return source.generated() == null ? null : source.generated().text();
            case WeaviateColumns.GENERATIVE_META:
                return source.generated() == null ? null
                    : formatGenerativeMeta(source.generated().metadata());
            default:
                String vectorName = WeaviateColumns.vectorNameOf(column, defaultVectorName);
                if (vectorName != null) {
                    return readVector(source.vectors(), vectorName);
                }
                return source.properties() == null ? null : source.properties().get(column);
        }
    }

    /**
     * Provider usage as text. The client types this as a marker interface with one concrete
     * record per provider, and even their usage sub-records share no common type -- so rather
     * than 14 instanceof branches, the record's own toString is used: for every provider it
     * reads as Metadata[usage=Usage[promptTokens=.., completionTokens=.., totalTokens=..]].
     */
    @Nullable
    static String formatGenerativeMeta(@Nullable ProviderMetadata metadata) {
        return metadata == null ? null : metadata.toString();
    }

    /**
     * Epoch milliseconds as ISO-8601 UTC, or null when the query did not ask for the timestamp.
     * A string rather than a raw long: the number is unreadable, and ISO-8601 still sorts
     * correctly as text.
     */
    @Nullable
    private static String formatTimestamp(@Nullable Long epochMillis) {
        if (epochMillis == null) {
            return null;
        }
        return java.time.Instant.ofEpochMilli(epochMillis).toString();
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
    private static String readVector(@Nullable Vectors vectors, @NotNull String vectorName) {
        if (vectors == null || !vectors.contains(vectorName)) {
            return null;
        }
        // Read the raw value and branch on what it actually is. getSingle is a bare cast to
        // float[], so on a multi-vector it throws ClassCastException rather than returning null
        // -- asking it first made the multi-vector branch below unreachable and turned a ColBERT
        // column into a failed read of the whole row.
        Object value = vectors.asMap().get(vectorName);
        if (value instanceof float[] single) {
            return WeaviateVectorParser.format(single);
        }
        if (!(value instanceof float[][] multi)) {
            return null;
        }
        // Multi-vector (ColBERT-style) embeddings: one bracketed vector per token. Formatted by
        // the parser so what is shown is what a Near Vector query accepts back.
        return WeaviateVectorParser.formatMulti(multi);
    }
}
