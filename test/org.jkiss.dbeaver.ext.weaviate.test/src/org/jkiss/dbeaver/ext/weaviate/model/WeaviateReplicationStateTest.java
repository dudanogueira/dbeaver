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
 * The replication state vocabulary, and why it is resolved from a string.
 */
public class WeaviateReplicationStateTest extends DBeaverUnitTest {

    /**
     * Every state the server defines, from {@code cluster/proto/api/shard_requests.go}.
     * <p>
     * Seven. The bundled client has six -- it is missing {@code INTEGRATING}, which the server has
     * emitted since 1.38 -- and because that enum is plain Gson rather than the throwing kind, a
     * live operation simply reports a null state while passing through it. Observed on 1.39.0:
     * REGISTERED, HYDRATING, FINALIZING, INTEGRATING, READY.
     */
    private static final List<String> SERVER_STATES = List.of(
        "REGISTERED", "HYDRATING", "FINALIZING", "INTEGRATING", "DEHYDRATING", "READY", "CANCELLED");

    @Test
    public void everyStateTheServerDefinesIsKnown() {
        for (String state : SERVER_STATES) {
            Assertions.assertTrue(WeaviateReplicationState.isKnown(state),
                () -> state + " is a state the server emits and this plugin cannot name it");
        }
    }

    @Test
    public void integratingIsNotLost() {
        // The specific gap, and the reason this does not go through the client.
        Assertions.assertEquals(WeaviateReplicationState.INTEGRATING,
            WeaviateReplicationState.fromName("INTEGRATING"));
    }

    @Test
    public void bothSpellingsOfCancelledResolve() {
        // The server writes CANCELLED; the client's own constant is CANCELED.
        Assertions.assertEquals(WeaviateReplicationState.CANCELLED,
            WeaviateReplicationState.fromName("CANCELLED"));
        Assertions.assertEquals(WeaviateReplicationState.CANCELLED,
            WeaviateReplicationState.fromName("CANCELED"));
    }

    @Test
    public void aStateFromALaterReleaseIsTolerated() {
        for (String odd : new String[]{null, "", "   ", "TRANSCENDING"}) {
            Assertions.assertEquals(WeaviateReplicationState.UNKNOWN,
                WeaviateReplicationState.fromName(odd),
                () -> "expected UNKNOWN for [" + odd + "]");
        }
    }

    @Test
    public void rankFollowsTheServersOrdering() {
        // Straight out of the server's StateRank. DEHYDRATING sits below READY even though it
        // comes after INTEGRATING: a move at that point is further along than a copy is.
        Assertions.assertEquals(1, WeaviateReplicationState.REGISTERED.getRank());
        Assertions.assertEquals(2, WeaviateReplicationState.HYDRATING.getRank());
        Assertions.assertEquals(3, WeaviateReplicationState.FINALIZING.getRank());
        Assertions.assertEquals(4, WeaviateReplicationState.INTEGRATING.getRank());
        Assertions.assertEquals(5, WeaviateReplicationState.DEHYDRATING.getRank());
        Assertions.assertEquals(6, WeaviateReplicationState.READY.getRank());
        Assertions.assertTrue(
            WeaviateReplicationState.DEHYDRATING.getRank() < WeaviateReplicationState.READY.getRank());
    }

    @Test
    public void cancelledNeverCountsAsProgress() {
        // Rank 0, so "has it got at least as far as X" is false for every happy-path state.
        Assertions.assertEquals(0, WeaviateReplicationState.CANCELLED.getRank());
        Assertions.assertTrue(WeaviateReplicationState.CANCELLED.getRank()
            < WeaviateReplicationState.REGISTERED.getRank());
    }

    @Test
    public void terminalStatesAreTheTwoThatStop() {
        Assertions.assertTrue(WeaviateReplicationState.READY.isTerminal());
        Assertions.assertTrue(WeaviateReplicationState.CANCELLED.isTerminal());
        for (String name : List.of("REGISTERED", "HYDRATING", "FINALIZING", "INTEGRATING",
            "DEHYDRATING")) {
            WeaviateReplicationState state = WeaviateReplicationState.fromName(name);
            Assertions.assertFalse(state.isTerminal(), name);
            Assertions.assertTrue(state.isInFlight(), name);
        }
        // An unrecognised state is not claimed to be in flight; nothing is known about it.
        Assertions.assertFalse(WeaviateReplicationState.UNKNOWN.isInFlight());
    }

    @Test
    public void everyKnownStateCarriesItsOwnOverlay() {
        // Colour is what distinguishes a stuck operation from a finished one in a long list.
        java.util.Set<Object> overlays = new java.util.HashSet<>();
        for (String name : SERVER_STATES) {
            WeaviateReplicationState state = WeaviateReplicationState.fromName(name);
            Assertions.assertNotNull(state.getObjectState().getOverlayImage(),
                () -> name + " should carry an overlay");
        }
        Assertions.assertNull(WeaviateReplicationState.UNKNOWN.getObjectState().getOverlayImage(),
            "an unknown state should wear no marker rather than a wrong one");
        overlays.clear();
    }

    @Test
    public void copyWarnsWhenItWouldMakeQuorumHarder() {
        // Three replicas to four moves quorum from 2 to 3: more copies, less tolerance.
        String warning = WeaviateReplicationType.COPY.getQuorumWarning(3);
        Assertions.assertNotNull(warning);
        Assertions.assertTrue(warning.contains("2"), warning);
        Assertions.assertTrue(warning.contains("3"), warning);
        // Two to three raises the count without raising the quorum, so nothing to warn about.
        Assertions.assertNull(WeaviateReplicationType.COPY.getQuorumWarning(2));
        Assertions.assertNull(WeaviateReplicationType.MOVE.getQuorumWarning(3));
    }

    @Test
    public void transferTypesResolve() {
        Assertions.assertEquals(WeaviateReplicationType.COPY,
            WeaviateReplicationType.fromName("COPY"));
        Assertions.assertEquals(WeaviateReplicationType.MOVE,
            WeaviateReplicationType.fromName("MOVE"));
        Assertions.assertEquals(WeaviateReplicationType.UNKNOWN,
            WeaviateReplicationType.fromName("TELEPORT"));
    }
}
