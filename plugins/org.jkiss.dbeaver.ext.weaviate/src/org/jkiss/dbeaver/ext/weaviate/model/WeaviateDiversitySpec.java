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

import io.weaviate.client6.v1.api.collections.query.Diversity;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

/**
 * Maximal Marginal Relevance: spread the results out instead of returning near-duplicates.
 * <p>
 * A plain vector search answers "what is closest", which on a corpus with repetitive content
 * returns the same thing several times over. MMR re-picks from a wider candidate pool, trading a
 * little relevance for results that differ from each other.
 * <p>
 * Vector modes only, plus hybrid -- it re-ranks by vector distance between candidates, so there is
 * nothing for it to do on a pure keyword search. See
 * {@link WeaviateQueryMode#supportsDiversity()}.
 *
 * @param candidates how many results to consider before picking, or null for the server's
 *                   default. Must be at least the query's limit to change anything: MMR chooses
 *                   from this pool, so a pool the size of the result set leaves nothing to choose
 * @param balance    0 favours diversity, 1 favours relevance, or null for the server's default
 */
public record WeaviateDiversitySpec(@Nullable Integer candidates, @Nullable Float balance) {

    /** Neither field set: MMR on, entirely with the server's own defaults. */
    public static final WeaviateDiversitySpec DEFAULTS = new WeaviateDiversitySpec(null, null);

    @NotNull
    public Diversity toClientType() {
        return Diversity.mmr(b -> {
            if (candidates != null && candidates > 0) {
                b.limit(candidates);
            }
            if (balance != null) {
                b.balance(balance);
            }
            return b;
        });
    }
}
