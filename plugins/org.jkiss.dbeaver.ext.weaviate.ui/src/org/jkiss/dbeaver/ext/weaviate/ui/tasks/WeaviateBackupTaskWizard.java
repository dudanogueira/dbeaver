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
package org.jkiss.dbeaver.ext.weaviate.ui.tasks;

import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.IWizardPage;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateBackupBackend;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateBackupNode;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.ext.weaviate.model.tasks.WeaviateBackupSettings;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.runtime.DBRRunnableContext;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.task.DBTTask;
import org.jkiss.dbeaver.registry.task.TaskPreferenceStore;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.tasks.ui.wizard.TaskConfigurationWizard;
import org.jkiss.dbeaver.tasks.ui.wizard.TaskConfigurationWizardDialog;
import org.jkiss.dbeaver.tasks.ui.wizard.TaskWizardExecutor;

import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The backup and restore wizard.
 * <p>
 * Extends {@link TaskConfigurationWizard} directly rather than {@code AbstractNativeToolWizard},
 * which is what the pg_dump and mysqldump wizards use. That one resolves a native client
 * installation and fails its own page when it cannot find one; there is no external binary here.
 * {@code SQLToolTaskWizard} is the precedent for a task wizard that runs in-process.
 */
public class WeaviateBackupTaskWizard extends TaskConfigurationWizard<WeaviateBackupSettings> {

    private static final org.jkiss.dbeaver.Log log =
        org.jkiss.dbeaver.Log.getLog(WeaviateBackupTaskWizard.class);

    static final String TASK_RESTORE = "weaviateBackupRestore";

    private final WeaviateBackupSettings settings = new WeaviateBackupSettings();

    private WeaviateBackupSettingsPage pageSettings;
    private WeaviateBackupLogPage pageLog;

    /** Set from the selection, so the form can show what the chosen backup holds. */
    @Nullable
    private WeaviateBackupNode selectedBackup;
    @Nullable
    private WeaviateDataSource dataSource;

    public WeaviateBackupTaskWizard() {
    }

    public WeaviateBackupTaskWizard(@NotNull DBTTask task) {
        super(task);
        settings.loadSettings(new TaskPreferenceStore(task));
    }

    public boolean isRestore() {
        DBTTask task = getCurrentTask();
        return task != null && TASK_RESTORE.equals(task.getType().getId());
    }

    @Nullable
    public WeaviateBackupNode getSelectedBackup() {
        return selectedBackup;
    }

    @Nullable
    public WeaviateDataSource getWeaviateDataSource() {
        return dataSource;
    }

    /**
     * Reads the node the action was invoked on.
     * <p>
     * Restore is offered on a backup and create on a backend, so the selection is what says which
     * backend to use, which backup to restore, and -- through either -- which connection the task
     * belongs to. Without it the settings would have to guess at a connection, which is wrong the
     * moment two Weaviate connections are open.
     */
    @Override
    public void init(@NotNull org.eclipse.ui.IWorkbench workbench, @Nullable IStructuredSelection currentSelection) {
        super.init(workbench, currentSelection);
        adoptSelection(currentSelection);
    }

    private void adoptSelection(@Nullable IStructuredSelection selection) {
        if (selection == null) {
            return;
        }
        List<String> collections = new ArrayList<>();
        for (Object element : selection.toList()) {
            DBNNode node = element instanceof DBNNode n ? n : null;
            DBSObject object = node instanceof DBNDatabaseNode databaseNode
                ? databaseNode.getObject() : null;

            if (object instanceof WeaviateBackupNode backup) {
                selectedBackup = backup;
                settings.setBackupId(backup.getName());
                settings.setBackendId(backup.getBackend().getBackendId());
                rememberDataSource(backup.getBackend().getDataSource());
                return;
            }
            if (object instanceof WeaviateBackupBackend backend) {
                settings.setBackendId(backend.getBackendId());
                rememberDataSource(backend.getDataSource());
                return;
            }
            if (object instanceof WeaviateCollection collection) {
                // Collected rather than returned on, so selecting eight collections carries all
                // eight through instead of only whichever the menu was opened over.
                collections.add(collection.getName());
                rememberDataSource(collection.getDataSource());
                continue;
            }
            if (object instanceof WeaviateDataSource weaviate) {
                rememberDataSource(weaviate);
            } else if (node != null && node.getParentNode() != null) {
                // The Collections folder and the connection node both mean the whole server. An
                // empty include list is exactly that, so there is nothing more to set.
                rememberDataSourceOf(node);
            }
        }
        if (!collections.isEmpty()) {
            settings.setIncludeCollections(collections);
        }
        chooseDefaultBackend();
    }

