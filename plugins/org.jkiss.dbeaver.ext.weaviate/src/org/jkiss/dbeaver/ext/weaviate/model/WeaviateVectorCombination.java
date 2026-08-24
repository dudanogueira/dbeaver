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

import io.weaviate.client6.v1.api.collections.query.Target;
import org.jkiss.code.NotNull;

/**
 * How the per-target scores of a multi-target vector search are combined into one ranking --
 * what the Weaviate docs call the <em>join strategy</em>, and what the Query panel calls it too.
 * The type keeps the client's own name so the mirroring below is obvious.
 * <p>
 * Mirrors io.weaviate.client6.v1.api.collections.query.Target$CombinationMethod. Wrapped here so
 * consumers (e.g. the UI bundle) don't need direct access to the shaded client classes packaged
 * inside the model bundle's lib/.
 */
public enum WeaviateVectorCombination {
    // Declaration order is the order of the join-strategy dropdown, and MINIMUM is first because
    // it is what Weaviate itself falls back to when a multi-target search names no strategy.
    MIN("Minimum", Target.CombinationMethod.MIN, false),
    SUM("Sum", Target.CombinationMethod.SUM, false),
    AVERAGE("Average", Target.CombinationMethod.AVERAGE, false),
    RELATIVE_SCORE("Relative score", Target.CombinationMethod.RELATIVE_SCORE, true),
    MANUAL_WEIGHTS("Manual weights", Target.CombinationMethod.MANUAL_WEIGHTS, true);

    private final String label;
    private final Target.CombinationMethod clientType;
    private final boolean usesWeights;

    WeaviateVectorCombination(String label, Target.CombinationMethod clientType, boolean usesWeights) {
        this.label = label;
        this.clientType = clientType;
        this.usesWeights = usesWeights;
    }

    @NotNull
    public String getLabel() {
        return label;
    }

    public Target.CombinationMethod toClientType() {
        return clientType;
    }

    /**
     * Whether a per-target weight means anything under this strategy.
     * <p>
     * Only the two that scale the targets against one another: manual weights multiplies each
     * target's score by its weight, and relative score normalises first and then does the same.
     * Minimum, sum and average take the targets as they come, so a weight there would be
     * silently ignored -- the panel greys the field out rather than accepting a number that
     * changes nothing.
     */
    public boolean usesWeights() {
        return usesWeights;
    }
}
