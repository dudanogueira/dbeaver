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

/**
 * The text DBeaver shows as "the query". Weaviate has no query language, so this is the only
 * statement text there is -- it reaches the query log, the error messages and the statistics.
 */
public class WeaviateQueryDescriptionTest extends DBeaverUnitTest {

    private static String describe(WeaviateQuerySpec spec) {
        return WeaviateQueryDescription.describeQuery(spec, null, List.of(), 0, 0);
    }

    @Test
    public void aQueryWithNoArgumentsIsStillBalanced() {
        // The reason the argument list is built before it is joined: patching up trailing
        // separators in place used to emit "fetchObjects)" for a FETCH with nothing set.
        Assertions.assertEquals("fetchObjects()", describe(WeaviateQuerySpec.fetch()));
    }

    @Test
    public void queryTextIsQuotedAndEscaped() {
        Assertions.assertEquals("bm25(query=\"shoes\")", describe(WeaviateQuerySpec.bm25("shoes")));
        Assertions.assertEquals(
            "bm25(query=\"say \\\"hi\\\"\")",
            describe(WeaviateQuerySpec.bm25("say \"hi\"")));
    }

    @Test
    public void hybridCarriesItsAlpha() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.HYBRID)
            .query("shoes").alpha(0.7f).build();
        Assertions.assertEquals("hybrid(query=\"shoes\", alpha=0.7)", describe(spec));
    }

    @Test
    public void limitAndOffsetAppearOnlyWhenSet() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.bm25("shoes");
        Assertions.assertEquals("bm25(query=\"shoes\", limit=200)",
            WeaviateQueryDescription.describeQuery(spec, null, List.of(), 200, 0));
        Assertions.assertEquals("bm25(query=\"shoes\", limit=200, offset=40)",
            WeaviateQueryDescription.describeQuery(spec, null, List.of(), 200, 40));
    }

    @Test
    public void oneTargetIsNotJoinedWithAnything() {
        // Naming a join strategy for a single target would be noise -- there is nothing to join.
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.NEAR_TEXT)
            .query("dog")
            .targets(List.of(WeaviateVectorTarget.of("body", null)))
            .build();
        String described = describe(spec);
        Assertions.assertTrue(described.contains("targets=[body]"), described);
        Assertions.assertFalse(described.contains("join="), described);
    }

    @Test
    public void twoTargetsNameTheirJoinStrategy() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.NEAR_TEXT)
            .query("dog")
            .targets(List.of(
                WeaviateVectorTarget.of("body", null),
                WeaviateVectorTarget.of("title", null)))
            .combination(WeaviateVectorCombination.SUM)
            .build();
        String described = describe(spec);
        Assertions.assertTrue(described.contains("targets=[body, title]"), described);
        Assertions.assertTrue(described.contains("join=SUM"), described);
    }

    @Test
    public void searchOptionsAppearInTheLoggedCall() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.BM25)
            .query("shoes")
            .searchOperator(WeaviateSearchOperator.OR)
            .minimumOrTokens(2)
            .consistencyLevel(WeaviateConsistencyLevel.QUORUM)
            .withQueryProfile(true)
            .build();
        String described = describe(spec);
        Assertions.assertTrue(described.contains("operator=OR(2)"), described);
        // Consistency changes what the server does rather than which rows come back, so a log
        // line that hid it would make two different queries look identical.
        Assertions.assertTrue(described.contains("consistency=QUORUM"), described);
        Assertions.assertTrue(described.contains("profile"), described);
    }

    @Test
    public void anOperatorWithoutAMinimumIsNotGivenOne() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.BM25)
            .query("shoes").searchOperator(WeaviateSearchOperator.AND).build();
        // "(" alone would match bm25( -- what must not appear is a minimum on an operator that
        // does not read one.
        Assertions.assertTrue(describe(spec).contains("operator=AND"), describe(spec));
        Assertions.assertFalse(describe(spec).contains("AND("), describe(spec));
    }

    @Test
    public void mmrRendersOnlyTheFieldsThatWereSet() {
        // The limit is required, so it is always rendered; balance is not.
        WeaviateQuerySpec bare = WeaviateQuerySpec.builder(WeaviateQueryMode.NEAR_TEXT)
            .query("dog").diversity(new WeaviateDiversitySpec(5, null)).build();
        Assertions.assertTrue(describe(bare).contains("mmr(limit=5)"), describe(bare));

        WeaviateQuerySpec tuned = WeaviateQuerySpec.builder(WeaviateQueryMode.NEAR_TEXT)
            .query("dog").diversity(new WeaviateDiversitySpec(5, 0.5f)).build();
        Assertions.assertTrue(
            describe(tuned).contains("mmr(limit=5, balance=0.5)"), describe(tuned));
    }

    @Test
    public void nearVectorReportsItsDimensionRatherThanTheVector() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.nearVector(new float[]{0.1f, 0.2f, 0.3f});
        Assertions.assertEquals("nearVector(dim=3)", describe(spec));
    }
}
