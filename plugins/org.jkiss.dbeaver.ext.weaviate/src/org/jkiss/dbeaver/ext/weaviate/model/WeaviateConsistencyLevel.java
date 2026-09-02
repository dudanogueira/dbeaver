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

import io.weaviate.client6.v1.api.collections.query.ConsistencyLevel;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

/**
 * How many replicas must answer before a read is returned.
 * <p>
 * Only means anything on a collection with a replication factor above one -- with a single replica
 * every level is the same read. It became worth exposing when replica movement did: the number of
 * replicas holding a shard is now something an operator changes on purpose, and reading at ONE
 * versus QUORUM is how they see the effect of having done so.
 * <p>
 * Wrapped rather than used directly so the UI bundle can name it; its classloader cannot see the
 * shaded client. Same reason as {@link WeaviateHybridFusion}.
 */
public enum WeaviateConsistencyLevel {

    /** One replica answers. Fastest, and may miss a write that has not reached that replica. */
    ONE(ConsistencyLevel.ONE, "One"),
    /** More than half the replicas answer. The usual choice: consistent without waiting for all. */
    QUORUM(ConsistencyLevel.QUORUM, "Quorum"),
    /** Every replica answers. Slowest, and fails outright while any replica is down. */
    ALL(ConsistencyLevel.ALL, "All");

    private final ConsistencyLevel clientType;
    private final String label;

    WeaviateConsistencyLevel(@NotNull ConsistencyLevel clientType, @NotNull String label) {
        this.clientType = clientType;
        this.label = label;
    }

    @NotNull
    public String getLabel() {
        return label;
    }

    @NotNull
    public ConsistencyLevel toClientType() {
        return clientType;
    }

    /** Resolves a stored name, tolerating one this build does not know. */
    @Nullable
    public static WeaviateConsistencyLevel fromName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (WeaviateConsistencyLevel level : values()) {
            if (level.name().equalsIgnoreCase(name)) {
                return level;
            }
        }
        return null;
    }
}
