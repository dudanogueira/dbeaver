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
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateBackupNode;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.ext.weaviate.model.tasks.WeaviateBackupSettings;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.runtime.DBRRunnableContext;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.task.DBTTask;
import org.jkiss.dbeaver.registry.task.TaskPreferenceStore;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.tasks.ui.wizard.TaskConfigurationWizard;
import org.jkiss.dbeaver.tasks.ui.wizard.TaskConfigurationWizardDialog;
import org.jkiss.dbeaver.tasks.ui.wizard.TaskWizardExecutor;

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
        for (Object element : selection.toList()) {
            DBSObject object = element instanceof DBNDatabaseNode node ? node.getObject() : null;
            if (object instanceof WeaviateBackupNode backup) {
                selectedBackup = backup;
                settings.setBackupId(backup.getName());
                settings.setBackendId(backup.getBackend().getBackendId());
                rememberDataSource(backup.getBackend());
                return;
            }
            if (object instanceof WeaviateBackupBackend backend) {
                settings.setBackendId(backend.getBackendId());
                rememberDataSource(backend);
                return;
            }
        }
    }

    private void rememberDataSource(@NotNull WeaviateBackupBackend backend) {
        if (backend.getDataSource() instanceof WeaviateDataSource weaviate) {
            dataSource = weaviate;
            settings.setDataSourceId(weaviate.getContainer().getId());
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
