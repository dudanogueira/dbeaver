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
package org.jkiss.dbeaver.ext.weaviate.ui;

import org.eclipse.core.runtime.IProgressMonitor;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateReplicationRest;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateReplicationState;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.utils.RuntimeUtils;

import java.util.List;

/**
 * Follows a replica movement until it stops.
 * <p>
 * Shared, because a movement can be followed from two places: picked out of the tree afterwards,
 * or straight from the dialog that started it. Both want the same loop, and the second cannot
 * reach it through a handler.
 * <p>
 * The same shape as the backup task's poll loop, which is the only other thing in this codebase
 * that watches a server-side operation. The difference is what cancelling means: a backup that is
 * not being watched is not running, whereas a movement runs whether or not anyone is looking. So
 * abandoning this loop stops the watching and nothing else -- stopping the movement is a separate,
 * deliberate action.
 */
public final class WeaviateReplicationWatch {

    /** Matches the backup handler's cadence. */
    private static final int POLL_INTERVAL_MS = 1000;

    private WeaviateReplicationWatch() {
    }

    /**
     * Polls one movement on the caller's monitor, reporting each state change.
     * <p>
     * Returns when the movement reaches a terminal state, when the record disappears, or when the
     * monitor is cancelled. Does not refresh: the caller decides when the tree should be re-read,
     * because it may have more to do first.
     */
    public static void followWith(
        @NotNull DBRProgressMonitor monitor,
        @NotNull WeaviateDataSource dataSource,
        @NotNull String id,
        @NotNull String label
    ) throws DBException {
        monitor.beginTask("Following " + label, IProgressMonitor.UNKNOWN);
        try {
            String last = null;
            while (!monitor.isCanceled()) {
                WeaviateReplicationRest.OperationInfo current =
                    WeaviateReplicationRest.get(dataSource, id);
                if (current == null) {
                    // Deleted underneath us, which is a legitimate end to watching.
                    break;
                }
                if (!current.state().equals(last)) {
                    last = current.state();
                    monitor.subTask(describe(current));
                }
                if (WeaviateReplicationState.fromName(current.state()).isTerminal()) {
                    break;
                }
                RuntimeUtils.pause(POLL_INTERVAL_MS);
            }
        } finally {
            monitor.done();
        }
    }

    /** The state, and the most recent thing that went wrong in it. */
    @NotNull
    private static String describe(@NotNull WeaviateReplicationRest.OperationInfo op) {
        List<WeaviateReplicationRest.ErrorInfo> errors = op.allErrors();
        return errors.isEmpty()
            ? op.state()
            : op.state() + " -- " + errors.get(errors.size() - 1).message();
    }
}
