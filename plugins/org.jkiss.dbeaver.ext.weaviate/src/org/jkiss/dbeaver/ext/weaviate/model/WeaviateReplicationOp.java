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
import org.jkiss.dbeaver.model.DBPUniqueObject;
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
    implements DBPImageProvider, DBPToolTipObject, DBPStatefulObject, DBPUniqueObject {

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
     * A name that stays put while the label changes.
     * <p>
     * The navigator reuses a tree node only when the object's class and <em>unique</em>
     * name both match ({@code DBNDatabaseNode#equalObjects}), and without this interface the
     * unique name is {@code getName()}. This label carries a marker for a pending cancel or delete, so
     * every change made the platform treat the row as a different object: the old node was
     * dropped, a new one took its place, and whatever was expanded underneath collapsed.
     * <p>
     * Identity and label are different things. This is the identity.
     */
    @NotNull
    @Override
    public String getUniqueName() {
        return op.id();
    }

    /**
     * The whole movement in one line:
     * {@code READY: MOVE DBeaverReplicaFixture/KochhPllas51: weaviate-0 \u2192 weaviate-1}.
     * <p>
     * State first, because it is what a list of these is scanned for -- which of them is still
     * going, and which is stuck. Then the type, which decides whether the source keeps its copy;
     * then what is moving, and from where to where.
     * <p>
     * The state also has a column and an overlay, so this repeats it. That is deliberate: the
     * overlay separates finished from in-flight from cancelled but cannot say which of the five
     * in-flight phases a movement is in, and the column is only visible if the properties are.
     * Repeating it here is what makes the row readable on its own.
     * <p>
     * Safe to put changing text in the label only because identity is {@link #getUniqueName()},
     * the operation's id. It was not always: while the name was the identity, a state change
     * replaced the tree node and collapsed whatever was expanded under it.
     */
    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        StringBuilder sb = new StringBuilder();
        if (!op.state().isEmpty()) {
            sb.append(op.state()).append(": ");
        }
        sb.append(op.type().isEmpty() ? "?" : op.type()).append(' ')
            .append(op.collection()).append('/').append(op.shard())
            .append(": ").append(op.sourceNode())
            .append(" \u2192 ").append(op.targetNode());
        if (op.scheduledForCancel()) {
            sb.append("  (cancelling)");
        } else if (op.scheduledForDelete()) {
            sb.append("  (deleting)");
        }
        return sb.toString();
    }

    /** Collection and shard, for anywhere a row has to be told apart from a sibling. */
    @NotNull
    public String getShardPath() {
        return op.collection() + "/" + op.shard();
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

    /**
     * When the movement started.
     * <p>
     * Read from the history rather than the operation's own {@code whenStartedUnixMs}, which a
     * 1.39.0 server never sends -- this column was empty on every row until it stopped asking for
     * that field. See {@code OperationInfo#startedAtMs}.
     */
    @Nullable
    @Property(viewable = true, order = 8)
    public String getStarted() {
        long started = op.startedAtMs();
        return started > 0 ? WHEN.format(Instant.ofEpochMilli(started)) : null;
    }

    /**
     * How long it took, or has been going.
     * <p>
     * The span between the first and last recorded states. Both ends are approximate -- the server
     * stamps neither the initial state nor the current one -- so a finished movement reads a
     * little short and a running one is measured to whenever it last changed state. Still the
     * number that answers "is this progressing or wedged", which no other column does.
     */
    @Nullable
    @Property(viewable = true, order = 9)
    public String getElapsed() {
        long started = op.startedAtMs();
        if (started <= 0) {
            return null;
        }
        long end = getReplicationState().isTerminal() ? op.lastRecordedMs() : System.currentTimeMillis();
        if (end <= started) {
            return null;
        }
        return describeDuration(end - started);
    }

    @NotNull
    private static String describeDuration(long millis) {
        if (millis < 1000) {
            return millis + " ms";
        }
        long seconds = millis / 1000;
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m " + (seconds % 60) + "s";
        }
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }

    @Property(viewable = true, order = 10)
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
        List<WeaviateReplicationRest.ErrorInfo> errors = op.allErrors();
        if (errors.isEmpty()) {
            // Everything else about this operation is in the label or a column of its own.
            return null;
        }
        return errors.size() + " error(s), most recently: "
            + errors.get(errors.size() - 1).message();
    }

    /**
     * Only what the label does not already say.
     * <p>
     * The navigator inlines this into the row -- {@code getNodeBriefInfo} returns the tooltip and
     * {@code DatabaseNavigatorLabelProvider} appends it in brackets whenever "Show object tips" is
     * on. Restating the operation here therefore doubled the width of a row that already names
     * the type, the shard and both nodes. Null when there is nothing to add, so no brackets
     * appear at all.
     */
    @Nullable
    @Override
    public String getObjectToolTip() {
        StringBuilder sb = new StringBuilder();
        if (op.uncancelable()) {
            sb.append("past the point where it can be cancelled");
        }
        List<WeaviateReplicationRest.ErrorInfo> errors = op.allErrors();
        if (!errors.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(errors.size()).append(" error(s), most recently: ")
                .append(errors.get(errors.size() - 1).message());
        }
        return sb.length() == 0 ? null : sb.toString();
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
