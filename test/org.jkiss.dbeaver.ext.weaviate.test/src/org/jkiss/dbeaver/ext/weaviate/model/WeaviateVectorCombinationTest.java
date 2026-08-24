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
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * The join strategies the Query panel offers, against the client constants they stand for.
 */
public class WeaviateVectorCombinationTest extends DBeaverUnitTest {

    /**
     * The wrapper exists so the UI bundle never touches a shaded class; if two constants mapped
     * to the same client method the panel would offer a choice that is not one.
     */
    @Test
    public void everyConstantMapsToADistinctClientMethod() {
        Set<Target.CombinationMethod> mapped = new HashSet<>();
        for (WeaviateVectorCombination combination : WeaviateVectorCombination.values()) {
            Assertions.assertNotNull(combination.toClientType(), combination + " maps to nothing");
            Assertions.assertTrue(mapped.add(combination.toClientType()),
                combination + " duplicates a client combination method");
        }
        Assertions.assertEquals(Target.CombinationMethod.values().length, mapped.size(),
            "the client gained or lost a combination method the panel does not offer");
    }

    @Test
    public void onlyRelativeScoreAndManualWeightsUseWeights() {
        Assertions.assertEquals(
            EnumSet.of(WeaviateVectorCombination.RELATIVE_SCORE, WeaviateVectorCombination.MANUAL_WEIGHTS),
            EnumSet.copyOf(java.util.Arrays.stream(WeaviateVectorCombination.values())
                .filter(WeaviateVectorCombination::usesWeights)
                .toList()));
    }

    /**
     * Minimum leads the dropdown because it is what Weaviate falls back to when a multi-target
     * search names no strategy, so the preselected entry matches the server's own default.
     */
    @Test
    public void minimumIsTheFirstOffered() {
        Assertions.assertEquals(WeaviateVectorCombination.MIN, WeaviateVectorCombination.values()[0]);
    }

    @Test
    public void everyConstantHasAReadableLabel() {
        for (WeaviateVectorCombination combination : WeaviateVectorCombination.values()) {
            String label = combination.getLabel();
            Assertions.assertFalse(label.isBlank(), combination + " has no label");
            Assertions.assertFalse(label.contains("_"), combination + " shows its raw enum name");
        }
    }
}
