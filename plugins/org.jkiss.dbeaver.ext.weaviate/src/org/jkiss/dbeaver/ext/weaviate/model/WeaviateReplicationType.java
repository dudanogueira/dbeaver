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

import java.util.Locale;

/**
 * What a replica movement does with the source copy.
 * <p>
 * The difference is not cosmetic and the dialog has to say so: a copy leaves the source in place
 * and so raises that shard's replication factor by one, while a move takes it away and leaves the
 * factor unchanged. A shard's factor is its own and may exceed the collection's.
 */
public enum WeaviateReplicationType {

    COPY("Copy", "Leaves the source replica in place, so this shard gains a replica."),
    MOVE("Move", "Removes the source replica once the target is caught up."),
    UNKNOWN("Unknown", "");

    private final String label;
    private final String explanation;

    WeaviateReplicationType(@NotNull String label, @NotNull String explanation) {
        this.label = label;
        this.explanation = explanation;
    }

    @NotNull
    public String getLabel() {
        return label;
    }

    @NotNull
    public String getExplanation() {
        return explanation;
    }

    /**
     * The consequence worth warning about before a copy.
     * <p>
     * Raising the replication factor can make quorum harder rather than easier: going from three
     * replicas to four moves the quorum from two nodes to three, so the shard becomes less
     * available, not more. Worth saying out loud, since "add a replica" reads as safety.
     */
    @Nullable
    public String getQuorumWarning(int currentReplicas) {
        if (this != COPY || currentReplicas < 1) {
            return null;
        }
        int after = currentReplicas + 1;
        int quorumNow = currentReplicas / 2 + 1;
        int quorumAfter = after / 2 + 1;
        if (quorumAfter > quorumNow) {
            return "This shard has " + currentReplicas + " replica(s); a copy makes it " + after
                + ", which raises the quorum from " + quorumNow + " to " + quorumAfter
                + " node(s). More replicas, but less tolerance of a node being down.";
        }
        return null;
    }

    @NotNull
    public static WeaviateReplicationType fromName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return UNKNOWN;
        }
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "COPY" -> COPY;
            case "MOVE" -> MOVE;
            default -> UNKNOWN;
        };
    }
}
