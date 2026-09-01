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
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateReplicationOp;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateReplicationRest;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateReplicationState;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateChangeConfirmDialog;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateReplicationRefresh;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIIcon;
import org.jkiss.dbeaver.ui.UIUtils;

import java.lang.reflect.InvocationTargetException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cancel, delete and watch, on replication operations picked in the tree.
 * <p>
 * What is offered is decided by the server's own flags rather than guessed. An operation past the
 * point where the replica joined the sharding state is {@code uncancelable}: a cancel then answers
 * 409, and a delete does too unless the operation is READY. Rows that cannot take the action are
 * shown in the confirmation and explained, not silently dropped.
 */
public class WeaviateReplicationOpHandler extends AbstractHandler implements IElementUpdater {

    private static final Log log = Log.getLog(WeaviateReplicationOpHandler.class);

    private static final String PARAM_OPERATION = "operation";
    private static final String TITLE = "Replica movement";

    /** How often a watched operation is polled, matching the backup handler's cadence. */
    private static final int POLL_INTERVAL_MS = 1000;

    @Override
    public void setEnabled(Object evaluationContext) {
        ISelection selection = WeaviateReplicationNodes.selectionOf(evaluationContext);
        setBaseEnabled(selection != null
            && WeaviateReplicationNodes.dataSourceOf(selection) != null
            && !WeaviateReplicationNodes.selectedOps(selection).isEmpty());
    }

    @Override
    public Object execute(ExecutionEvent event) {
        String operation = event.getParameter(PARAM_OPERATION);
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        WeaviateDataSource dataSource = WeaviateReplicationNodes.dataSourceOf(selection);
        List<WeaviateReplicationOp> ops = WeaviateReplicationNodes.selectedOps(selection);
        if (dataSource == null || ops.isEmpty()) {
            return null;
        }
        switch (operation == null ? "" : operation) {
            case "cancel" -> act(event, dataSource, ops, true);
            case "delete" -> act(event, dataSource, ops, false);
            case "watch" -> watch(event, dataSource, ops.get(0));
            default -> log.debug("Unknown replication operation: " + operation);
        }
        return null;
    }

    /**
     * Cancels or deletes, listing what will and will not be touched.
     *
     * @param cancel true to cancel, false to delete
     */
    private void act(
        @NotNull ExecutionEvent event,
        @NotNull WeaviateDataSource dataSource,
        @NotNull List<WeaviateReplicationOp> ops,
        boolean cancel
    ) {
        String verb = cancel ? "Cancel" : "Delete";
        List<WeaviateChangeConfirmDialog.Row> rows = new ArrayList<>();
        List<WeaviateReplicationOp> targets = new ArrayList<>();
        for (WeaviateReplicationOp op : ops) {
            boolean allowed = cancel ? op.canCancel() : op.canDelete();
            String outcome;
            if (allowed) {
                outcome = cancel ? "will be cancelled" : "will be deleted";
                targets.add(op);
            } else if (op.getReplicationState().isTerminal() && cancel) {
                outcome = "left alone: already " + op.getReplicationState().getLabel().toLowerCase(
                    java.util.Locale.ROOT);
            } else {
                // The server's own reason. Past this point the replica has joined the sharding
                // state and stopping would leave the shard in an inconsistent place.
                outcome = "left alone: past the point where it can be "
                    + (cancel ? "cancelled" : "deleted");
            }
            // Named by shard rather than by the tree label: the label says only which direction
            // the replica is going, and two movements of different shards between the same pair of
            // nodes read identically. A confirmation is the one place that must not be ambiguous.
            rows.add(new WeaviateChangeConfirmDialog.Row(
                org.jkiss.dbeaver.model.navigator.DBNModel.getStateOverlayImage(
                    org.jkiss.dbeaver.model.DBIcon.TREE_PARTITION, op.getObjectState()),
                op.getShardPath(),
                op.getOperationInfo().type() + " " + op.getOperationInfo().sourceNode()
                    + " \u2192 " + op.getOperationInfo().targetNode(),
                outcome, allowed));
        }

        if (targets.isEmpty()) {
            StringBuilder message = new StringBuilder("Nothing to " + verb.toLowerCase(
                java.util.Locale.ROOT) + ".\n\n");
            for (WeaviateChangeConfirmDialog.Row row : rows) {
                message.append(row.name()).append("\n  ").append(row.outcome()).append('\n');
            }
            DBWorkbench.getPlatformUI().showMessageBox(TITLE, message.toString(), false);
            return;
        }

        String warning = cancel
            ? "A cancelled movement cannot be resumed; the record stays as CANCELLED."
            : "The record is removed. Any movement still running is cancelled first.";
        WeaviateChangeConfirmDialog dialog = new WeaviateChangeConfirmDialog(
            HandlerUtil.getActiveShell(event), TITLE,
            MessageFormat.format("{0} {1} replica movement(s)?\n\n{2}",
                verb, targets.size(), warning),
            rows, verb);
        if (dialog.open() != IDialogConstants.OK_ID) {
            return;
        }

        Map<String, String> failed = new LinkedHashMap<>();
        try {
            UIUtils.runInProgressService(monitor -> {
                monitor.beginTask(verb + " " + targets.size() + " movement(s)", targets.size());
                try {
                    for (WeaviateReplicationOp op : targets) {
                        if (monitor.isCanceled()) {
                            break;
                        }
                        monitor.subTask(op.getName());
                        try {
                            if (cancel) {
                                WeaviateReplicationRest.cancel(dataSource, op.getOperationId());
                            } else {
                                WeaviateReplicationRest.delete(dataSource, op.getOperationId());
                            }
                        } catch (DBException e) {
                            // One refusal should not abandon the rest.
                            log.error("Cannot " + verb + " " + op.getOperationId(), e);
                            failed.put(op.getName(), e.getMessage() == null
                                ? e.getClass().getSimpleName() : e.getMessage());
                        }
                        monitor.worked(1);
                    }
                } finally {
                    monitor.done();
                }
                WeaviateReplicationRefresh.after(monitor, dataSource);
            });
        } catch (InvocationTargetException e) {
            DBWorkbench.getPlatformUI().showError(TITLE,
                "Cannot " + verb.toLowerCase(java.util.Locale.ROOT) + " the selected movements",
                e.getTargetException());
            return;
        } catch (InterruptedException e) {
            // Cancelled partway; whatever was accepted stands.
        }
        if (!failed.isEmpty()) {
            StringBuilder report = new StringBuilder("These movements were not changed:\n\n");
            failed.forEach((name, reason) ->
                report.append(name).append("\n  ").append(reason).append('\n'));
            DBWorkbench.getPlatformUI().showMessageBox(TITLE, report.toString(), true);
        }
    }

