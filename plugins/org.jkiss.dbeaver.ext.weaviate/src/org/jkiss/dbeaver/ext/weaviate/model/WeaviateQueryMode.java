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

public enum WeaviateQueryMode {
    // Declaration order is the order of the mode dropdown: the panel populates it from values()
    // and maps the selected index straight back. Ordered by how often they are reached for --
    // plain fetch, then hybrid as the usual first search, then the text-driven ones -- rather
    // than alphabetically or by internal grouping.
    FETCH("Fetch"),
    HYBRID("Hybrid"),
    NEAR_TEXT("Near Text"),
    BM25("BM25"),
    NEAR_VECTOR("Near Vector"),
    NEAR_OBJECT("Near Object");

    private final String label;

    WeaviateQueryMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * Whether results carry a relevance score.
     * <p>
     * Keyword scoring only: BM25 ranks by term relevance, and hybrid blends that score with the
     * vector side into a single fused score.
     */
    public boolean hasScore() {
        return this == BM25 || this == HYBRID;
    }

    /**
     * Whether results carry a vector distance.
     * <p>
     * The near_* searches rank by distance from a query vector -- supplied directly, derived from
     * text, or taken from another object. Hybrid is excluded: it reports the fused score, and a
     * per-row distance would invite comparing two numbers that do not mean the same thing.
     */
    public boolean hasDistance() {
        return this == NEAR_TEXT || this == NEAR_VECTOR || this == NEAR_OBJECT;
    }

    /**
     * Whether the server can explain how the score was reached.
     * <p>
     * Only the keyword side produces an explanation: BM25 can itemise per-term contributions,
     * and hybrid inherits that for its keyword half. A near_* search ranks purely by vector
     * distance, which has nothing to itemise.
     */
    public boolean hasExplainScore() {
        return this == BM25 || this == HYBRID;
    }

    /**
     * Whether a reranker can reorder this mode's results.
     * <p>
     * A client limitation, not a server one: client 6.3.1 exposes rerank only on the vector
     * search builders, so BM25 and Hybrid -- which the server could rerank -- have nowhere to
     * attach it. Fetch is unranked either way. Revisit on a client upgrade.
     */
    public boolean supportsRerank() {
        return this == NEAR_TEXT || this == NEAR_VECTOR || this == NEAR_OBJECT;
    }

    /**
     * Whether the server can report a certainty for this mode's results.
     * <p>
     * Certainty is a normalisation of the vector distance, so it exists exactly where distance
     * does: the three near_* searches. The keyword and fused scores have no distance to derive
     * it from -- the server leaves it null there, so offering the column would only add a blank.
     */
    public boolean supportsCertainty() {
        return hasDistance();
    }

    /**
     * Whether this mode can name the vectors it searches.
     * <p>
     * The three that reach a vector index: Near Text and Hybrid have the server embed the query
     * text, Near Vector is handed the vectors outright. Fetch ranks nothing, BM25 is keyword-only
     * and never touches a vector, and Near Object is left out for a duller reason -- Weaviate
     * supports targets there, but client 6.3.1 exposes no Target overload of {@code nearObject}
     * to send them with.
     */
    public boolean supportsTargetVectors() {
        return this == NEAR_TEXT || this == NEAR_VECTOR || this == HYBRID;
    }

    /**
     * Whether the mode takes a keyword search operator.
     * <p>
     * BM25 and hybrid's keyword half, and nowhere else -- the client exposes
     * {@code searchOperator} on {@code Bm25.Builder} and {@code Hybrid.Builder} only, which
     * matches the server: a vector search has no tokens to combine.
     */
    public boolean supportsSearchOperator() {
        return this == BM25 || this == HYBRID;
    }

    /**
     * Whether the mode can diversify its results with MMR.
     * <p>
     * The three vector searches and hybrid. MMR re-picks from a candidate pool by vector distance
     * between the candidates, so a pure keyword search has nothing for it to measure, and Fetch
     * ranks nothing at all. The client agrees: {@code diversity} sits on
     * {@code BaseVectorSearchBuilder} and on {@code Hybrid.Builder}.
     */
    public boolean supportsDiversity() {
        return this == NEAR_TEXT || this == NEAR_VECTOR || this == NEAR_OBJECT || this == HYBRID;
    }

    /**
     * Whether autocut applies.
     * <p>
     * Autocut trims where the ranking metric jumps, so it needs a ranking: every mode except a
     * plain fetch, which returns objects in insertion order with no metric to cut on.
     */
    public boolean supportsAutoCut() {
        return this != FETCH;
    }

    /**
     * Whether results can be grouped.
     * <p>
     * Every mode but a plain fetch. The client declares a {@code fetchObjects(GroupBy)} overload
     * and it looks like the sixth supported mode, but the server rejects it outright --
     * {@code "group is not present"} -- because grouping needs a ranking to order the groups by
     * and a bare fetch has none. Verified against Weaviate 1.39; the Python client does not
     * expose the combination at all, which is the same limitation stated as an absence.
     */
    public boolean supportsGroupBy() {
        return this != FETCH;
    }
}
