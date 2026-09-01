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
import org.jkiss.dbeaver.model.DBPUniqueObject;
import org.jkiss.dbeaver.model.DBPToolTipObject;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectState;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * One state a replication operation has been in, as a child row.
 * <p>
 * The history is where a stalled operation explains itself: the server records every error it hit
 * against the state it was in at the time, and keeps them after moving on. A row per state with
 * its errors is the difference between "this move is stuck" and "this move has been failing to
 * reach the target node for ten minutes, and here is what it says".
 */
public class WeaviateReplicationStep implements DBSObject, DBPImageProvider, DBPToolTipObject,
    DBPStatefulObject, DBPUniqueObject {

    private static final DateTimeFormatter WHEN =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final WeaviateReplicationOp op;
    private final WeaviateReplicationRest.StatusInfo status;
    private final boolean current;

    public WeaviateReplicationStep(
        @NotNull WeaviateReplicationOp op,
        @NotNull WeaviateReplicationRest.StatusInfo status,
        boolean current
    ) {
        this.op = op;
        this.status = status;
        this.current = current;
    }

    /**
     * The state and when it started: {@code INTEGRATING  12:34:56}.
     * <p>
     * The error count is left to the overlay and the Errors column -- a row that had errors wears
     * the red marker either way, and saying so twice crowded the one thing this row is for. The
     * current state is marked because "where is it now" is the question the history is read to
     * answer, and it is the only row whose position is not obvious from the order.
     */
    /**
     * A name that stays put while the label changes.
     * <p>
     * The navigator reuses a tree node only when the object's class and <em>unique</em>
     * name both match ({@code DBNDatabaseNode#equalObjects}), and without this interface the
     * unique name is {@code getName()}. This label carries a marker for whichever state is current, so
     * every change made the platform treat the row as a different object: the old node was
     * dropped, a new one took its place, and whatever was expanded underneath collapsed.
     * <p>
     * Identity and label are different things. This is the identity.
     */
    @NotNull
    @Override
    public String getUniqueName() {
        return status.state();
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        StringBuilder sb = new StringBuilder(status.state());
        if (status.whenStartedUnixMs() > 0) {
            sb.append("  ").append(WHEN.format(Instant.ofEpochMilli(status.whenStartedUnixMs())));
        }
        if (current) {
            sb.append("  \u2190 now");
        }
        return sb.toString();
    }

    @NotNull
    @Property(viewable = true, order = 2)
    public String getState() {
        return status.state();
    }

    @Property(viewable = true, order = 3)
    public int getErrorCount() {
        return status.errors().size();
    }

    @Nullable
    @Override
    @Property(viewable = true, order = 4)
    public String getDescription() {
        if (!status.hasErrors()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (WeaviateReplicationRest.ErrorInfo error : status.errors()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(error.message());
        }
        return sb.toString();
    }

    @Nullable
    @Override
    public String getObjectToolTip() {
        return getDescription();
    }

    @NotNull
    @Override
    public DBPImage getObjectImage() {
        return DBIcon.TREE_ATTRIBUTE;
    }

    @NotNull
    @Override
    public DBSObjectState getObjectState() {
        // An error is worth seeing even on a state the operation has since moved past: it is why
        // the move took as long as it did.
        return status.hasErrors()
            ? new DBSObjectState("Had errors", DBIcon.OVER_ERROR)
            : WeaviateReplicationState.fromName(status.state()).getObjectState();
    }

    @Override
    public void refreshObjectState(@NotNull DBRProgressMonitor monitor) {
        // Carried by the snapshot.
    }

    @NotNull
    @Override
    public DBSObject getParentObject() {
        return op;
    }

    @NotNull
    @Override
    public DBPDataSource getDataSource() {
        return op.getDataSource();
    }

    @Override
    public boolean isPersisted() {
        return true;
    }
}
