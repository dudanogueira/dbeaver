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
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateReplicationRest;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateChangeConfirmDialog;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateReplicationRefresh;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIIcon;
import org.jkiss.dbeaver.ui.UIUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Actions on the Replication folder: clear the finished records, or force out a stuck one.
 * <p>
 * Separate from {@link WeaviateReplicationOpHandler} for the reason recorded in
 * {@code WeaviateRoleHandler}: {@code setEnabled} cannot see which {@code operation} an invocation
 * carries, so one handler across both scopes would offer every action in both places.
 */
public class WeaviateReplicationFolderHandler extends AbstractHandler implements IElementUpdater {

    private static final Log log = Log.getLog(WeaviateReplicationFolderHandler.class);

    private static final String PARAM_OPERATION = "operation";
    private static final String TITLE = "Replica movement";

    @Override
    public void setEnabled(Object evaluationContext) {
        ISelection selection = WeaviateReplicationNodes.selectionOf(evaluationContext);
        setBaseEnabled(selection != null
            && WeaviateReplicationNodes.dataSourceOf(selection) != null
            && WeaviateReplicationNodes.isOpsFolder(selection));
    }

    @Override
    public Object execute(ExecutionEvent event) {
        String operation = event.getParameter(PARAM_OPERATION);
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        WeaviateDataSource dataSource = WeaviateReplicationNodes.dataSourceOf(selection);
        if (dataSource == null) {
            return null;
        }
        switch (operation == null ? "" : operation) {
            case "deleteAll" -> deleteAll(event, dataSource);
            case "forceDelete" -> forceDelete(event, dataSource);
            default -> log.debug("Unknown replication folder operation: " + operation);
        }
        return null;
    }

    /**
     * Removes every record the server is willing to let go of.
     * <p>
     * The server skips what it cannot delete rather than refusing the whole call, so this is safe
     * to offer without checking first -- but the wording has to say so, or a movement still
     * running afterwards looks like a bug.
     */
    private void deleteAll(
        @NotNull ExecutionEvent event, @NotNull WeaviateDataSource dataSource
    ) {
        if (!DBWorkbench.getPlatformUI().confirmAction(TITLE,
            "Delete every replication record this server will release?\n\n"
                + "Movements still running are cancelled first where that is allowed. Any that "
                + "have passed the point of no return are left alone and stay in the list.",
            "Delete All", true)) {
            return;
        }
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    WeaviateReplicationRest.deleteAll(dataSource);
                    WeaviateReplicationRefresh.after(monitor, dataSource);
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot delete replication records", e.getTargetException());
            DBWorkbench.getPlatformUI().showError(TITLE,
                "Cannot delete the replication records", e.getTargetException());
        } catch (InterruptedException e) {
            // Cancelled.
        }
    }

    /**
     * The escape hatch, shown as a preview first.
     * <p>
     * Force delete removes an operation from the state machine with no checks and no cleanup --
     * the specification's own words are "USE AT OWN RISK ... may lead to data corruption or loss".
     * It exists because an operation can wedge somewhere nothing else will move it from, and it is
     * the one replication call that still answers when the feature is switched off.
     * <p>
     * So it is always run as a dry run first. The server reports which ids it would remove, those
     * are shown, and only a second, deliberate confirmation does it for real.
     */
    private void forceDelete(
        @NotNull ExecutionEvent event, @NotNull WeaviateDataSource dataSource
    ) {
        WeaviateReplicationRest.ForceDeleteResult[] preview = new WeaviateReplicationRest.ForceDeleteResult[1];
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    preview[0] = WeaviateReplicationRest.forceDelete(
                        dataSource, null, null, null, null, true);
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            DBWorkbench.getPlatformUI().showError(TITLE,
                "Cannot preview a force delete", e.getTargetException());
            return;
        } catch (InterruptedException e) {
            return;
        }

        List<String> ids = preview[0] == null ? List.of() : preview[0].deleted();
        if (ids.isEmpty()) {
            DBWorkbench.getPlatformUI().showMessageBox(TITLE,
                "There is nothing for a force delete to remove.", false);
            return;
        }
        List<WeaviateChangeConfirmDialog.Row> rows = new ArrayList<>();
        for (String id : ids) {
            rows.add(new WeaviateChangeConfirmDialog.Row(
                DBIcon.TREE_PARTITION, id, "Operation", "will be forced out of the state machine",
                true));
        }
        WeaviateChangeConfirmDialog dialog = new WeaviateChangeConfirmDialog(
            HandlerUtil.getActiveShell(event), TITLE,
            "Force delete " + ids.size() + " replication record(s)?\n\n"
                + "This is not the same as Delete. It removes them from the state machine with no "
                + "checks and no cleanup of whatever they left behind on the nodes, which Weaviate "
                + "warns may lead to data corruption or loss. Use it only for a movement that is "
                + "stuck and that Delete refuses.\n\n"
                + "The list above came from a dry run: this is what the server says it would "
                + "remove.",
            rows, "Force Delete");
        if (dialog.open() != IDialogConstants.OK_ID) {
            return;
        }
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    WeaviateReplicationRest.forceDelete(dataSource, null, null, null, null, false);
                    WeaviateReplicationRefresh.after(monitor, dataSource);
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot force delete replication records", e.getTargetException());
            DBWorkbench.getPlatformUI().showError(TITLE,
                "Cannot force delete the replication records", e.getTargetException());
        } catch (InterruptedException e) {
            // Cancelled.
        }
    }

    @Override
    public void updateElement(UIElement element, @SuppressWarnings("rawtypes") Map parameters) {
        Object operation = parameters == null ? null : parameters.get(PARAM_OPERATION);
        if (!(operation instanceof String op)) {
            return;
        }
        element.setIcon(DBeaverIcons.getImageDescriptor(
            "forceDelete".equals(op) ? UIIcon.REJECT : UIIcon.DELETE));
    }
}
