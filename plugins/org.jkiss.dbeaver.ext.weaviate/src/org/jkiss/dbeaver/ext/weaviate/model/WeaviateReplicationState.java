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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.model.struct.DBSObjectState;

import java.util.Locale;

/**
 * The states a replica-movement operation passes through.
 * <p>
 * From {@code cluster/proto/api/shard_requests.go}. A copy runs
 * REGISTERED, HYDRATING, FINALIZING, INTEGRATING, READY; a move inserts DEHYDRATING before READY
 * while the source replica is removed. CANCELLED is terminal and cannot be resumed.
 * <p>
 * {@code INTEGRATING} arrived in 1.38 and is the one the bundled client does not have -- its
 * enum stops at six, so Gson maps this state to null and a live copy shows nothing during a phase
 * it demonstrably occupies. Observed on a 1.39.0 cluster:
 * <pre>
 * REGISTERED -&gt; HYDRATING -&gt; FINALIZING -&gt; INTEGRATING -&gt; READY
 * </pre>
 * Resolved from a string with an unknown-tolerant fallback, so a state a later release adds is
 * displayed as it arrived rather than becoming null or throwing.
 */
public enum WeaviateReplicationState {

    /** Accepted and queued; no work has started. */
    REGISTERED("Registered", 1, new DBSObjectState("Registered", DBIcon.OVER_UNKNOWN)),
    /** The data is being copied to the target node. */
    HYDRATING("Hydrating", 2, new DBSObjectState("Hydrating", DBIcon.OVER_LAMP)),
    /** The bulk copy is done and the target is catching up on writes made during it. */
    FINALIZING("Finalizing", 3, new DBSObjectState("Finalizing", DBIcon.OVER_LAMP)),
    /** The new replica is being brought into the shard, once every node agrees it is caught up. */
    INTEGRATING("Integrating", 4, new DBSObjectState("Integrating", DBIcon.OVER_ADD)),
    /** A move only: the source replica is being removed. */
    DEHYDRATING("Dehydrating", 5, new DBSObjectState("Dehydrating", DBIcon.OVER_LAMP)),
    /** Finished. */
    READY("Ready", 6, DBSObjectState.ACTIVE),
    /** Stopped, by request or after too many errors. Cannot be resumed. */
    CANCELLED("Cancelled", 0, new DBSObjectState("Cancelled", DBIcon.OVER_ERROR)),
    /** A state newer than this build. Shown as the server sent it. */
    UNKNOWN("Unknown", 0, DBSObjectState.NORMAL);

    private final String label;
    private final int rank;
    private final DBSObjectState state;

    WeaviateReplicationState(@NotNull String label, int rank, @NotNull DBSObjectState state) {
        this.label = label;
        this.rank = rank;
        this.state = state;
    }

    @NotNull
    public String getLabel() {
        return label;
    }

    /**
     * How far along the happy path this state is, matching the server's own {@code StateRank}.
     * <p>
     * Note DEHYDRATING ranks <em>below</em> READY even though it comes after INTEGRATING: a move
     * is further along than a copy at the same point. CANCELLED ranks 0 and so never satisfies a
     * "has it got at least this far" comparison, which is the server's intent.
     */
    public int getRank() {
        return rank;
    }

    /** Nothing further will happen without a new request. */
    public boolean isTerminal() {
        return this == READY || this == CANCELLED;
    }

    /** The server is still working on it. */
    public boolean isInFlight() {
        return !isTerminal() && this != UNKNOWN;
    }

    @NotNull
    public DBSObjectState getObjectState() {
        return state;
    }

    @NotNull
    public static WeaviateReplicationState fromName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return UNKNOWN;
        }
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "REGISTERED" -> REGISTERED;
            case "HYDRATING" -> HYDRATING;
            case "FINALIZING" -> FINALIZING;
            case "INTEGRATING" -> INTEGRATING;
            case "DEHYDRATING" -> DEHYDRATING;
            case "READY" -> READY;
            // The server spells it with two Ls; the client's constant uses one, so accept both.
            case "CANCELLED", "CANCELED" -> CANCELLED;
            default -> UNKNOWN;
        };
    }

    /** Whether this build knows the state, as opposed to merely being able to show it. */
    public static boolean isKnown(@Nullable String name) {
        return fromName(name) != UNKNOWN;
    }
}
