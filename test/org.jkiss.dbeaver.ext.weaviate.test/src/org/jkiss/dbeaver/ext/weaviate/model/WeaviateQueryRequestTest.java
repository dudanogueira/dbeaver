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

import io.weaviate.client6.v1.api.collections.query.Metadata;
import io.weaviate.client6.v1.api.collections.query.SortBy;
import org.jkiss.dbeaver.model.data.DBDAttributeConstraint;
import org.jkiss.dbeaver.model.data.DBDDataFilter;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * The spec-to-request translation, which is the one part of the query path with no I/O in it.
 * <p>
 * It was unreachable until it was extracted from {@code WeaviateCollection}, where it lived as
 * private statics. These are the rules that decide what the server is actually asked for, so
 * getting one wrong is a silently wrong query rather than a failure.
 */
public class WeaviateQueryRequestTest extends DBeaverUnitTest {

    private static WeaviateQuerySpec.Builder near() {
        return WeaviateQuerySpec.builder(WeaviateQueryMode.NEAR_TEXT).query("dog");
    }

    // -- target vectors ----------------------------------------------------------------------

    @Test
    public void targetNamesDedupeAndKeepOrder() {
        WeaviateQuerySpec spec = near().targets(List.of(
            WeaviateVectorTarget.of("body", null),
            WeaviateVectorTarget.of("title", null),
            WeaviateVectorTarget.of("body", 2.0f))).build();
        // "body" twice is legitimate -- the same vector can carry two weights in a join -- but it
        // is one column and one thing to ask the server for.
        Assertions.assertEquals(List.of("body", "title"), WeaviateQueryRequest.targetVectorNames(spec));
    }

    @Test
    public void noTargetsMeansNoNames() {
        Assertions.assertTrue(WeaviateQueryRequest.targetVectorNames(near().build()).isEmpty());
    }

    @Test
    public void withoutTargetsEveryDeclaredVectorIsInPlay() {
        List<String> declared = List.of("body", "title");
        Assertions.assertEquals(
            declared,
            WeaviateQueryRequest.narrowToTargets(near().build(), declared));
    }

    @Test
    public void aTargetTheCollectionNoLongerDeclaresIsDropped() {
        WeaviateQuerySpec spec = near().targets(List.of(
            WeaviateVectorTarget.of("body", null),
            WeaviateVectorTarget.of("removed", null))).build();
        // Otherwise the grid grows a column that can never be filled: readData builds the vector
        // columns from this list and applyCommon asks the server for exactly the same list.
        Assertions.assertEquals(
            List.of("body"),
            WeaviateQueryRequest.narrowToTargets(spec, List.of("body", "title")));
    }

    // -- metadata ----------------------------------------------------------------------------

    @Test
    public void theScoreExplanationIsOptIn() {
        WeaviateQuerySpec plain = WeaviateQuerySpec.bm25("shoes");
        Assertions.assertArrayEquals(
            new Metadata[]{Metadata.SCORE},
            WeaviateQueryRequest.scoreMetadata(plain));

        WeaviateQuerySpec explained = WeaviateQuerySpec.builder(WeaviateQueryMode.BM25)
            .query("shoes").explainScore(true).build();
        Assertions.assertArrayEquals(
            new Metadata[]{Metadata.SCORE, Metadata.EXPLAIN_SCORE},
            WeaviateQueryRequest.scoreMetadata(explained));
    }

    @Test
    public void timestampsAreRequestedWhenAskedFor() {
        WeaviateQuerySpec spec = near().withCreated(true).withUpdated(true).build();
        Assertions.assertEquals(
            List.of(Metadata.CREATION_TIME_UNIX, Metadata.LAST_UPDATE_TIME_UNIX),
            WeaviateQueryRequest.extraMetadata(spec));
    }

