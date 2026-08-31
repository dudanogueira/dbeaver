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
 * A shard's read/write state, which is the one thing about a shard that can be changed.
 * <p>
 * Not to be confused with the vector indexing status the cluster API reports for the same shard.
 * They share the value READY and mean different things, and only this one is settable:
 * <ul>
 *   <li><b>this</b> comes from {@code GET /v1/schema/{collection}/shards} and is READY or
 *       READONLY, nothing else</li>
 *   <li>the indexing status comes from the nodes API and includes LAZY_LOADING and INDEXING,
 *       which describe work the server is doing and which no request can change</li>
 * </ul>
 * Keeping them apart is the whole reason this type exists: an action offering to make a
 * LAZY_LOADING shard READY would silently do nothing.
 */
public enum WeaviateShardStatus {

    /** Serving reads and writes. */
    READY("Ready"),
    /** Serving reads only. What a shard is set to before maintenance. */
    READONLY("Read-only"),
    /** Not reported, or a value this plugin does not know. */
    UNKNOWN("Unknown");

    private final String label;

    WeaviateShardStatus(@NotNull String label) {
        this.label = label;
    }

    @NotNull
    public String getLabel() {
        return label;
    }

    /** Whether asking the server for this state is a request it will accept. */
    public boolean isSettable() {
        return this == READY || this == READONLY;
    }

    /** The other settable state, for an action that names the change it will make. */
    @NotNull
    public WeaviateShardStatus opposite() {
        return this == READONLY ? READY : READONLY;
    }

    @NotNull
    public static WeaviateShardStatus fromName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return UNKNOWN;
        }
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "READY" -> READY;
            case "READONLY", "READ_ONLY" -> READONLY;
            default -> UNKNOWN;
        };
    }
}
