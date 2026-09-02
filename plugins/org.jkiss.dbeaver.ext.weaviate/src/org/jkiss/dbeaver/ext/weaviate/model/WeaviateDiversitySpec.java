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
 * returns the same thing several times over. MMR re-picks from the candidates, trading a little
 * relevance for results that differ from each other.
 * <p>
 * <b>The query's own limit is the candidate pool.</b> MMR chooses {@code limit} results out of the
 * rows the search would otherwise have returned, so it can only do something when the read fetches
 * more rows than MMR returns. Measured against a 13-row fixture: fetching 13 and asking MMR for 5
 * gives one row from each cluster, while fetching 5 and asking MMR for 5 gives the same five
 * near-duplicates in a different order -- there was nothing left to choose from. The server
 * refuses the inverse outright: <em>MMR limit (13) cannot be larger than the query limit (5)</em>.
 * <p>
 * Vector modes only, plus hybrid -- it re-ranks by vector distance between candidates, so there is
 * nothing for it to do on a pure keyword search. See
 * {@link WeaviateQueryMode#supportsDiversity()}.
 *
 * @param limit   how many results to return after diversifying. Required: the server answers
 *                <em>MMR limit must be at least 1</em> if it is left out
 * @param balance 0 for maximum diversity, 1 for pure relevance -- at 1 the results are the ones
 *                the search would have returned anyway. Null leaves it to the server
 */
public record WeaviateDiversitySpec(int limit, @Nullable Float balance) {

    /** The server's own floor; anything below it is refused rather than defaulted. */
    public static final int MIN_LIMIT = 1;

    public WeaviateDiversitySpec {
        if (limit < MIN_LIMIT) {
            throw new IllegalArgumentException("MMR limit must be at least " + MIN_LIMIT);
        }
    }

    @NotNull
    public Diversity toClientType() {
        return Diversity.mmr(b -> {
            b.limit(limit);
            if (balance != null) {
                b.balance(balance);
            }
            return b;
        });
    }
}
