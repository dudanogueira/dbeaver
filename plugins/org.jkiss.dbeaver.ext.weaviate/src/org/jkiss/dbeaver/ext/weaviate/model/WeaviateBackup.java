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

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One backup, as the server describes it.
 * <p>
 * Plain JDK so it can cross into the UI bundle, whose classloader cannot see the shaded client.
 * <p>
 * Several fields are genuinely optional, and not for want of asking. The list endpoint reports
 * the collections a backup holds; the per-backup status endpoint does not, so {@code collections}
 * is empty for a backup reached by polling rather than by listing. {@code path} is the other way
 * round. Neither absence is an error.
 */
public record WeaviateBackup(
    @NotNull String id,
    @NotNull String backend,
    @Nullable String path,
    @NotNull List<String> collections,
    @NotNull WeaviateBackupStatus status,
    @Nullable String error,
    @Nullable OffsetDateTime startedAt,
    @Nullable OffsetDateTime completedAt,
    @Nullable Float sizeGiB
) {

    public WeaviateBackup {
        if (id.isBlank()) {
            throw new IllegalArgumentException("Backup id is required");
        }
        collections = collections == null ? List.of() : List.copyOf(collections);
    }

    /**
     * Size in bytes, or null when the server did not report one.
     * <p>
     * The wire field is a float count of <em>gibibytes</em>, which for a small backup arrives as
     * {@code 2.796e-05}. Nothing readable can be built from that without converting first.
     */
    @Nullable
    public Long getSizeBytes() {
        return sizeGiB == null ? null : (long) (sizeGiB * 1024L * 1024L * 1024L);
    }

    /** How long it took, or null while it is still running. */
    @Nullable
    public java.time.Duration getDuration() {
        return startedAt == null || completedAt == null
            ? null
            : java.time.Duration.between(startedAt, completedAt);
    }
}
