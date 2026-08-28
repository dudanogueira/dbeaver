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
 * Where a backup or restore has got to.
 * <p>
 * Resolved from the status name rather than from the client's own {@code BackupStatus} enum, and
 * that is not a stylistic choice. The client's enum has no {@code TRANSFERRED} constant, but the
 * server emits exactly that on the restore path -- {@code usecases/backup/coordinator.go} sets it
 * once staging completes, and the descriptor is persisted and served back. Gson's default enum
 * adapter answers <b>null</b> for a name it does not know, so reading the client enum directly
 * gives a null status midway through every restore. Resolving the string ourselves cannot.
 * <p>
 * {@link #UNKNOWN} covers the same ground going forward: a newer server naming a state this
 * plugin has never heard of should show as unknown, not crash the tree.
 */
public enum WeaviateBackupStatus {

    /** Accepted, nothing moved yet. */
    STARTED("Started"),
    /** Copying data to the backend. */
    TRANSFERRING("Transferring"),
    /** Data is at the backend. On restore this is followed by the schema apply. */
    TRANSFERRED("Transferred"),
    /** Applying schema changes. The server refuses to cancel during this phase. */
    FINALIZING("Finalizing"),
    /** Done. */
    SUCCESS("Success"),
    /** Gave up. {@code WeaviateBackup#error()} says why. */
    FAILED("Failed"),
    /** A cancel has been claimed but has not finished unwinding. */
    CANCELLING("Cancelling"),
    /** Cancelled. The id becomes reusable, which a completed backup's does not. */
    CANCELED("Canceled"),
    /** A state this plugin does not know, or none reported at all. */
    UNKNOWN("Unknown");

    private final String label;

    WeaviateBackupStatus(@NotNull String label) {
        this.label = label;
    }

    @NotNull
    public String getLabel() {
        return label;
    }

    /**
     * Whether the server has finished with it, one way or another. This is what a poll loop stops
     * on -- everything else means "ask again".
     * <p>
     * {@link #UNKNOWN} is deliberately not terminal. An unrecognised state is far more likely to
     * be a new intermediate phase than a new ending, and treating it as terminal would report a
     * still-running backup as complete.
     */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELED;
    }

    /**
     * Whether asking the server to cancel could still achieve anything. {@code FINALIZING} is
     * excluded because the server refuses it outright -- it answers 422 "is applying schema
     * changes and cannot be cancelled".
     */
    public boolean isCancellable() {
        return this == STARTED || this == TRANSFERRING || this == TRANSFERRED;
    }

    /** Whether the backup can be restored from. Only a completed one can. */
    public boolean isRestorable() {
        return this == SUCCESS;
    }

    /**
     * Resolves a status name. Never throws and never returns null: one unrecognised backup should
     * not fail the listing it arrived in.
     */
    @NotNull
    public static WeaviateBackupStatus fromName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return UNKNOWN;
        }
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "STARTED" -> STARTED;
            case "TRANSFERRING" -> TRANSFERRING;
            case "TRANSFERRED" -> TRANSFERRED;
            case "FINALIZING" -> FINALIZING;
            case "SUCCESS" -> SUCCESS;
            case "FAILED" -> FAILED;
            case "CANCELLING" -> CANCELLING;
            case "CANCELED", "CANCELLED" -> CANCELED;
            default -> UNKNOWN;
        };
    }
}
