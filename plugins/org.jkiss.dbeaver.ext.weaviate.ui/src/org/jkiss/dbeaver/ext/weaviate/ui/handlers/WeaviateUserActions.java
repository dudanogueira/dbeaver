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

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDbUser;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateRbacRest;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateChangeConfirmDialog;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateRbacRefresh;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIUtils;

import java.lang.reflect.InvocationTargetException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Acting on several database users at once, whether picked in the tree or taken from the folder.
 * <p>
 * Shared because "do this to one user" and "do this to all of them" differ only in where the list
 * comes from, and two copies of the skipping, confirming and failure-reporting rules is how one of
 * them ends up quietly different from the other.
 */
final class WeaviateUserActions {

    private static final Log log = Log.getLog(WeaviateUserActions.class);

    static final String TITLE = "Weaviate users";

    /** What is being asked for, which decides both the wording and who is already there. */
    enum Operation {
        ACTIVATE("Activate", "activate", "activated", true),
        DEACTIVATE("Deactivate", "deactivate", "deactivated", true),
        DELETE("Delete", "delete", "deleted", false);

        final String button;
        final String verb;
        final String past;
        /** Whether users already in the wanted state can simply be left out. */
        final boolean skippableWhenAlreadyThere;

        Operation(String button, String verb, String past, boolean skippable) {
            this.button = button;
            this.verb = verb;
            this.past = past;
            this.skippableWhenAlreadyThere = skippable;
        }
    }

    private WeaviateUserActions() {
    }

    /**
     * Runs an operation over a set of users, having first explained what it will not touch.
     * <p>
     * Two kinds of exclusion, and they are reported differently because they mean different
     * things. A user declared in the server's environment cannot be changed through the API at
     * all, so it is named -- somebody who selected it deserves to know why nothing happened to it.
     * A user already in the wanted state is not an obstacle, just nothing to do, so it is counted
     * rather than listed.
     */
    static void run(
        @NotNull org.eclipse.swt.widgets.Shell shell,
        @NotNull WeaviateDataSource dataSource,
        @NotNull List<WeaviateDbUser> users,
        @NotNull Operation operation
    ) {
        if (users.isEmpty()) {
            DBWorkbench.getPlatformUI().showMessageBox(TITLE, "There are no users here.", false);
            return;
        }

        // Every user asked about becomes a row, including the ones that will be left alone --
        // the reason belongs beside the name, not in a sentence underneath a list.
        List<WeaviateChangeConfirmDialog.Row> rows = new ArrayList<>();
        List<WeaviateDbUser> targets = new ArrayList<>();
        for (WeaviateDbUser user : users) {
            String outcome;
            boolean included;
            if (user.isEnvUser()) {
                outcome = "left alone: declared in the server's environment";
                included = false;
            } else if (operation.skippableWhenAlreadyThere
                && user.isActive() == (operation == Operation.ACTIVATE)) {
                outcome = "left alone: already " + operation.past;
                included = false;
            } else {
                outcome = "will be " + operation.past;
                included = true;
                targets.add(user);
            }
            rows.add(WeaviateChangeConfirmDialog.Row.of(user, "User", outcome, included));
        }

        if (targets.isEmpty()) {
            // Nothing to do, so no confirmation to press: say why and stop.
            StringBuilder message = new StringBuilder("Nothing to " + operation.verb + ".\n\n");
            for (WeaviateChangeConfirmDialog.Row row : rows) {
                message.append(row.name()).append(" - ").append(row.outcome()).append('\n');
            }
            DBWorkbench.getPlatformUI().showMessageBox(TITLE, message.toString(), false);
            return;
        }

        String warning = switch (operation) {
            case DELETE -> "Their API keys stop working immediately. This cannot be undone.";
            case DEACTIVATE -> "Their API keys stop working until they are activated again.";
            case ACTIVATE -> "Their API keys start working again.";
        };
        WeaviateChangeConfirmDialog dialog = new WeaviateChangeConfirmDialog(
            shell, TITLE,
            MessageFormat.format("{0} {1} user(s)?\n\n{2}",
                operation.button, targets.size(), warning),
            rows, operation.button);
        if (dialog.open() != org.eclipse.jface.dialogs.IDialogConstants.OK_ID) {
            return;
        }

        Map<String, String> failed = new LinkedHashMap<>();
        try {
            UIUtils.runInProgressService(monitor -> {
                monitor.beginTask(operation.button + " " + targets.size() + " user(s)",
                    targets.size());
                try {
                    for (WeaviateDbUser user : targets) {
                        if (monitor.isCanceled()) {
                            break;
                        }
                        monitor.subTask(user.getName());
                        try {
                            apply(dataSource, user.getName(), operation);
                        } catch (DBException e) {
                            // One refusal should not abandon the rest; what failed is named once
                            // at the end.
                            log.error("Cannot " + operation.verb + " " + user.getName(), e);
                            failed.put(user.getName(), e.getMessage() == null
                                ? e.getClass().getSimpleName() : e.getMessage());
                        }
                        monitor.worked(1);
                    }
                } finally {
                    monitor.done();
                }
                WeaviateRbacRefresh.after(monitor, dataSource);
            });
        } catch (InvocationTargetException e) {
            DBWorkbench.getPlatformUI().showError(TITLE,
                "Cannot " + operation.verb + " the selected users", e.getTargetException());
            return;
        } catch (InterruptedException e) {
            // Cancelled partway; whatever was accepted stands and the refresh shows it.
        }

        if (!failed.isEmpty()) {
            StringBuilder message = new StringBuilder(
                "These users were not " + operation.past + ":\n\n");
            failed.forEach((name, reason) ->
                message.append(name).append(" - ").append(reason).append('\n'));
            DBWorkbench.getPlatformUI().showMessageBox(TITLE, message.toString(), true);
        }
    }

    private static void apply(
        @NotNull WeaviateDataSource dataSource, @NotNull String userId,
        @NotNull Operation operation
    ) throws DBException {
        switch (operation) {
            case ACTIVATE -> WeaviateRbacRest.activateDbUser(dataSource, userId);
            // revokeKey stays false: deactivating is reversible and revoking a key is not, so the
            // destructive half is not bundled into the reversible one.
            case DEACTIVATE -> WeaviateRbacRest.deactivateDbUser(dataSource, userId, false);
            case DELETE -> WeaviateRbacRest.deleteDbUser(dataSource, userId);
        }
    }



    /** Every user under the connection, for the folder-level actions. */
    @NotNull
    static List<WeaviateDbUser> allUsers(
        @NotNull WeaviateDataSource dataSource, @NotNull DBRProgressMonitor monitor
    ) throws DBException {
        return dataSource.getDbUsers(monitor);
    }
}
