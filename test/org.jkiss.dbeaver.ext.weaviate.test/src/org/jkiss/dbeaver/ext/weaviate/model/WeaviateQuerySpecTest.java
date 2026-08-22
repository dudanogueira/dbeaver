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

import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class WeaviateQuerySpecTest extends DBeaverUnitTest {

    @Test
    public void fetchSpecHasFetchMode() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.fetch();
        Assertions.assertEquals(WeaviateQueryMode.FETCH, spec.getMode());
        Assertions.assertFalse(spec.rankedResults());
        Assertions.assertNull(spec.getQuery());
        Assertions.assertNull(spec.getVector());
        Assertions.assertNull(spec.getAlpha());
    }

    @Test
    public void bm25SpecCarriesQuery() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.bm25("hello");
        Assertions.assertEquals(WeaviateQueryMode.BM25, spec.getMode());
        Assertions.assertTrue(spec.rankedResults());
        Assertions.assertEquals("hello", spec.getQuery());
    }

    @Test
    public void nearTextSpecCarriesQuery() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.nearText("dog");
        Assertions.assertEquals(WeaviateQueryMode.NEAR_TEXT, spec.getMode());
        Assertions.assertTrue(spec.rankedResults());
        Assertions.assertEquals("dog", spec.getQuery());
    }

    @Test
    public void nearVectorSpecCarriesVector() {
        float[] v = {0.1f, 0.2f};
        WeaviateQuerySpec spec = WeaviateQuerySpec.nearVector(v);
        Assertions.assertEquals(WeaviateQueryMode.NEAR_VECTOR, spec.getMode());
        Assertions.assertTrue(spec.rankedResults());
        Assertions.assertArrayEquals(v, spec.getVector(), 0.0001f);
    }

    @Test
    public void hybridSpecCarriesQueryAndAlpha() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.hybrid("cat", 0.7f);
        Assertions.assertEquals(WeaviateQueryMode.HYBRID, spec.getMode());
        Assertions.assertTrue(spec.rankedResults());
        Assertions.assertEquals("cat", spec.getQuery());
        Assertions.assertNotNull(spec.getAlpha());
        Assertions.assertEquals(0.7f, spec.getAlpha(), 0.0001f);
    }

    @Test
    public void rankedResultsFalseOnlyForFetch() {
        Assertions.assertFalse(WeaviateQuerySpec.fetch().rankedResults());
        Assertions.assertTrue(WeaviateQuerySpec.bm25("x").rankedResults());
        Assertions.assertTrue(WeaviateQuerySpec.nearText("x").rankedResults());
        Assertions.assertTrue(WeaviateQuerySpec.nearVector(new float[]{1.0f}).rankedResults());
        Assertions.assertTrue(WeaviateQuerySpec.hybrid("x", 0.5f).rankedResults());
    }

    @Test
    public void factoryDefaultsHaveEmptyOptionalFields() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.bm25("hello");
        Assertions.assertTrue(spec.getQueryProperties().isEmpty());
        Assertions.assertNull(spec.getDistance());
        Assertions.assertNull(spec.getFusionType());
    }

    @Test
    public void builderCarriesQueryProperties() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.BM25)
            .query("dog")
            .queryProperties(List.of("name", "summary"))
            .build();
        Assertions.assertEquals(List.of("name", "summary"), spec.getQueryProperties());
    }

    @Test
    public void queryPropertiesAreImmutable() {
        java.util.List<String> mutable = new java.util.ArrayList<>();
        mutable.add("a");
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.BM25)
            .query("q")
            .queryProperties(mutable)
            .build();
        mutable.add("b");
        Assertions.assertEquals(List.of("a"), spec.getQueryProperties());
        Assertions.assertThrows(UnsupportedOperationException.class, () -> spec.getQueryProperties().add("c"));
    }

    @Test
    public void builderCarriesDistance() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.NEAR_TEXT)
            .query("dog")
            .distance(0.7f)
            .build();
        Assertions.assertNotNull(spec.getDistance());
        Assertions.assertEquals(0.7f, spec.getDistance(), 0.0001f);
    }

    @Test
    public void builderCarriesFusionType() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.HYBRID)
            .query("dog")
            .alpha(0.5f)
            .fusionType(WeaviateHybridFusion.RELATIVE_SCORE)
            .build();
        Assertions.assertEquals(WeaviateHybridFusion.RELATIVE_SCORE, spec.getFusionType());
    }

    @Test
    public void hybridFusionWrapperMapsToClientType() {
        Assertions.assertNotNull(WeaviateHybridFusion.RANKED.toClientType());
        Assertions.assertNotNull(WeaviateHybridFusion.RELATIVE_SCORE.toClientType());
    }

    @Test
    public void nullQueryPropertiesBecomeEmptyList() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.BM25)
            .query("q")
            .queryProperties(null)
            .build();
        Assertions.assertTrue(spec.getQueryProperties().isEmpty());
    }

    @Test
    public void nearObjectCarriesTheReferenceUuid() {
        String uuid = "6f2c1e4a-1111-4222-8333-444455556666";
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.NEAR_OBJECT)
            .objectId(uuid)
            .distance(0.25f)
            .build();
        Assertions.assertEquals(uuid, spec.getObjectId());
        Assertions.assertNull(spec.getVector(), "Near Object must not carry a vector");
        Assertions.assertNull(spec.getQuery(), "Near Object takes an id, not a query string");
        Assertions.assertEquals(0.25f, spec.getDistance(), 0.0001f);
    }

}
