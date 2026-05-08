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
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class WeaviateQuerySpecTest extends DBeaverUnitTest {

    @Test
    public void fetchSpecHasFetchMode() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.fetch();
        Assert.assertEquals(WeaviateQueryMode.FETCH, spec.getMode());
        Assert.assertFalse(spec.rankedResults());
        Assert.assertNull(spec.getQuery());
        Assert.assertNull(spec.getVector());
        Assert.assertNull(spec.getAlpha());
    }

    @Test
    public void bm25SpecCarriesQuery() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.bm25("hello");
        Assert.assertEquals(WeaviateQueryMode.BM25, spec.getMode());
        Assert.assertTrue(spec.rankedResults());
        Assert.assertEquals("hello", spec.getQuery());
    }

    @Test
    public void nearTextSpecCarriesQuery() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.nearText("dog");
        Assert.assertEquals(WeaviateQueryMode.NEAR_TEXT, spec.getMode());
        Assert.assertTrue(spec.rankedResults());
        Assert.assertEquals("dog", spec.getQuery());
    }

    @Test
    public void nearVectorSpecCarriesVector() {
        float[] v = {0.1f, 0.2f};
        WeaviateQuerySpec spec = WeaviateQuerySpec.nearVector(v);
        Assert.assertEquals(WeaviateQueryMode.NEAR_VECTOR, spec.getMode());
        Assert.assertTrue(spec.rankedResults());
        Assert.assertArrayEquals(v, spec.getVector(), 0.0001f);
    }

    @Test
    public void hybridSpecCarriesQueryAndAlpha() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.hybrid("cat", 0.7f);
        Assert.assertEquals(WeaviateQueryMode.HYBRID, spec.getMode());
        Assert.assertTrue(spec.rankedResults());
        Assert.assertEquals("cat", spec.getQuery());
        Assert.assertNotNull(spec.getAlpha());
        Assert.assertEquals(0.7f, spec.getAlpha(), 0.0001f);
    }

    @Test
    public void rankedResultsFalseOnlyForFetch() {
        Assert.assertFalse(WeaviateQuerySpec.fetch().rankedResults());
        Assert.assertTrue(WeaviateQuerySpec.bm25("x").rankedResults());
        Assert.assertTrue(WeaviateQuerySpec.nearText("x").rankedResults());
        Assert.assertTrue(WeaviateQuerySpec.nearVector(new float[]{1.0f}).rankedResults());
        Assert.assertTrue(WeaviateQuerySpec.hybrid("x", 0.5f).rankedResults());
    }

    @Test
    public void factoryDefaultsHaveEmptyOptionalFields() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.bm25("hello");
        Assert.assertTrue(spec.getQueryProperties().isEmpty());
        Assert.assertNull(spec.getDistance());
        Assert.assertNull(spec.getFusionType());
    }

    @Test
    public void builderCarriesQueryProperties() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.BM25)
            .query("dog")
            .queryProperties(List.of("name", "summary"))
            .build();
        Assert.assertEquals(List.of("name", "summary"), spec.getQueryProperties());
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
        Assert.assertEquals(List.of("a"), spec.getQueryProperties());
        Assert.assertThrows(UnsupportedOperationException.class, () -> spec.getQueryProperties().add("c"));
    }

    @Test
    public void builderCarriesDistance() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.NEAR_TEXT)
            .query("dog")
            .distance(0.7f)
            .build();
        Assert.assertNotNull(spec.getDistance());
        Assert.assertEquals(0.7f, spec.getDistance(), 0.0001f);
    }

    @Test
    public void builderCarriesFusionType() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.HYBRID)
            .query("dog")
            .alpha(0.5f)
            .fusionType(WeaviateHybridFusion.RELATIVE_SCORE)
            .build();
        Assert.assertEquals(WeaviateHybridFusion.RELATIVE_SCORE, spec.getFusionType());
    }

    @Test
    public void hybridFusionWrapperMapsToClientType() {
        Assert.assertNotNull(WeaviateHybridFusion.RANKED.toClientType());
        Assert.assertNotNull(WeaviateHybridFusion.RELATIVE_SCORE.toClientType());
    }

    @Test
    public void nullQueryPropertiesBecomeEmptyList() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.BM25)
            .query("q")
            .queryProperties(null)
            .build();
        Assert.assertTrue(spec.getQueryProperties().isEmpty());
    }
}