    private void rememberDataSourceOf(@NotNull DBNNode node) {
        if (node instanceof DBNDatabaseNode databaseNode
            && databaseNode.getDataSource() instanceof WeaviateDataSource weaviate) {
            rememberDataSource(weaviate);
        }
    }

    private void rememberDataSource(@Nullable org.jkiss.dbeaver.model.DBPDataSource candidate) {
        if (dataSource == null && candidate instanceof WeaviateDataSource weaviate) {
            dataSource = weaviate;
            settings.setDataSourceId(weaviate.getContainer().getId());
        }
    }

    /**
     * Picks a backend when the selection did not name one, which is every route except starting
     * from a backend node. The first that can actually be written to; on nearly every server that
     * is the only one.
     */
    private void chooseDefaultBackend() {
        if (dataSource == null) {
            return;
        }
        try {
            WeaviateBackupBackend backend =
                dataSource.getDefaultBackupBackend(new VoidProgressMonitor());
            if (backend != null) {
                settings.setBackendId(backend.getBackendId());
            }
        } catch (Exception e) {
            // The page shows the backend it will use and validates before finishing, so a failure
            // to guess here is not worth interrupting the wizard for.
            log.debug("Cannot choose a default backup backend", e);
        }
    }

    @Override
    public WeaviateBackupSettings getSettings() {
        return settings;
    }

    @Override
    protected String getDefaultWindowTitle() {
        return getTaskType().getName();
    }

    @Override
    public String getTaskTypeId() {
        return getCurrentTask().getType().getId();
    }

    @Override
    public void addPages() {
        super.addPages();
        pageSettings = new WeaviateBackupSettingsPage(this);
        pageLog = new WeaviateBackupLogPage();
        addPage(pageSettings);
        addPage(pageLog);
    }

    @Override
    public IWizardPage getNextPage(@NotNull IWizardPage page) {
        return page == pageSettings ? null : super.getNextPage(page);
    }

    @Override
    public void saveTaskState(DBRRunnableContext runnableContext, DBTTask task, Map<String, Object> state) {
        pageSettings.saveSettings();
        settings.saveSettings(new TaskPreferenceStore(state));
    }

    @Override
    public boolean performFinish() {
        if (isRunTaskOnFinish()) {
            saveConfigurationToTask(getCurrentTask());
            return super.performFinish();
        }
        try {
            DBTTask task = getCurrentTask();
            saveConfigurationToTask(task);

            TaskConfigurationWizardDialog container = getContainer();
            container.disableButtonsOnProgress();
            container.showPage(pageLog);
            pageLog.clearLog();

            new TaskWizardExecutor(getRunnableContext(), task, org.jkiss.dbeaver.Log.getLog(
                WeaviateBackupTaskWizard.class), pageLog.getLogWriter()).executeTask();

            container.enableButtonsAfterProgress();
            container.setCompleteMarkAfterProgress();
            // Deliberately false: the dialog stays open so the phase log can be read, and a second
            // backup can be started without reopening it. Same choice the native tool wizards make.
            return false;
        } catch (Exception e) {
            DBWorkbench.getPlatformUI().showError("Backup failed", e.getMessage(), e);
            return false;
        }
    }
}