    @Test
    public void certaintyIsGatedOnTheMode() {
        // The server derives certainty from vector distance, so a keyword search cannot produce
        // it. Asking anyway returns nothing, so the flag is dropped rather than sent.
        WeaviateQuerySpec vectorSearch = near().withCertainty(true).build();
        Assertions.assertEquals(List.of(Metadata.CERTAINTY),
            WeaviateQueryRequest.extraMetadata(vectorSearch));

        WeaviateQuerySpec keywordSearch = WeaviateQuerySpec.builder(WeaviateQueryMode.BM25)
            .query("shoes").withCertainty(true).build();
        Assertions.assertTrue(WeaviateQueryRequest.extraMetadata(keywordSearch).isEmpty());
    }

    @Test
    public void nothingOptedIntoMeansNoExtraMetadata() {
        Assertions.assertTrue(WeaviateQueryRequest.extraMetadata(near().build()).isEmpty());
    }

    // -- search options ----------------------------------------------------------------------

    @Test
    public void theProfileIsOptInLikeTheOtherMetadata() {
        // Asking is what makes the server produce one, so an unasked query must not carry it.
        Assertions.assertFalse(
            WeaviateQueryRequest.extraMetadata(near().build()).contains(Metadata.QUERY_PROFILE));
        Assertions.assertTrue(
            WeaviateQueryRequest.extraMetadata(near().withQueryProfile(true).build())
                .contains(Metadata.QUERY_PROFILE));
    }

    @Test
    public void anUnsetOperatorSendsNothing() {
        // Not the same as sending OR with a minimum of one: unset leaves the choice to the server.
        Assertions.assertNull(WeaviateQueryRequest.searchOperator(WeaviateQuerySpec.bm25("shoes")));
    }

    @Test
    public void orCarriesItsMinimumAndTheOthersDoNot() {
        Assertions.assertTrue(WeaviateSearchOperator.OR.takesMinimum());
        Assertions.assertFalse(WeaviateSearchOperator.AND.takesMinimum());
        Assertions.assertFalse(WeaviateSearchOperator.AND_CROSS.takesMinimum());
        // A missing or nonsensical minimum falls back to OR's own default rather than reaching
        // the server as zero.
        Assertions.assertNotNull(WeaviateSearchOperator.OR.toClientType(null));
        Assertions.assertNotNull(WeaviateSearchOperator.OR.toClientType(0));
    }

    @Test
    public void theOperatorIsDroppedOnAModeThatHasNone() {
        // The setting survives on the spec so switching back restores it; what must not happen is
        // it reaching a vector search, which has no tokens to combine.
        WeaviateQuerySpec keyword = WeaviateQuerySpec.builder(WeaviateQueryMode.BM25)
            .query("shoes").searchOperator(WeaviateSearchOperator.AND).build();
        Assertions.assertEquals(WeaviateSearchOperator.AND, keyword.getSearchOperator());

        WeaviateQuerySpec vector = WeaviateQuerySpec.builder(WeaviateQueryMode.NEAR_TEXT)
            .query("dog").searchOperator(WeaviateSearchOperator.AND).build();
        Assertions.assertNull(vector.getSearchOperator());
        Assertions.assertNull(WeaviateQueryRequest.searchOperator(vector));
    }

    @Test
    public void diversityIsDroppedOnAModeThatCannotDiversify() {
        WeaviateQuerySpec vector = near().diversity(WeaviateDiversitySpec.DEFAULTS).build();
        Assertions.assertNotNull(vector.getDiversity());
        Assertions.assertNotNull(WeaviateQueryRequest.diversity(vector));

        // BM25 ranks by term relevance; there is no distance between candidates to spread out on.
        WeaviateQuerySpec keyword = WeaviateQuerySpec.builder(WeaviateQueryMode.BM25)
            .query("shoes").diversity(WeaviateDiversitySpec.DEFAULTS).build();
        Assertions.assertNull(keyword.getDiversity());
        Assertions.assertNull(WeaviateQueryRequest.diversity(keyword));
    }

