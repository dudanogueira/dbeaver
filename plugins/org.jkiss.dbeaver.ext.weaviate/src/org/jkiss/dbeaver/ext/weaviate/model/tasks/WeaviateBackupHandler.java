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
package org.jkiss.dbeaver.ext.weaviate.model.tasks;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateBackup;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateBackupStatus;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.DBRRunnableContext;
import org.jkiss.dbeaver.model.task.DBTTask;
import org.jkiss.dbeaver.model.task.DBTTaskExecutionListener;
import org.jkiss.dbeaver.model.task.DBTTaskHandler;
import org.jkiss.dbeaver.model.task.DBTTaskRunStatus;
import org.jkiss.dbeaver.utils.RuntimeUtils;

import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.util.Locale;
import java.util.Optional;

/**
 * The shared half of the backup and restore tasks: run the operation, then watch it.
 * <p>
 * Implements {@link DBTTaskHandler} directly rather than extending
 * {@code AbstractNativeToolHandler}, which is what pg_dump and mysqldump use. That class is built
 * around {@link ProcessBuilder} -- its abstract contract is literally {@code getCommandLine} and
 * {@code fillProcessParameters} -- and there is no external process here. {@code DBTTaskHandler}
 * itself carries no process assumptions, as the data-transfer tasks already demonstrate.
 * <p>
 * The poll loop has no precedent in this codebase; nothing else in DBeaver watches a server-side
 * operation it started. The shape follows the two nearest analogues: check the monitor, act, sleep
 * with {@link RuntimeUtils#pause}, and report each phase through {@code subTask} so it reaches
 * both the progress bar and the task log.
 */
public abstract class WeaviateBackupHandler implements DBTTaskHandler {

    private static final Log log = Log.getLog(WeaviateBackupHandler.class);

    /**
     * How long to wait between status reads.
     * <p>
     * A second, matching what the client's own waiter and weaviate-studio both use. Backups move
     * through their phases in seconds on a small collection, so anything slower makes a short
     * backup look like it did nothing at all until it finished.
     */
    private static final long POLL_INTERVAL_MS = 1000;

    /** Whether this handler restores rather than creates. */
    protected abstract boolean isRestore();

    /** Starts the operation and returns the server's first answer. */
    @NotNull
    protected abstract WeaviateBackup start(
        @NotNull DBRProgressMonitor monitor,
        @NotNull WeaviateDataSource dataSource,
        @NotNull WeaviateBackupSettings settings
    ) throws DBException;

    @NotNull
    @Override
    public DBTTaskRunStatus executeTask(
        @NotNull DBRRunnableContext runnableContext,
        @NotNull DBTTask task,
        @NotNull Locale locale,
        @NotNull Log log,
        @NotNull PrintStream logStream,
        @NotNull DBTTaskExecutionListener listener
    ) throws DBException {
        WeaviateBackupSettings settings = new WeaviateBackupSettings();
        settings.loadSettings(new org.jkiss.dbeaver.registry.task.TaskPreferenceStore(task));

        DBTTaskRunStatus result = new DBTTaskRunStatus();
        try {
            runnableContext.run(true, true, monitor -> {
                listener.taskStarted(task);
                Throwable error = null;
                try {
                    result.setResultMessage(run(monitor, task, settings, logStream));
                } catch (Exception e) {
                    error = e;
                    throw new InvocationTargetException(e);
                } finally {
                    listener.taskFinished(task, null, error, settings);
                }
            });
        } catch (InvocationTargetException e) {
            throw new DBException("Backup task failed", e.getTargetException());
        } catch (InterruptedException e) {
            // A cancel, already passed on to the server by the loop below.
            return result;
        }
        return result;
    }

    @NotNull
    private String run(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBTTask task,
        @NotNull WeaviateBackupSettings settings,
        @NotNull PrintStream logStream
    ) throws DBException, InterruptedException {
        WeaviateDataSource dataSource = resolveDataSource(task, settings);
        String backend = settings.getBackendId();
        String id = settings.getBackupId();
        String verb = isRestore() ? "Restore" : "Backup";

        monitor.beginTask(verb + " " + id, org.eclipse.core.runtime.IProgressMonitor.UNKNOWN);
        try {
            WeaviateBackup current = start(monitor, dataSource, settings);
            report(monitor, logStream, current);

            while (!current.status().isTerminal()) {
                if (monitor.isCanceled()) {
                    // The whole reason this loop exists rather than the client's waitForCompletion:
                    // a cancel here can actually reach the server.
                    cancelQuietly(monitor, dataSource, backend, id);
                    throw new InterruptedException();
                }
                RuntimeUtils.pause((int) POLL_INTERVAL_MS);

                Optional<WeaviateBackup> polled =
                    dataSource.getBackupStatus(monitor, backend, id, isRestore());
                if (polled.isEmpty()) {
                    // The server has no record of it. For a restore that means it never started;
                    // either way there is nothing left to watch.
                    throw new DBException(
                        "The server no longer reports a status for " + id);
                }
                if (polled.get().status() != current.status()) {
                    report(monitor, logStream, polled.get());
                }
                current = polled.get();
            }

            if (current.status() == WeaviateBackupStatus.FAILED) {
                throw new DBException(verb + " of " + id + " failed"
                    + (current.error() == null ? "" : ": " + current.error()));
            }
            dataSource.resetBackupCache();
            return verb + " " + id + ": " + current.status().getLabel();
        } finally {
            monitor.done();
        }
    }

    private void report(
        @NotNull DBRProgressMonitor monitor,
        @NotNull PrintStream logStream,
        @NotNull WeaviateBackup backup
    ) {
        String line = backup.status().getLabel()
            + (backup.path() == null ? "" : " -> " + backup.path());
        monitor.subTask(line);
        logStream.println(line);
    }

    /**
     * Cancelling is best-effort. The operation is already being abandoned, and a server that
     * refuses the cancel -- it does during FINALIZING -- must not turn that into a second failure
     * on top of the one the user asked for.
     */
    private void cancelQuietly(
        @NotNull DBRProgressMonitor monitor,
        @NotNull WeaviateDataSource dataSource,
        @NotNull String backend,
        @NotNull String id
    ) {
        try {
            dataSource.cancelBackup(monitor, backend, id, isRestore());
        } catch (Exception e) {
            log.warn("Cannot cancel " + id + " on the server", e);
        }
    }

    /**
     * The connection this task names, which must be connected: a backup runs through the live
     * client, and a saved task pointing at a closed connection has to say so rather than pick
     * another one.
     */
    @NotNull
    private static WeaviateDataSource resolveDataSource(
        @NotNull DBTTask task, @NotNull WeaviateBackupSettings settings
    ) throws DBException {
        String id = settings.getDataSourceId();
        if (id.isEmpty()) {
            throw new DBException("This task does not name a connection");
        }
        DBPDataSourceContainer container =
            task.getProject().getDataSourceRegistry().getDataSource(id);
        if (container == null) {
            throw new DBException("Connection " + id + " no longer exists");
        }
        if (!(container.getDataSource() instanceof WeaviateDataSource weaviate)) {
            throw new DBException("Connect to " + container.getName() + " before running this task");
        }
        return weaviate;
    }
}
