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
import org.jkiss.dbeaver.model.DBPImage;
import org.jkiss.dbeaver.model.DBPImageProvider;
import org.jkiss.dbeaver.model.DBPStatefulObject;
import org.jkiss.dbeaver.model.DBPToolTipObject;
import org.jkiss.dbeaver.model.meta.Association;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObjectState;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * One replica-movement operation, as a navigator node.
 * <p>
 * The label carries the whole story -- what is moving, between which nodes, and where it has got
 * to -- because that is the question this row exists to answer, and reading it should not require
 * opening a properties panel.
 */
public class WeaviateReplicationOp extends WeaviateReplicationEntry
    implements DBPImageProvider, DBPToolTipObject, DBPStatefulObject {

    private static final DateTimeFormatter WHEN =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final WeaviateReplicationRest.OperationInfo op;

    public WeaviateReplicationOp(
        @NotNull WeaviateDataSource dataSource, @NotNull WeaviateReplicationRest.OperationInfo op
    ) {
        super(dataSource);
        this.op = op;
    }

    @NotNull
    public WeaviateReplicationRest.OperationInfo getOperationInfo() {
        return op;
    }

    @NotNull
    public String getOperationId() {
        return op.id();
    }

    /**
     * What is moving and where to: {@code Orders/abc123 \u2192 weaviate-1}.
     * <p>
     * Deliberately not the whole operation. Collection, shard, source, target, type and state are
     * each a viewable property, so the navigator already gives them their own columns; repeating
     * all six in the label produced a row long enough to need scrolling and still told nobody
     * anything the grid was not already showing.
     * <p>
     * What stays is what identifies the row and what a reader is actually looking for: which shard,
     * and where it is going. The state is carried by the overlay -- green when ready, red when
     * cancelled, orange while in flight -- and spelled out in its own column. Only a pending
     * cancel or delete is called out in words, because that is transient and has no column.
     */
    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        StringBuilder sb = new StringBuilder()
            .append(op.collection()).append('/').append(op.shard())
            .append(" \u2192 ").append(op.targetNode());
        if (op.scheduledForCancel()) {
            sb.append("  (cancelling)");
        } else if (op.scheduledForDelete()) {
            sb.append("  (deleting)");
        }
        return sb.toString();
    }

    @NotNull
    @Property(viewable = true, order = 2)
    public String getCollection() {
        return op.collection();
    }

    @NotNull
    @Property(viewable = true, order = 3)
    public String getShard() {
        return op.shard();
    }

    @NotNull
    @Property(viewable = true, order = 4)
    public String getSourceNode() {
        return op.sourceNode();
    }

    @NotNull
    @Property(viewable = true, order = 5)
    public String getTargetNode() {
        return op.targetNode();
    }

    @NotNull
    @Property(viewable = true, order = 6)
    public String getType() {
        return op.type();
    }

    @NotNull
    @Property(viewable = true, order = 7)
    public String getState() {
        return op.state();
    }

    @Nullable
    @Property(viewable = true, order = 8)
    public String getStarted() {
        return op.whenStartedUnixMs() > 0
            ? WHEN.format(Instant.ofEpochMilli(op.whenStartedUnixMs())) : null;
    }

    @Property(viewable = true, order = 9)
    public int getErrorCount() {
        return op.allErrors().size();
    }

    @NotNull
    public WeaviateReplicationState getReplicationState() {
        return WeaviateReplicationState.fromName(op.state());
    }

    /**
     * Whether the server would accept a cancel.
     * <p>
     * Mirrors its predicate rather than guessing: once the replica has joined the sharding state
     * the operation is marked {@code uncancelable} and a cancel answers 409. Offering a button
     * that can only fail is worse than not offering it, and the server hands us the flag for
     * exactly this purpose -- the bundled client drops it.
     */
    public boolean canCancel() {
        return !op.uncancelable()
            && !op.scheduledForCancel()
            && !getReplicationState().isTerminal();
    }

    /**
     * Whether the server would accept a delete.
     * <p>
     * Deleting cancels first, so it carries the same restriction with one exception the server
     * makes explicit: a READY operation is always deletable, because there is nothing left to stop.
     */
    public boolean canDelete() {
        if (op.scheduledForDelete()) {
            return false;
        }
        return !op.uncancelable() || getReplicationState() == WeaviateReplicationState.READY;
    }

    /** The states it has been through, as rows. */
    @NotNull
    @Association
    @Override
    public List<WeaviateReplicationStep> getSteps(@NotNull DBRProgressMonitor monitor) {
        List<WeaviateReplicationStep> steps = new ArrayList<>();
        for (WeaviateReplicationRest.StatusInfo status : op.statusHistory()) {
            steps.add(new WeaviateReplicationStep(this, status, false));
        }
        if (op.status() != null) {
            steps.add(new WeaviateReplicationStep(this, op.status(), true));
        }
        return steps;
    }

    @Nullable
    @Override
    public String getDescription() {
        StringBuilder sb = new StringBuilder(op.type())
            .append(" from ").append(op.sourceNode())
            .append(", ").append(getReplicationState().getLabel().toLowerCase(
                java.util.Locale.ROOT));
        List<WeaviateReplicationRest.ErrorInfo> errors = op.allErrors();
        if (!errors.isEmpty()) {
            sb.append(" -- ").append(errors.size()).append(" error(s), most recently: ")
                .append(errors.get(errors.size() - 1).message());
        }
        return sb.toString();
    }

    @Nullable
    @Override
    public String getObjectToolTip() {
        StringBuilder sb = new StringBuilder(op.type())
            .append(" of ").append(op.collection()).append('/').append(op.shard())
            .append(" from ").append(op.sourceNode()).append(" to ").append(op.targetNode());
        if (op.uncancelable()) {
            sb.append("\nPast the point where it can be cancelled.");
        }
        return sb.toString();
    }

    @NotNull
    @Override
    public DBPImage getObjectImage() {
        return DBIcon.TREE_PARTITION;
    }

    @NotNull
    @Override
    public DBSObjectState getObjectState() {
        return getReplicationState().getObjectState();
    }

    @Override
    public void refreshObjectState(@NotNull DBRProgressMonitor monitor) {
        // Carried by the snapshot this node was built from; the folder re-reads on refresh.
    }
}