    /**
     * Follows one operation until it stops.
     * <p>
     * The same poll loop the backup handler uses, and for the same reason: nothing in DBeaver
     * watches a server-side operation. The difference is that this one is already running whether
     * or not anyone is looking, so cancelling the progress dialog only stops the watching --
     * stopping the movement is a separate, deliberate action.
     */
    private void watch(
        @NotNull ExecutionEvent event,
        @NotNull WeaviateDataSource dataSource,
        @NotNull WeaviateReplicationOp op
    ) {
        String id = op.getOperationId();
        try {
            UIUtils.runInProgressService(monitor -> {
                monitor.beginTask("Watching " + op.getName(),
                    org.eclipse.core.runtime.IProgressMonitor.UNKNOWN);
                try {
                    String last = null;
                    while (!monitor.isCanceled()) {
                        WeaviateReplicationRest.OperationInfo current;
                        try {
                            current = WeaviateReplicationRest.get(dataSource, id);
                        } catch (DBException e) {
                            throw new InvocationTargetException(e);
                        }
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
                        org.jkiss.dbeaver.utils.RuntimeUtils.pause(POLL_INTERVAL_MS);
                    }
                } finally {
                    monitor.done();
                }
                WeaviateReplicationRefresh.after(monitor, dataSource);
            });
        } catch (InvocationTargetException e) {
            DBWorkbench.getPlatformUI().showError(TITLE,
                "Cannot follow " + op.getName(), e.getTargetException());
        } catch (InterruptedException e) {
            // The user closed the progress dialog. The movement carries on regardless.
        }
    }

    @NotNull
    private static String describe(@NotNull WeaviateReplicationRest.OperationInfo op) {
        List<WeaviateReplicationRest.ErrorInfo> errors = op.allErrors();
        return errors.isEmpty()
            ? op.state()
            : op.state() + " -- " + errors.get(errors.size() - 1).message();
    }

    @Override
    public void updateElement(UIElement element, @SuppressWarnings("rawtypes") Map parameters) {
        Object operation = parameters == null ? null : parameters.get(PARAM_OPERATION);
        if (!(operation instanceof String op)) {
            return;
        }
        IWorkbenchWindow window = element.getServiceLocator().getService(IWorkbenchWindow.class);
        int count = window == null || window.getSelectionService() == null
            ? 0
            : WeaviateReplicationNodes.selectedOps(window.getSelectionService().getSelection()).size();
        String noun = count == 1 ? "Movement" : count + " Movements";
        switch (op) {
            case "cancel" -> {
                element.setText("Cancel " + noun);
                element.setIcon(DBeaverIcons.getImageDescriptor(UIIcon.REJECT));
            }
            case "delete" -> {
                element.setText("Delete " + noun);
                element.setIcon(DBeaverIcons.getImageDescriptor(UIIcon.DELETE));
            }
            case "watch" -> element.setIcon(DBeaverIcons.getImageDescriptor(UIIcon.REFRESH));
            default -> {
                // Keeps the command icon.
            }
        }
    }
}
