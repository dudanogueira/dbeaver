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
import org.junit.Assert;
import org.junit.Test;

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
        Assert.assertEquals("00000000-0000-0000-0000-000000000001", row[0]);
        Assert.assertEquals("alice", row[1]);
    }

    @Test
    public void missingPropertyMapsToNull() {
        WeaviateObject<Map<String, Object>> obj = WeaviateObject.of(b -> b
            .uuid("u")
            .properties(new HashMap<>()));
        Object[] row = WeaviateRowMapper.toRow(List.of(WeaviateColumns.UUID, "missing"), obj);
        Assert.assertEquals("u", row[0]);
        Assert.assertNull(row[1]);
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
        Assert.assertEquals("u", row[0]);
        Assert.assertEquals(0.81f, ((Number) row[1]).floatValue(), 0.0001);
        Assert.assertEquals(0.42f, ((Number) row[2]).floatValue(), 0.0001);
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
        Assert.assertNull(row[1]);
        Assert.assertNull(row[2]);
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
        Assert.assertEquals(2, row[0]);
        Assert.assertEquals("u", row[1]);
        Assert.assertEquals(1, row[2]);
    }
}