    @Test
    public void everyModeAgreesWithWhatTheClientBuildersAccept() {
        // These four rules are the whole feature -- get one wrong and a control is offered where
        // the server will refuse it, or hidden where it would have worked.
        for (WeaviateQueryMode mode : WeaviateQueryMode.values()) {
            boolean keyword = mode == WeaviateQueryMode.BM25 || mode == WeaviateQueryMode.HYBRID;
            Assertions.assertEquals(keyword, mode.supportsSearchOperator(), mode.name());
            boolean vectorish = mode != WeaviateQueryMode.BM25 && mode != WeaviateQueryMode.FETCH;
            Assertions.assertEquals(vectorish, mode.supportsDiversity(), mode.name());
        }
    }

    @Test
    public void consistencyLevelSurvivesAModeSwitch() {
        // Unlike the operator and MMR it applies everywhere, so no mode may drop it.
        for (WeaviateQueryMode mode : WeaviateQueryMode.values()) {
            WeaviateQuerySpec spec = WeaviateQuerySpec.builder(mode)
                .query("x").vector(new float[]{1f}).objectId("id")
                .consistencyLevel(WeaviateConsistencyLevel.QUORUM).build();
            Assertions.assertEquals(
                WeaviateConsistencyLevel.QUORUM, spec.getConsistencyLevel(), mode.name());
        }
    }

    // -- required input ----------------------------------------------------------------------

    @Test
    public void aBlankQueryIsRefusedAndTheModeIsNamed() {
        WeaviateQuerySpec blank = WeaviateQuerySpec.builder(WeaviateQueryMode.BM25).query("   ").build();
        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
            () -> WeaviateQueryRequest.requireQuery(blank, "BM25"));
        Assertions.assertTrue(e.getMessage().contains("BM25"), e.getMessage());
    }

    @Test
    public void anEmptyVectorIsRefused() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.NEAR_VECTOR)
            .vector(new float[0]).build();
        Assertions.assertThrows(IllegalStateException.class,
            () -> WeaviateQueryRequest.requireVector(spec));
    }

    @Test
    public void aBlankReferenceUuidIsRefused() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.NEAR_OBJECT)
            .objectId(" ").build();
        Assertions.assertThrows(IllegalStateException.class,
            () -> WeaviateQueryRequest.requireObjectId(spec));
    }

    // -- sorting -----------------------------------------------------------------------------

    @Test
    public void noFilterMeansNoSort() {
        Assertions.assertTrue(WeaviateQueryRequest.buildSortBy(null, List.of()).isEmpty());
    }

    @Test
    public void unorderedConstraintsAreIgnored() {
        DBDAttributeConstraint c = new DBDAttributeConstraint("title", 0);
        Assertions.assertTrue(
            WeaviateQueryRequest.buildSortBy(new DBDDataFilter(List.of(c)), List.of()).isEmpty());
    }

    @Test
    public void sortFollowsOrderPositionNotColumnOrder() {
        DBDAttributeConstraint second = new DBDAttributeConstraint("title", 0);
        second.setOrderPosition(2);
        DBDAttributeConstraint first = new DBDAttributeConstraint("price", 1);
        first.setOrderPosition(1);
        first.setOrderDescending(true);

        List<SortBy> sort = WeaviateQueryRequest.buildSortBy(
            new DBDDataFilter(List.of(second, first)), List.of());

        Assertions.assertEquals(2, sort.size());
        Assertions.assertEquals(List.of("price"), sort.get(0).path());
        Assertions.assertFalse(sort.get(0).ascending());
        Assertions.assertEquals(List.of("title"), sort.get(1).path());
        Assertions.assertTrue(sort.get(1).ascending());
    }

    @Test
    public void theUuidColumnSortsAsUuidNotAsAProperty() {
        // uuid is not a property, so SortBy.property("uuid") would ask the server to sort by a
        // field that does not exist.
        Assertions.assertEquals(SortBy.uuid().path(), WeaviateQueryRequest.mapSortBy(WeaviateColumns.UUID).path());
        Assertions.assertEquals(List.of("title"), WeaviateQueryRequest.mapSortBy("title").path());
    }
}
