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
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WeaviateRowMapperTest extends DBeaverUnitTest {

    @Test
    public void uuidColumnIsTakenFromObject() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("name", "alice");
        WeaviateObject<Map<String, Object>> obj = WeaviateObject.of(b -> b
            .uuid("00000000-0000-0000-0000-000000000001")
            .properties(properties));
        Object[] row = WeaviateRowMapper.toRow(List.of(WeaviateColumns.UUID, "name"), obj);
        Assertions.assertEquals("00000000-0000-0000-0000-000000000001", row[0]);
        Assertions.assertEquals("alice", row[1]);
    }

    @Test
    public void missingPropertyMapsToNull() {
        WeaviateObject<Map<String, Object>> obj = WeaviateObject.of(b -> b
            .uuid("u")
            .properties(new HashMap<>()));
        Object[] row = WeaviateRowMapper.toRow(List.of(WeaviateColumns.UUID, "missing"), obj);
        Assertions.assertEquals("u", row[0]);
        Assertions.assertNull(row[1]);
    }

    @Test
    public void scoreAndDistanceComeFromQueryMetadata() {
        QueryMetadata meta = new QueryMetadata(0.42f, null, 0.81f, null);
        WeaviateObject<Map<String, Object>> obj = new WeaviateObject<>(
            "u", "MyCollection", null, new HashMap<>(), null, null, null, meta, null
        );
        Object[] row = WeaviateRowMapper.toRow(
            Arrays.asList(WeaviateColumns.UUID, WeaviateColumns.SCORE, WeaviateColumns.DISTANCE),
            obj
        );
        Assertions.assertEquals("u", row[0]);
        Assertions.assertEquals(0.81f, ((Number) row[1]).floatValue(), 0.0001);
        Assertions.assertEquals(0.42f, ((Number) row[2]).floatValue(), 0.0001);
    }

    @Test
    public void scoreAndDistanceAreNullWhenMetadataMissing() {
        WeaviateObject<Map<String, Object>> obj = WeaviateObject.of(b -> b
            .uuid("u")
            .properties(new HashMap<>()));
        Object[] row = WeaviateRowMapper.toRow(
            List.of(WeaviateColumns.UUID, WeaviateColumns.SCORE, WeaviateColumns.DISTANCE),
            obj
        );
        Assertions.assertNull(row[1]);
        Assertions.assertNull(row[2]);
    }

    @Test
    public void columnOrderIsPreserved() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("a", 1);
        properties.put("b", 2);
        WeaviateObject<Map<String, Object>> obj = WeaviateObject.of(b -> b
            .uuid("u")
            .properties(properties));
        Object[] row = WeaviateRowMapper.toRow(List.of("b", WeaviateColumns.UUID, "a"), obj);
        Assertions.assertEquals(2, row[0]);
        Assertions.assertEquals("u", row[1]);
        Assertions.assertEquals(1, row[2]);
    }

    @Test
    public void flatVectorRendersAsABracketedList() {
        WeaviateObject<Map<String, Object>> obj = WeaviateObject.of(b -> b
            .uuid("u")
            .properties(new HashMap<>())
            .vectors(io.weaviate.client6.v1.api.collections.Vectors.of("title", new float[]{0.1f, 0.2f})));
        Object[] row = WeaviateRowMapper.toRow(
            List.of(WeaviateColumns.VECTOR_PREFIX + "title"), obj);
        Assertions.assertEquals("[0.1, 0.2]", row[0]);
    }

    /**
     * A multi-vector embedding used to be bracketed twice -- once by the formatter and again by
     * the caller -- rendering as [[[0.1, 0.2]], [[0.3, 0.4]]]. It has to come out in the form a
     * Near Vector query accepts back, or a value copied from the grid cannot be pasted into one.
     */
    @Test
    public void multiVectorRendersInTheFormTheParserAccepts() {
        WeaviateObject<Map<String, Object>> obj = WeaviateObject.of(b -> b
            .uuid("u")
            .properties(new HashMap<>())
            .vectors(io.weaviate.client6.v1.api.collections.Vectors.of(
                "colbert", new float[][]{{0.1f, 0.2f}, {0.3f, 0.4f}})));
        Object[] row = WeaviateRowMapper.toRow(
            List.of(WeaviateColumns.VECTOR_PREFIX + "colbert"), obj);

        Assertions.assertEquals("[[0.1, 0.2], [0.3, 0.4]]", row[0]);
        Assertions.assertArrayEquals(
            new float[][]{{0.1f, 0.2f}, {0.3f, 0.4f}},
            WeaviateVectorParser.parseMulti((String) row[0]));
    }

    /**
     * Timestamps render as ISO-8601 rather than raw epoch millis: readable, and still sorts
     * correctly as text. Null when the query did not ask for them.
     */
    @Test
    public void timestampsRenderAsIso8601() {
        // The record constructor: the builder exposes no timestamp setters, since writes never
        // send them -- they are server-assigned.
        WeaviateObject<Map<String, Object>> obj = new WeaviateObject<>(
            "u", null, null, new HashMap<>(), null,
            1735689600000L, 1735776000000L, null, null);
        Object[] row = WeaviateRowMapper.toRow(
            List.of(WeaviateColumns.CREATED, WeaviateColumns.UPDATED), obj);
        Assertions.assertEquals("2025-01-01T00:00:00Z", row[0]);
        Assertions.assertEquals("2025-01-02T00:00:00Z", row[1]);
    }

    @Test
    public void timestampsAreNullWhenNotRequested() {
        WeaviateObject<Map<String, Object>> obj = WeaviateObject.of(b -> b
            .uuid("u")
            .properties(new HashMap<>()));
        Object[] row = WeaviateRowMapper.toRow(
            List.of(WeaviateColumns.CREATED, WeaviateColumns.UPDATED, WeaviateColumns.CERTAINTY), obj);
        Assertions.assertNull(row[0]);
        Assertions.assertNull(row[1]);
        Assertions.assertNull(row[2]);
    }

    @Test
    public void certaintyComesFromQueryMetadata() {
        WeaviateObject<Map<String, Object>> obj = new WeaviateObject<>(
            "u", null, null, new HashMap<>(), null, null, null,
            new QueryMetadata(0.25f, 0.875f, null, null), null);
        Object[] row = WeaviateRowMapper.toRow(List.of(WeaviateColumns.CERTAINTY), obj);
        Assertions.assertEquals(0.875f, row[0]);
    }

    /**
     * The rerank score reaches the row from outside the typed object -- the client never
     * unmarshals it -- so the mapper takes it as a separate argument.
     */
    @Test
    public void rerankScoreComesFromTheCapturedValue() {
        WeaviateObject<Map<String, Object>> obj = WeaviateObject.of(b -> b
            .uuid("u")
            .properties(new HashMap<>()));
        Object[] row = WeaviateRowMapper.toRow(
            List.of(WeaviateColumns.UUID, WeaviateColumns.RERANK_SCORE), obj, null, 0.781f);
        Assertions.assertEquals("u", row[0]);
        Assertions.assertEquals(0.781f, row[1]);
    }

    /**
     * Zero is a real rerank score, not "absent" -- the reply has a separate present flag for
     * exactly that reason, so a captured 0 must survive to the grid.
     */
    @Test
    public void zeroRerankScoreIsKept() {
        WeaviateObject<Map<String, Object>> obj = WeaviateObject.of(b -> b
            .uuid("u")
            .properties(new HashMap<>()));
        Object[] row = WeaviateRowMapper.toRow(
            List.of(WeaviateColumns.RERANK_SCORE), obj, null, 0f);
        Assertions.assertEquals(0f, row[0]);
    }

    @Test
    public void rerankScoreIsNullWhenNotReranked() {
        WeaviateObject<Map<String, Object>> obj = WeaviateObject.of(b -> b
            .uuid("u")
            .properties(new HashMap<>()));
        Object[] row = WeaviateRowMapper.toRow(List.of(WeaviateColumns.RERANK_SCORE), obj);
        Assertions.assertNull(row[0]);
    }
}
