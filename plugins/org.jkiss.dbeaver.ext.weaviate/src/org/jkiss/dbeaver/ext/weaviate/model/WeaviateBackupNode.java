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
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPImage;
import org.jkiss.dbeaver.model.DBPImageProvider;
import org.jkiss.dbeaver.model.DBPStatefulObject;
import org.jkiss.dbeaver.model.DBPToolTipObject;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectState;
import org.jkiss.utils.ByteNumberFormat;

import java.time.OffsetDateTime;

/**
 * One backup, as a navigator node.
 * <p>
 * The state carries an overlay as well as a column, for the same reason a tenant's does: the text
 * beside the name only appears when the "Show object tips" navigator preference is on, which it
 * is not by default, whereas {@code DBNDatabaseNode#getNodeIcon} applies a stateful object's
 * overlay unconditionally.
 */
public class WeaviateBackupNode implements DBSObject, DBPImageProvider, DBPToolTipObject, DBPStatefulObject {

    private static final DBSObjectState STATE_FAILED =
        new DBSObjectState("Failed", DBIcon.OVER_ERROR);
    private static final DBSObjectState STATE_RUNNING =
        new DBSObjectState("Running", DBIcon.OVER_UNKNOWN);
    private static final DBSObjectState STATE_CANCELED =
        new DBSObjectState("Canceled", DBIcon.OVER_LOCK);

    private final WeaviateBackupBackend backend;
    private final WeaviateBackup backup;

    public WeaviateBackupNode(@NotNull WeaviateBackupBackend backend, @NotNull WeaviateBackup backup) {
        this.backend = backend;
        this.backup = backup;
    }

    @NotNull
    public WeaviateBackup getBackup() {
        return backup;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return backup.id();
    }

    @NotNull
    @Property(viewable = true, order = 2)
    public String getStatus() {
        return backup.status().getLabel();
    }

    @NotNull
    public WeaviateBackupStatus getBackupStatus() {
        return backup.status();
    }

    /**
     * The collections in the backup. Empty when this node came from a status poll rather than a
     * listing -- the list endpoint reports them and the status endpoint does not.
     */
    @NotNull
    @Property(viewable = true, order = 3)
    public String getCollections() {
        return String.join(", ", backup.collections());
    }

    /**
     * Size in human terms. The wire value is a float count of gibibytes, so a small backup arrives
     * as 2.796e-05 -- true, and unreadable.
     */
    @Nullable
    @Property(viewable = true, order = 4)
    public String getSize() {
        Long bytes = backup.getSizeBytes();
        return bytes == null ? null : new ByteNumberFormat().format(bytes);
    }

    @Nullable
    @Property(viewable = true, order = 5)
    public OffsetDateTime getStartedAt() {
        return backup.startedAt();
    }

    @Nullable
    @Property(viewable = true, order = 6)
    public OffsetDateTime getCompletedAt() {
        return backup.completedAt();
    }

    @Nullable
    @Property(viewable = true, order = 7)
    public String getError() {
        return backup.error();
    }

    @NotNull
    public WeaviateBackupBackend getBackend() {
        return backend;
    }

    @Nullable
    @Override
    public String getObjectToolTip() {
        String error = backup.error();
        return error == null ? backup.status().getLabel() : backup.status().getLabel() + ": " + error;
    }

    @NotNull
    @Override
    public DBPImage getObjectImage() {
        return DBIcon.TYPE_DOCUMENT;
    }

    /**
     * Only the states that are not "finished cleanly" are marked. A successful backup is the
     * ordinary case and a folder of hundreds of them should not be a wall of green ticks.
     */
    @NotNull
    @Override
    public DBSObjectState getObjectState() {
        return switch (backup.status()) {
            case SUCCESS -> DBSObjectState.NORMAL;
            case FAILED -> STATE_FAILED;
            case CANCELED -> STATE_CANCELED;
            default -> STATE_RUNNING;
        };
    }

    @Override
    public void refreshObjectState(@NotNull DBRProgressMonitor monitor) {
        // The state travels with the backup this node was built from, and the backend rebuilds
        // its list on refresh, so there is nothing to re-read here.
    }

    @Nullable
    @Override
    public String getDescription() {
        return backup.path();
    }

    @NotNull
    @Override
    public DBSObject getParentObject() {
        return backend;
    }

    @NotNull
    @Override
    public DBPDataSource getDataSource() {
        return backend.getDataSource();
    }

    @Override
    public boolean isPersisted() {
        return true;
    }
}
