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
 * A shard's state, as the server reports it.
 * <p>
 * The full set is {@code entities/storagestate/status.go}, and it is six values, not the three the
 * bundled client models:
 * <pre>
 * READONLY      writes refused; what a shard is set to before maintenance
 * INDEXING      building its index, still serving
 * LOADING       coming up
 * LAZY_LOADING  on disk, not yet loaded -- what an inactive tenant's shard reports
 * READY         serving reads and writes
 * SHUTDOWN      stopped
 * </pre>
 * Reading and writing are not symmetric here, and the asymmetry is the reason this type keeps
 * {@link #isSettable()} separate from the list above. The server's own {@code ValidateStatus}
 * accepts READONLY, INDEXING, READY and SHUTDOWN on a write and rejects LOADING and LAZY_LOADING.
 * Of the four it accepts, only two are worth offering: asking for INDEXING or SHUTDOWN is asking
 * the server to pretend, not to do something, so the plugin offers READY and READONLY and nothing
 * else.
 * <p>
 * What a shard currently reports does <em>not</em> restrict what it can be set to. A LAZY_LOADING
 * shard takes a READONLY write and reports READONLY afterwards, verified against 1.39.0. So the
 * transient states are ordinary sources for a change, not obstacles to one.
 */
public enum WeaviateShardStatus {

    /** Serving reads and writes. */
    READY("Ready", DBSObjectState.ACTIVE),
    /** Serving reads only. What a shard is set to before maintenance. */
    READONLY("Read-only", new DBSObjectState("Read-only", DBIcon.OVER_ERROR)),
    /** Building its index. Still serving, so this is work in progress rather than a fault. */
    INDEXING("Indexing", new DBSObjectState("Indexing", DBIcon.OVER_ADD)),
    /** Coming up. */
    LOADING("Loading", new DBSObjectState("Loading", DBIcon.OVER_LAMP)),
    /** On disk and not loaded. What the shard of an inactive tenant reports. */
    LAZY_LOADING("Lazy loading", new DBSObjectState("Lazy loading", DBIcon.OVER_UNKNOWN)),
    /** Stopped. */
    SHUTDOWN("Shut down", new DBSObjectState("Shut down", DBIcon.OVER_RED_LAMP)),
    /** Not reported, or a value newer than this plugin. Carries no overlay rather than a wrong one. */
    UNKNOWN("Unknown", DBSObjectState.NORMAL);

    private final String label;
    private final DBSObjectState state;

    WeaviateShardStatus(@NotNull String label, @NotNull DBSObjectState state) {
        this.label = label;
        this.state = state;
    }

    @NotNull
    public String getLabel() {
        return label;
    }

    /**
     * The overlay this status draws in the tree.
     * <p>
     * Green for READY and red for READONLY are the two that carry meaning at a glance: one is
     * healthy, the other is a deliberate restriction someone put there. The rest are transitional
     * and share the warm end of the palette -- distinguished by glyph, since the icon set has one
     * orange and several shapes.
     */
    @NotNull
    public DBSObjectState getObjectState() {
        return state;
    }

    /** Whether asking the server for this state is a request it will accept, and worth offering. */
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
            case "INDEXING" -> INDEXING;
            case "LOADING" -> LOADING;
            case "LAZY_LOADING", "LAZYLOADING", "LAZY-LOADING" -> LAZY_LOADING;
            case "SHUTDOWN", "SHUT_DOWN" -> SHUTDOWN;
            default -> UNKNOWN;
        };
    }
}
