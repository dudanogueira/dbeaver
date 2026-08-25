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
        record Expected(WeaviateQueryMode mode, boolean score, boolean distance, boolean explain,
                        boolean autoCut, boolean targets, boolean certainty, boolean rerank) { }
        java.util.List<Expected> matrix = java.util.List.of(
            new Expected(WeaviateQueryMode.FETCH, false, false, false, false, false, false, false),
            new Expected(WeaviateQueryMode.BM25, true, false, true, true, false, false, false),
            new Expected(WeaviateQueryMode.NEAR_TEXT, false, true, false, true, true, true, true),
            new Expected(WeaviateQueryMode.NEAR_VECTOR, false, true, false, true, true, true, true),
            new Expected(WeaviateQueryMode.NEAR_OBJECT, false, true, false, true, false, true, true),
            new Expected(WeaviateQueryMode.HYBRID, true, false, true, true, true, false, false));

        Assertions.assertEquals(WeaviateQueryMode.values().length, matrix.size(),
            "a mode was added without deciding its score/distance/explain/autocut/target behaviour");
        for (Expected e : matrix) {
            Assertions.assertEquals(e.score(), e.mode().hasScore(), e.mode() + ".hasScore");
            Assertions.assertEquals(e.distance(), e.mode().hasDistance(), e.mode() + ".hasDistance");
            Assertions.assertEquals(e.explain(), e.mode().hasExplainScore(), e.mode() + ".hasExplainScore");
            Assertions.assertEquals(e.autoCut(), e.mode().supportsAutoCut(), e.mode() + ".supportsAutoCut");
            Assertions.assertEquals(e.targets(), e.mode().supportsTargetVectors(),
                e.mode() + ".supportsTargetVectors");
            Assertions.assertEquals(e.certainty(), e.mode().supportsCertainty(),
                e.mode() + ".supportsCertainty");
            Assertions.assertEquals(e.rerank(), e.mode().supportsRerank(),
                e.mode() + ".supportsRerank");
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

    /**
     * Certainty is a normalisation of the distance, so a mode cannot report one without the
     * other.
     */
    @Test
    public void certaintyExistsExactlyWhereDistanceDoes() {
        for (WeaviateQueryMode mode : WeaviateQueryMode.values()) {
            Assertions.assertEquals(mode.hasDistance(), mode.supportsCertainty(),
                mode + ": certainty without distance (or the reverse)");
        }
    }

    /**
     * The dropdown is populated from values() and the selected index is mapped straight back,
     * so declaration order is user-facing and reordering it silently changes the UI.
     */
    @Test
    public void modeOrderMatchesTheDropdown() {
        Assertions.assertEquals(
            java.util.List.of(
                WeaviateQueryMode.FETCH,
                WeaviateQueryMode.HYBRID,
                WeaviateQueryMode.NEAR_TEXT,
                WeaviateQueryMode.BM25,
                WeaviateQueryMode.NEAR_VECTOR,
                WeaviateQueryMode.NEAR_OBJECT),
            java.util.List.of(WeaviateQueryMode.values()));
    }

    @Test
    public void explainScoreIsOffByDefault() {
        Assertions.assertFalse(
            WeaviateQuerySpec.builder(WeaviateQueryMode.BM25).query("q").build().isExplainScore());
        Assertions.assertFalse(
            WeaviateQuerySpec.builder(WeaviateQueryMode.HYBRID).query("q").build().isExplainScore());
    }

    @Test
    public void explainScoreIsCarriedWhenRequested() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.HYBRID)
            .query("q").explainScore(true).build();
        Assertions.assertTrue(spec.isExplainScore());
        Assertions.assertTrue(spec.withTenant("acme").isExplainScore(),
            "withTenant must not drop the explain-score choice");
    }

    /**
     * Only modes that can explain a score should ever be asked to. The flag being set on another
     * mode must stay inert rather than producing an empty column.
     */
    @Test
    public void explainScoreOnlyAppliesWhereTheModeSupportsIt() {
        for (WeaviateQueryMode mode : WeaviateQueryMode.values()) {
            boolean requested = WeaviateQuerySpec.builder(mode).explainScore(true).build().isExplainScore();
            Assertions.assertTrue(requested, mode + " should carry the request verbatim");
            if (!mode.hasExplainScore()) {
                Assertions.assertFalse(mode.hasScore() && mode != WeaviateQueryMode.FETCH
                        && mode.hasExplainScore(),
                    mode + " cannot explain a score");
            }
        }
    }

    @Test
    public void targetsDefaultToNone() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.fetch();
        Assertions.assertTrue(spec.getTargets().isEmpty());
        Assertions.assertFalse(spec.hasTargets());
        Assertions.assertNull(spec.getCombination());
    }

    @Test
    public void targetsRoundTripThroughTheBuilder() {
        WeaviateVectorTarget title = WeaviateVectorTarget.of("title", 0.7f);
        WeaviateVectorTarget body = WeaviateVectorTarget.of("body", 0.3f);
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.NEAR_TEXT)
            .query("shoes")
            .targets(java.util.List.of(title, body))
            .combination(WeaviateVectorCombination.MANUAL_WEIGHTS)
            .build();

        Assertions.assertTrue(spec.hasTargets());
        Assertions.assertEquals(java.util.List.of(title, body), spec.getTargets());
        Assertions.assertEquals(WeaviateVectorCombination.MANUAL_WEIGHTS, spec.getCombination());
    }

    @Test
    public void nearVectorTargetsCarryTheirOwnVectors() {
        WeaviateVectorTarget flat = WeaviateVectorTarget.of("title", null, new float[]{0.1f, 0.2f});
        WeaviateVectorTarget matrix =
            WeaviateVectorTarget.of("colbert", null, new float[][]{{0.1f, 0.2f}, {0.3f, 0.4f}});

        Assertions.assertFalse(flat.isMulti());
        Assertions.assertTrue(flat.hasQueryVector());
        Assertions.assertArrayEquals(new float[]{0.1f, 0.2f}, flat.getVector(), 0.0001f);
        Assertions.assertTrue(matrix.isMulti());
        Assertions.assertArrayEquals(new float[]{0.3f, 0.4f}, matrix.getMultiVector()[1], 0.0001f);
    }

    /**
     * A target holds arrays, so without copies a caller could reach in and change a spec that is
     * supposed to be immutable -- and the spec is shared across reads and the panel.
     */
    @Test
    public void targetCopiesItsVectors() {
        float[] source = {0.1f, 0.2f};
        WeaviateVectorTarget target = WeaviateVectorTarget.of("title", null, source);
        source[0] = 99f;
        target.getVector()[1] = 99f;
        Assertions.assertArrayEquals(new float[]{0.1f, 0.2f}, target.getVector(), 0.0001f);
    }

    @Test
    public void aTargetMustBeNamed() {
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> WeaviateVectorTarget.of("  ", null));
    }

    /**
     * withIncludeVector used to enumerate the fields to copy by hand and had fallen behind by
     * four of them, so toggling vectors silently reset the tenant, the reference object, autocut
     * and the explain-score flag. Everything must survive the copy.
     */
    @Test
    public void withIncludeVectorKeepsEveryOtherField() {
        WeaviateVectorTarget title = WeaviateVectorTarget.of("title", 0.7f);
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.NEAR_OBJECT)
            .objectId("11111111-2222-3333-4444-555555555555")
            .tenant("acme")
            .autoCut(3)
            .explainScore(true)
            .distance(0.25f)
            .targets(java.util.List.of(title))
            .combination(WeaviateVectorCombination.RELATIVE_SCORE)
            .withCreated(true)
            .withUpdated(true)
            .withCertainty(true)
            .includeVector(false)
            .build();

        WeaviateQuerySpec copy = spec.withIncludeVector(true);

        Assertions.assertTrue(copy.isIncludeVector());
        Assertions.assertEquals("11111111-2222-3333-4444-555555555555", copy.getObjectId());
        Assertions.assertEquals("acme", copy.getTenant());
        Assertions.assertEquals(Integer.valueOf(3), copy.getAutoCut());
        Assertions.assertTrue(copy.isExplainScore());
        Assertions.assertEquals(Float.valueOf(0.25f), copy.getDistance());
        Assertions.assertEquals(java.util.List.of(title), copy.getTargets());
        Assertions.assertEquals(WeaviateVectorCombination.RELATIVE_SCORE, copy.getCombination());
        Assertions.assertTrue(copy.isWithCreated());
        Assertions.assertTrue(copy.isWithUpdated());
        Assertions.assertTrue(copy.isWithCertainty());
    }

    @Test
    public void withTenantKeepsTheTargets() {
        WeaviateVectorTarget title = WeaviateVectorTarget.of("title", null);
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.HYBRID)
            .query("shoes")
            .alpha(0.4f)
            .targets(java.util.List.of(title))
            .combination(WeaviateVectorCombination.AVERAGE)
            .build();

        WeaviateQuerySpec copy = spec.withTenant("acme");

        Assertions.assertEquals("acme", copy.getTenant());
        Assertions.assertEquals("shoes", copy.getQuery());
        Assertions.assertEquals(Float.valueOf(0.4f), copy.getAlpha());
        Assertions.assertEquals(java.util.List.of(title), copy.getTargets());
        Assertions.assertEquals(WeaviateVectorCombination.AVERAGE, copy.getCombination());
    }

    @Test
    public void rerankSurvivesTheCopies() {
        WeaviateRerankSpec rerank = new WeaviateRerankSpec("title", "shoes");
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.NEAR_TEXT)
            .query("q")
            .rerank(rerank)
            .build();
        Assertions.assertEquals(rerank, spec.withTenant("acme").getRerank());
        Assertions.assertEquals(rerank, spec.withIncludeVector(true).getRerank());
    }

    @Test
    public void rerankNeedsAProperty() {
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> new WeaviateRerankSpec("  ", null));
    }

    /**
     * A blank rerank query means "rank against the search query" and is stored as null, so two
     * specs built from an empty box and an untouched one compare equal.
     */
    @Test
    public void blankRerankQueryNormalisesToNull() {
        Assertions.assertNull(new WeaviateRerankSpec("title", "   ").getQuery());
        Assertions.assertEquals(new WeaviateRerankSpec("title", null),
            new WeaviateRerankSpec("title", ""));
    }

    @Test
    public void generativeTaskNeedsAPrompt() {
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> WeaviateGenerativeTask.builder().build());
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> WeaviateGenerativeTask.builder().singlePrompt("   ").build());
    }

    @Test
    public void generativeTaskNormalisesBlanks() {
        WeaviateGenerativeTask task = WeaviateGenerativeTask.builder()
            .singlePrompt("Summarize {title}")
            .groupedTask("  ")
            .model("   ")
            .build();
        Assertions.assertEquals("Summarize {title}", task.getSinglePrompt());
        Assertions.assertNull(task.getGroupedTask());
        Assertions.assertNull(task.getModel());
        Assertions.assertTrue(task.getGroupedProperties().isEmpty());
    }

    @Test
    public void generativeSurvivesTheCopies() {
        WeaviateGenerativeTask task = WeaviateGenerativeTask.builder()
            .singlePrompt("Summarize {title}")
            .groupedTask("What do these share?")
            .groupedProperties(java.util.List.of("title", "body"))
            .provider(WeaviateGenerativeProvider.OPENAI)
            .model("gpt-4o")
            .temperature(0.2f)
            .maxTokens(128)
            .returnMetadata(true)
            .build();
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.HYBRID)
            .query("q")
            .generative(task)
            .build();
        Assertions.assertEquals(task, spec.withTenant("acme").getGenerative());
        Assertions.assertEquals(task, spec.withIncludeVector(true).getGenerative());
    }

    /**
     * A viewer executes the remembered spec the moment it opens, so only what browsing means --
     * a plain fetch with no generative task -- may run unasked. Everything else waits for Run.
     */
    @Test
    public void onlyAPlainFetchIsAutoRunSafe() {
        Assertions.assertTrue(WeaviateQuerySpec.fetch().isAutoRunSafe());
        for (WeaviateQueryMode mode : WeaviateQueryMode.values()) {
            if (mode != WeaviateQueryMode.FETCH) {
                Assertions.assertFalse(
                    WeaviateQuerySpec.builder(mode).query("q").build().isAutoRunSafe(),
                    mode + " must not re-fire on viewer open");
            }
        }
        Assertions.assertFalse(WeaviateQuerySpec.builder(WeaviateQueryMode.FETCH)
                .generative(WeaviateGenerativeTask.builder().singlePrompt("p").build())
                .build()
                .isAutoRunSafe(),
            "a generative fetch calls a paid model per object; never unasked");
    }

    /**
     * Demotion is what makes reopening a viewer cheap without losing the query: the mode drops
     * to Fetch, every input survives, and Run brings the search back.
     */
    @Test
    public void demotionKeepsEveryInput() {
        WeaviateVectorTarget target = WeaviateVectorTarget.of("title", 0.7f);
        WeaviateGenerativeTask task = WeaviateGenerativeTask.builder().singlePrompt("p").build();
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.HYBRID)
            .query("shoes")
            .alpha(0.4f)
            .tenant("acme")
            .targets(java.util.List.of(target))
            .rerank(new WeaviateRerankSpec("title", null))
            .generative(task)
            .filterRows(java.util.List.of())
            .includeVector(true)
            .build();

        WeaviateQuerySpec demoted = spec.demotedToFetch();

        Assertions.assertEquals(WeaviateQueryMode.FETCH, demoted.getMode());
        Assertions.assertEquals("shoes", demoted.getQuery());
        Assertions.assertEquals(Float.valueOf(0.4f), demoted.getAlpha());
        Assertions.assertEquals("acme", demoted.getTenant());
        Assertions.assertEquals(java.util.List.of(target), demoted.getTargets());
        Assertions.assertEquals(new WeaviateRerankSpec("title", null), demoted.getRerank());
        Assertions.assertEquals(task, demoted.getGenerative());
        Assertions.assertTrue(demoted.isIncludeVector());
        // A demoted fetch still carries the task, so it is still not safe to run unasked;
        // the executor strips the task with withoutGenerative instead.
        Assertions.assertFalse(demoted.isAutoRunSafe());
        Assertions.assertNull(demoted.withoutGenerative().getGenerative());
        Assertions.assertTrue(demoted.withoutGenerative().isAutoRunSafe());
    }

    @Test
    public void demotingAFetchIsANoOp() {
        WeaviateQuerySpec fetch = WeaviateQuerySpec.fetch();
        Assertions.assertSame(fetch, fetch.demotedToFetch());
        Assertions.assertSame(fetch, fetch.withoutGenerative());
    }

    /**
     * The one test that matters for group-by on the spec: every {@code with*} copy goes through
     * {@code copy()}, which enumerates its fields by hand. A field left out of that list is
     * dropped silently -- which is exactly how {@code withIncludeVector} once lost the tenant,
     * the object id, autocut and the explain-score flag.
     */
    @Test
    public void groupBySurvivesEveryCopy() {
        WeaviateGroupBySpec groupBy = new WeaviateGroupBySpec("category", 4, 2, true);
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.NEAR_TEXT)
            .query("dog")
            .groupBy(groupBy)
            .build();

        Assertions.assertEquals(groupBy, spec.getGroupBy());
        Assertions.assertTrue(spec.isGrouped());

        Assertions.assertEquals(groupBy, spec.demotedToFetch().getGroupBy(),
            "demoting to fetch dropped the grouping");
        Assertions.assertEquals(groupBy, spec.withoutGenerative().getGroupBy(),
            "stripping the generative task dropped the grouping");
        Assertions.assertEquals(groupBy, spec.withTenant("t1").getGroupBy(),
            "choosing a tenant dropped the grouping");
        Assertions.assertEquals(groupBy, spec.withIncludeVector(true).getGroupBy(),
            "toggling vectors dropped the grouping");
    }

    /** No grouping is the default, and isGrouped has to agree with getGroupBy. */
    @Test
    public void anUngroupedSpecReportsItself() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.fetch();
        Assertions.assertNull(spec.getGroupBy());
        Assertions.assertFalse(spec.isGrouped());
    }

    /**
     * The grouping is kept through a demotion but goes inert, because the server refuses a
     * grouped fetch. Keeping it is what lets switching back to a ranked mode restore it;
     * reporting it as inactive is what stops a reopened viewer from sending a query that fails.
     */
    @Test
    public void aGroupingIsKeptButInertUnderFetch() {
        WeaviateGroupBySpec groupBy = new WeaviateGroupBySpec("category", 4, 2, false);
        WeaviateQuerySpec fetch = WeaviateQuerySpec.builder(WeaviateQueryMode.FETCH)
            .groupBy(groupBy)
            .build();

        Assertions.assertEquals(groupBy, fetch.getGroupBy(), "the grouping should be remembered");
        Assertions.assertFalse(fetch.isGrouped(), "a grouped fetch would be rejected by the server");
        // Still browsable on open: an inert grouping neither embeds nor calls a model.
        Assertions.assertTrue(fetch.isAutoRunSafe());

        WeaviateQuerySpec ranked = WeaviateQuerySpec.builder(WeaviateQueryMode.BM25)
            .query("x")
            .groupBy(groupBy)
            .build();
        Assertions.assertTrue(ranked.isGrouped());
        Assertions.assertFalse(ranked.demotedToFetch().isGrouped(),
            "demoting to fetch must not leave a grouping that the server will reject");
        Assertions.assertEquals(groupBy, ranked.demotedToFetch().getGroupBy(),
            "...but the grouping itself must survive so the panel can still show it");
    }
}
