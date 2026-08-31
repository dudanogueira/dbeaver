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

import org.jkiss.dbeaver.model.struct.DBSObjectState;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Shard status is resolved from a name rather than from the client's enum, and this is why.
 */
public class WeaviateShardStatusTest extends DBeaverUnitTest {

    /**
     * Every status the server can report, from {@code entities/storagestate/status.go}.
     * <p>
     * The list is the point of the test. The bundled client models this field as
     * {@code VectorIndexingStatus}, which has three constants -- READONLY, INDEXING, READY -- so
     * Gson maps the other three to null and the shard reaches the tree with no status at all. On a
     * server with lazily-loaded tenant shards that is nearly every shard, and it fails silently:
     * no exception, no log line, just a shard with no label, no colour, and no place in any action
     * that filters on status.
     */
    private static final List<String> SERVER_STATUSES = List.of(
        "READONLY", "INDEXING", "LOADING", "LAZY_LOADING", "READY", "SHUTDOWN");

    @Test
    public void everyStatusTheServerReportsIsRecognised() {
        for (String name : SERVER_STATUSES) {
            Assertions.assertNotEquals(WeaviateShardStatus.UNKNOWN,
                WeaviateShardStatus.fromName(name),
                () -> name + " is a status the server reports and this plugin cannot name it");
        }
    }

    @Test
    public void lazyLoadingIsNotLost() {
        // The specific gap: the client enum has no LAZY_LOADING, so every shard of an inactive
        // tenant arrives with a null status.
        Assertions.assertEquals(
            WeaviateShardStatus.LAZY_LOADING, WeaviateShardStatus.fromName("LAZY_LOADING"));
    }

    @Test
    public void anUnknownNameIsUnknownRatherThanNull() {
        for (String odd : new String[]{null, "", "   ", "SOMETHING_NEW"}) {
            Assertions.assertEquals(WeaviateShardStatus.UNKNOWN,
                WeaviateShardStatus.fromName(odd),
                () -> "expected UNKNOWN for [" + odd + "]");
        }
    }

    @Test
    public void onlyReadyAndReadonlyAreOffered() {
        // The server's ValidateStatus also accepts INDEXING and SHUTDOWN, but asking for those is
        // asking it to pretend rather than to do something, so they are not offered as targets.
        for (WeaviateShardStatus status : WeaviateShardStatus.values()) {
            boolean expected =
                status == WeaviateShardStatus.READY || status == WeaviateShardStatus.READONLY;
            Assertions.assertEquals(expected, status.isSettable(),
                () -> status + " settability");
        }
    }

    @Test
    public void everyStatusCarriesItsOwnOverlay() {
        // Colour is the only thing distinguishing states in a list of thousands, so two statuses
        // sharing an overlay would make them indistinguishable in the tree.
        Set<Object> overlays = new HashSet<>();
        for (WeaviateShardStatus status : WeaviateShardStatus.values()) {
            if (status == WeaviateShardStatus.UNKNOWN) {
                continue;
            }
            DBSObjectState state = status.getObjectState();
            Assertions.assertNotNull(state.getOverlayImage(),
                () -> status + " should carry an overlay");
            Assertions.assertTrue(overlays.add(state.getOverlayImage()),
                () -> status + " shares its overlay with another status");
        }
    }

    @Test
    public void unknownDrawsNothing() {
        // Better a shard with no marker than one wearing a state it is not in.
        Assertions.assertNull(
            WeaviateShardStatus.UNKNOWN.getObjectState().getOverlayImage());
    }

    @Test
    public void theOppositeOfEachSettableStateIsTheOther() {
        Assertions.assertEquals(WeaviateShardStatus.READY, WeaviateShardStatus.READONLY.opposite());
        Assertions.assertEquals(WeaviateShardStatus.READONLY, WeaviateShardStatus.READY.opposite());
    }
}
