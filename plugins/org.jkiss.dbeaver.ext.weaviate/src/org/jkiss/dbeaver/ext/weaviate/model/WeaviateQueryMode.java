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
    FETCH("Fetch"),
    BM25("BM25"),
    NEAR_TEXT("Near Text"),
    NEAR_VECTOR("Near Vector"),
    NEAR_OBJECT("Near Object"),
    HYBRID("Hybrid");

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
     * Whether autocut applies.
     * <p>
     * Autocut trims where the ranking metric jumps, so it needs a ranking: every mode except a
     * plain fetch, which returns objects in insertion order with no metric to cut on.
     */
    public boolean supportsAutoCut() {
        return this != FETCH;
    }
}
