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
import java.util.Set;

/**
 * Saying what moved, without saying it forever.
 */
public class WeaviatePlacementTrackerTest extends DBeaverUnitTest {

    @Test
    public void theFirstLookReportsNothing() {
        // Everything would be new, and highlighting everything highlights nothing.
        WeaviatePlacementTracker tracker = new WeaviatePlacementTracker();
        Assertions.assertTrue(
            tracker.note("Orders/abc", List.of("node-1", "node-2")).isEmpty());
    }

    @Test
    public void anAddedReplicaIsReported() {
        WeaviatePlacementTracker tracker = new WeaviatePlacementTracker();
        tracker.note("Orders/abc", List.of("node-1"));
        Assertions.assertEquals(Set.of("node-2"),
            tracker.note("Orders/abc", List.of("node-1", "node-2")));
    }

    @Test
    public void theMarkClearsItselfOnTheNextLook() {
        // The whole point of "since the last look": a second refresh must not keep shouting.
        WeaviatePlacementTracker tracker = new WeaviatePlacementTracker();
        tracker.note("Orders/abc", List.of("node-1"));
        tracker.note("Orders/abc", List.of("node-1", "node-2"));
        Assertions.assertTrue(
            tracker.note("Orders/abc", List.of("node-1", "node-2")).isEmpty());
    }

    @Test
    public void aRemovedReplicaIsNotReportedAsAnArrival() {
        // The source side of a move. It leaves; it does not arrive.
        WeaviatePlacementTracker tracker = new WeaviatePlacementTracker();
        tracker.note("Orders/abc", List.of("node-1", "node-2"));
        Assertions.assertTrue(tracker.note("Orders/abc", List.of("node-2")).isEmpty());
    }

    @Test
    public void aMoveReportsOnlyTheDestination() {
        WeaviatePlacementTracker tracker = new WeaviatePlacementTracker();
        tracker.note("Orders/abc", List.of("node-1"));
        Assertions.assertEquals(Set.of("node-2"), tracker.note("Orders/abc", List.of("node-2")));
    }

    @Test
    public void keysAreIndependent() {
        WeaviatePlacementTracker tracker = new WeaviatePlacementTracker();
        tracker.note("Orders/abc", List.of("node-1"));
        tracker.note("Orders/def", List.of("node-1"));
        Assertions.assertEquals(Set.of("node-2"),
            tracker.note("Orders/abc", List.of("node-1", "node-2")));
        Assertions.assertTrue(tracker.note("Orders/def", List.of("node-1")).isEmpty());
    }

    @Test
    public void clearingMakesTheNextLookAFirstLook() {
        WeaviatePlacementTracker tracker = new WeaviatePlacementTracker();
        tracker.note("Orders/abc", List.of("node-1"));
        tracker.clear();
        Assertions.assertTrue(
            tracker.note("Orders/abc", List.of("node-1", "node-2")).isEmpty());
    }
}
