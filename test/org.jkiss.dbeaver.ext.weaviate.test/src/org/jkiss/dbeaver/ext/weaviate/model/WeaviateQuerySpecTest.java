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

    /**
     * Ranked modes skip sorting and report an unknown row count, so a new mode that forgot to
     * be ranked would silently get plain-fetch behaviour.
     */
    @Test
    public void everyNonFetchModeIsRanked() {
        for (WeaviateQueryMode mode : WeaviateQueryMode.values()) {
            boolean ranked = WeaviateQuerySpec.builder(mode).build().rankedResults();
            Assertions.assertEquals(mode != WeaviateQueryMode.FETCH, ranked, mode + " rankedResults");
        }
    }

    @Test
    public void everyModeHasADistinctLabel() {
        java.util.Set<String> labels = new java.util.HashSet<>();
        for (WeaviateQueryMode mode : WeaviateQueryMode.values()) {
            Assertions.assertTrue(labels.add(mode.getLabel()), "duplicate label: " + mode.getLabel());
            Assertions.assertFalse(mode.getLabel().isBlank(), mode + " has no label");
        }
    }

    @Test
    public void scoreIsKeywordModesOnly() {
        Assertions.assertTrue(WeaviateQueryMode.BM25.hasScore());
        Assertions.assertTrue(WeaviateQueryMode.HYBRID.hasScore());
        Assertions.assertFalse(WeaviateQueryMode.NEAR_TEXT.hasScore());
        Assertions.assertFalse(WeaviateQueryMode.NEAR_VECTOR.hasScore());
        Assertions.assertFalse(WeaviateQueryMode.NEAR_OBJECT.hasScore());
        Assertions.assertFalse(WeaviateQueryMode.FETCH.hasScore());
    }

    @Test
    public void distanceIsNearModesOnly() {
        Assertions.assertTrue(WeaviateQueryMode.NEAR_TEXT.hasDistance());
        Assertions.assertTrue(WeaviateQueryMode.NEAR_VECTOR.hasDistance());
        Assertions.assertTrue(WeaviateQueryMode.NEAR_OBJECT.hasDistance());
        Assertions.assertFalse(WeaviateQueryMode.BM25.hasDistance());
        Assertions.assertFalse(WeaviateQueryMode.FETCH.hasDistance());
    }

    /**
     * Hybrid reports one fused score. Showing a distance beside it would invite comparing two
     * numbers that do not mean the same thing.
     */
    @Test
    public void hybridReportsScoreNotDistance() {
        Assertions.assertTrue(WeaviateQueryMode.HYBRID.hasScore());
        Assertions.assertFalse(WeaviateQueryMode.HYBRID.hasDistance());
    }

    /**
     * A plain fetch is not ranked, so neither metric exists for it.
     */
    @Test
    public void fetchHasNoRelevanceColumns() {
        Assertions.assertFalse(WeaviateQueryMode.FETCH.hasScore());
        Assertions.assertFalse(WeaviateQueryMode.FETCH.hasDistance());
    }

    /**
     * Every ranked mode must report something, or its results are ordered by a number the user
     * cannot see.
     */
    @Test
    public void everyRankedModeExposesAMetric() {
        for (WeaviateQueryMode mode : WeaviateQueryMode.values()) {
            if (mode == WeaviateQueryMode.FETCH) {
                continue;
            }
            Assertions.assertTrue(mode.hasScore() || mode.hasDistance(),
                mode + " is ranked but exposes neither score nor distance");
        }
    }

    @Test
    public void autoCutIsCarriedAndOptional() {
        WeaviateQuerySpec off = WeaviateQuerySpec.builder(WeaviateQueryMode.HYBRID).query("q").build();
        Assertions.assertNull(off.getAutoCut(), "autocut must default to off");

        WeaviateQuerySpec on = WeaviateQuerySpec.builder(WeaviateQueryMode.HYBRID)
            .query("q").autoCut(3).build();
        Assertions.assertEquals(3, on.getAutoCut());
    }

    @Test
    public void withTenantPreservesAutoCut() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.BM25)
            .query("q").autoCut(2).build().withTenant("acme");
        Assertions.assertEquals("acme", spec.getTenant());
        Assertions.assertEquals(2, spec.getAutoCut(), "withTenant must not drop other settings");
        Assertions.assertEquals("q", spec.getQuery());
    }

    /**
     * The full per-mode capability matrix, so a new mode cannot quietly inherit the wrong
     * columns or options from a fallthrough.
     */
    @Test
    public void everyModeDeclaresItsOwnCapabilities() {
        record Expected(WeaviateQueryMode mode, boolean score, boolean distance,
                        boolean explain, boolean autoCut) { }
        java.util.List<Expected> matrix = java.util.List.of(
            new Expected(WeaviateQueryMode.FETCH, false, false, false, false),
            new Expected(WeaviateQueryMode.BM25, true, false, true, true),
            new Expected(WeaviateQueryMode.NEAR_TEXT, false, true, false, true),
            new Expected(WeaviateQueryMode.NEAR_VECTOR, false, true, false, true),
            new Expected(WeaviateQueryMode.NEAR_OBJECT, false, true, false, true),
            new Expected(WeaviateQueryMode.HYBRID, true, false, true, true));

        Assertions.assertEquals(WeaviateQueryMode.values().length, matrix.size(),
            "a mode was added without deciding its score/distance/explain/autocut behaviour");
        for (Expected e : matrix) {
            Assertions.assertEquals(e.score(), e.mode().hasScore(), e.mode() + ".hasScore");
            Assertions.assertEquals(e.distance(), e.mode().hasDistance(), e.mode() + ".hasDistance");
            Assertions.assertEquals(e.explain(), e.mode().hasExplainScore(), e.mode() + ".hasExplainScore");
            Assertions.assertEquals(e.autoCut(), e.mode().supportsAutoCut(), e.mode() + ".supportsAutoCut");
        }
    }

    /**
     * An explanation without a score to explain would be a column of orphaned text.
     */
    @Test
    public void explainScoreImpliesScore() {
        for (WeaviateQueryMode mode : WeaviateQueryMode.values()) {
            if (mode.hasExplainScore()) {
                Assertions.assertTrue(mode.hasScore(), mode + " explains a score it does not report");
            }
        }
    }

    @Test
    public void fetchSupportsNoAutoCut() {
        Assertions.assertFalse(WeaviateQueryMode.FETCH.supportsAutoCut());
        for (WeaviateQueryMode mode : WeaviateQueryMode.values()) {
            if (mode != WeaviateQueryMode.FETCH) {
                Assertions.assertTrue(mode.supportsAutoCut(), mode + " is ranked, so autocut applies");
            }
        }
    }
}
