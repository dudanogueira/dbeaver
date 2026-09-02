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

import io.weaviate.client6.v1.api.collections.query.SearchOperator;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

/**
 * How a keyword query's tokens are combined when matching.
 * <p>
 * Keyword only: the server takes it on BM25 and on hybrid's keyword half, and nowhere else --
 * a vector search has no tokens to combine. {@link WeaviateQueryMode#supportsSearchOperator()}
 * is what decides where the control appears.
 * <p>
 * The default is the server's, not one of these. Leaving the operator unset sends no
 * {@code searchOperator} at all, which is not the same as sending OR with no minimum.
 */
public enum WeaviateSearchOperator {

    /** Every token must match. The narrowest reading of the query. */
    AND("All words", false),
    /**
     * Every token must match, but they may be spread across different properties rather than all
     * appearing in one. Wider than {@link #AND} on a multi-property search.
     */
    AND_CROSS("All words, across properties", false),
    /**
     * At least {@code minimumOrTokens} of the tokens must match. With a minimum of 1 this is the
     * loosest reading; raising it trades recall for precision without demanding every word.
     */
    OR("At least N words", true);

    private final String label;
    private final boolean takesMinimum;

    WeaviateSearchOperator(@NotNull String label, boolean takesMinimum) {
        this.label = label;
        this.takesMinimum = takesMinimum;
    }

    @NotNull
    public String getLabel() {
        return label;
    }

    /** Whether this operator reads {@code minimumOrTokens}; only OR does. */
    public boolean takesMinimum() {
        return takesMinimum;
    }

    /**
     * @param minimumOrTokens how many tokens must match, used by {@link #OR} alone; a null or
     *                        non-positive value falls back to 1, which is OR's own default and
     *                        the value the server assumes
     */
    @NotNull
    public SearchOperator toClientType(@Nullable Integer minimumOrTokens) {
        return switch (this) {
            case AND -> SearchOperator.and();
            case AND_CROSS -> SearchOperator.andCross();
            case OR -> SearchOperator.or(
                minimumOrTokens == null || minimumOrTokens < 1 ? 1 : minimumOrTokens);
        };
    }

    /** Resolves a stored name, tolerating one this build does not know. */
    @Nullable
    public static WeaviateSearchOperator fromName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (WeaviateSearchOperator op : values()) {
            if (op.name().equalsIgnoreCase(name)) {
                return op;
            }
        }
        return null;
    }
}
