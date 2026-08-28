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
package org.jkiss.dbeaver.ext.weaviate.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.ui.ISources;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateBackupBackend;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateBackupNode;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.navigator.DBNDataSource;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseFolder;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.tasks.ui.wizard.TaskConfigurationWizardDialog;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;

/**
 * Puts Backup and Restore where they will be found.
 * <p>
 * Registering the tasks already contributes them under the navigator's "Tools" submenu, which is
 * how pg_dump is reached. That is two levels down and unlike every other action in this plugin,
 * which sits directly on the context menu -- so the entries are offered there as well. Both routes
 * open the same wizard on the same task; this is a shortcut, not a second implementation.
 * <p>
 * {@code TaskConfigurationWizardDialog#openNewToolTaskDialog} is exactly what the platform's own
 * {@code ExecuteToolHandler} calls, so a task started this way is indistinguishable from one
 * started through Tools.
 */
public abstract class WeaviateBackupActionHandler extends AbstractHandler {

    /**
     * The Collections folder's node id. It has no {@code id=} in the tree, so the platform derives
     * one from its child items' path, which is {@code collection}.
     */
    private static final String COLLECTIONS_FOLDER = "collection";

    public static final String TASK_CREATE = "weaviateBackupCreate";
    public static final String TASK_RESTORE = "weaviateBackupRestore";

    /**
     * Which task this entry starts.
     * <p>
     * A subclass per command rather than one handler with a parameter, because enablement is what
     * differs and {@code setEnabled} is not told which command is asking. Sharing one handler
     * would enable both entries wherever either applied, putting Restore on a backend and Backup
     * on a backup.
     */
    protected abstract boolean isRestore();

    /**
     * The node this action would run on, or null when it does not apply.
     * <p>
     * Restore wants a backup that succeeded. Create accepts either a backend whose module is
     * enabled, or one or more collections -- backing up is a thing you decide to do about some
     * collections at least as often as about a backend, and starting from the collections is the
     * only way the choice arrives already made.
     * <p>
     * A collection is only a subject when the server has somewhere to write. Otherwise the entry
     * would open a wizard that cannot finish.
     */
    @Nullable
    private static DBSObject subject(@Nullable ISelection selection, boolean restore) {
        if (selection == null) {
            return null;
        }
        DBNNode node = NavigatorUtils.getSelectedNode(selection);
        if (node == null) {
            return null;
        }
        DBSObject object = node instanceof DBNDatabaseNode databaseNode
            ? databaseNode.getObject() : null;
        if (restore) {
            return object instanceof WeaviateBackupNode backup
                && backup.getBackupStatus().isRestorable() ? backup : null;
        }
        if (object instanceof WeaviateBackupBackend backend) {
            return backend.isAvailable() ? backend : null;
        }
        if (object instanceof WeaviateCollection collection) {
            return collection.getDataSource() instanceof WeaviateDataSource ds
                && ds.hasAvailableBackupBackend() ? collection : null;
        }
        // The connection itself, or the Collections folder: both mean "all of them", which is
        // what the wizard already does when no collection is named.
        WeaviateDataSource wholeServer = weaviateOf(node);
        return wholeServer != null && wholeServer.hasAvailableBackupBackend() ? wholeServer : null;
    }

    /**
     * The connection behind a node that stands for the whole server: the connection node itself,
     * or its Collections folder. Null for anything narrower, which the caller has already handled.
     */
    @Nullable
    private static WeaviateDataSource weaviateOf(@NotNull DBNNode node) {
        if (node instanceof DBNDatabaseFolder folder) {
            return COLLECTIONS_FOLDER.equals(folder.getNodeId())
                && folder.getParentObject() instanceof DBSObject parent
                && parent.getDataSource() instanceof WeaviateDataSource ds ? ds : null;
        }
        if (node instanceof DBNDataSource dataSourceNode) {
            DBPDataSourceContainer container = dataSourceNode.getDataSourceContainer();
            return container != null && container.getDataSource() instanceof WeaviateDataSource ds
                ? ds : null;
        }
        return null;
    }

    @Nullable
    private static ISelection selectionFrom(Object evaluationContext) {
        if (!(evaluationContext instanceof IEvaluationContext context)) {
            return null;
        }
        Object selection = context.getVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME);
        return selection instanceof ISelection sel ? sel : null;
    }

    @Override
    public void setEnabled(Object evaluationContext) {
        setBaseEnabled(subject(selectionFrom(evaluationContext), isRestore()) != null);
    }

    @Override
    public Object execute(ExecutionEvent event) {
        boolean restore = isRestore();
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        DBSObject subject = subject(selection, restore);
        if (subject == null) {
            return null;
        }
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        DBPProject project = subject.getDataSource() == null
            ? null
            : subject.getDataSource().getContainer().getProject();
        if (window == null || project == null) {
            return null;
        }
        IStructuredSelection nodeSelection = selection instanceof IStructuredSelection structured
            ? structured
            : new StructuredSelection(subject);
        TaskConfigurationWizardDialog.openNewToolTaskDialog(
            window, project, restore ? TASK_RESTORE : TASK_CREATE, nodeSelection);
        return null;
    }
}
