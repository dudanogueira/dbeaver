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
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDbUser;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateRbacRest;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateRole;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateApiKeyDialog;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateRbacRefresh;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateRoleAssignmentDialog;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIIcon;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.dialogs.EnterNameDialog;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;

import java.lang.reflect.InvocationTargetException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Actions on database users picked in the tree.
 * <p>
 * Only on users. The folder-level actions live in {@link WeaviateUsersFolderHandler}, and the
 * split is not tidiness: {@code AbstractHandler#setEnabled} is handed an evaluation context and
 * nothing else, with no way to see which {@code operation} the invocation carries. One handler
 * covering both scopes therefore has to enable every operation wherever any of them applies, and
 * "Deactivate User" duly appeared on the Users folder, where it could only reach a dialog saying
 * to select a user. Two handlers, each with a condition it can actually express.
 * <p>
 * Delete, activate and deactivate take a multi-selection; assigning roles and rotating a key are
 * about one user by their nature.
 * <p>
 * Users declared in {@code AUTHENTICATION_APIKEY_USERS} arrive as {@code db_env_user}, and the
 * server refuses to change them through the API. Every operation that would try says so by name
 * rather than letting the request fail with a status code.
 */
public class WeaviateDbUserHandler extends AbstractHandler implements IElementUpdater {

    private static final Log log = Log.getLog(WeaviateDbUserHandler.class);

    private static final String PARAM_OPERATION = "operation";
    private static final String TITLE = "Weaviate user";

    /**
     * Roles that exist but cannot be handed out.
     * <p>
     * {@code root} is granted by {@code AUTHORIZATION_RBAC_ROOT_USERS} and {@code read-only} by
     * its own variable; the API refuses to assign either, so offering them would only produce a
     * refusal after the fact.
     */
    private static final Set<String> NOT_ASSIGNABLE = Set.of("root", "read-only");

    @Override
    public void setEnabled(Object evaluationContext) {
        ISelection selection = WeaviateSecurityNodes.selectionOf(evaluationContext);
        setBaseEnabled(selection != null
            && WeaviateSecurityNodes.dataSourceOf(selection) != null
            && !WeaviateSecurityNodes.selectedUsers(selection).isEmpty());
    }

    @Override
    public Object execute(ExecutionEvent event) {
        String operation = event.getParameter(PARAM_OPERATION);
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        WeaviateDataSource dataSource = WeaviateSecurityNodes.dataSourceOf(selection);
        if (dataSource == null) {
            return null;
        }
        List<WeaviateDbUser> users = WeaviateSecurityNodes.selectedUsers(selection);
        WeaviateDbUser user = users.size() == 1 ? users.get(0) : null;

        switch (operation == null ? "" : operation) {
            case "roles" -> editRoles(event, dataSource, user);
            case "rotate" -> rotateKey(event, dataSource, user);
            case "delete" -> WeaviateUserActions.run(HandlerUtil.getActiveShell(event),
                dataSource, users, WeaviateUserActions.Operation.DELETE);
            case "activate" -> WeaviateUserActions.run(HandlerUtil.getActiveShell(event),
                dataSource, users, WeaviateUserActions.Operation.ACTIVATE);
            case "deactivate" -> WeaviateUserActions.run(HandlerUtil.getActiveShell(event),
                dataSource, users, WeaviateUserActions.Operation.DEACTIVATE);
            default -> log.debug("Unknown user operation: " + operation);
        }
        return null;
    }

    /**
     * Names how many users the entry would touch.
     * <p>
     * {@code updateElement} is handed the command parameters, which is what makes this possible
     * at all -- {@code setEnabled} is not, which is why the folder-level actions had to move to
     * their own handler. Read from what the tree already holds, with no fetching: this runs while
     * the menu is being built.
     */
    @Override
    public void updateElement(UIElement element, @SuppressWarnings("rawtypes") Map parameters) {
        Object operation = parameters == null ? null : parameters.get(PARAM_OPERATION);
        if (!(operation instanceof String op)) {
            return;
        }
        IWorkbenchWindow window = element.getServiceLocator().getService(IWorkbenchWindow.class);
        if (window == null || window.getSelectionService() == null) {
            return;
        }
        int count = WeaviateSecurityNodes
            .selectedUsers(window.getSelectionService().getSelection()).size();
        String noun = count == 1 ? "User" : count + " Users";
        switch (op) {
            case "delete" -> {
                element.setText("Delete " + noun);
                element.setIcon(DBeaverIcons.getImageDescriptor(UIIcon.DELETE));
            }
            case "activate" -> {
                element.setText("Activate " + noun);
                element.setIcon(DBeaverIcons.getImageDescriptor(UIIcon.ACCEPT));
            }
            case "deactivate" -> {
                element.setText("Deactivate " + noun);
                element.setIcon(DBeaverIcons.getImageDescriptor(UIIcon.REJECT));
            }
            // roles and rotate are single-user by nature; their fixed labels already say so, and
            // only the icon needs setting. One command serves every entry here, so the icon from
            // <commandImages> would otherwise be the same on all five.
            case "roles" -> element.setIcon(DBeaverIcons.getImageDescriptor(UIIcon.EDIT));
            case "rotate" -> element.setIcon(DBeaverIcons.getImageDescriptor(UIIcon.REFRESH));
            default -> {
                // An operation added to plugin.xml without a case here keeps the command icon.
            }
        }
    }

    /** Refuses an operation on an environment user, naming the reason. */
    private static boolean refuseEnvUser(@Nullable WeaviateDbUser user, @NotNull String what) {
        if (user != null && user.isEnvUser()) {
            DBWorkbench.getPlatformUI().showMessageBox(TITLE, MessageFormat.format(
                "{0} is declared in the server''s environment, so Weaviate will not {1} it "
                    + "through the API. Change AUTHENTICATION_APIKEY_USERS and restart instead.",
                user.getName(), what), true);
            return true;
        }
        return false;
    }

    private static boolean requireOne(@Nullable WeaviateDbUser user, @NotNull String what) {
        if (user == null) {
            DBWorkbench.getPlatformUI().showMessageBox(TITLE,
                "Select a single user to " + what + ".", true);
            return false;
        }
        return true;
    }


    private void editRoles(
        @NotNull ExecutionEvent event,
        @NotNull WeaviateDataSource dataSource,
        @Nullable WeaviateDbUser user
    ) {
        if (!requireOne(user, "change roles for")) {
            return;
        }
        List<String> available = new ArrayList<>();
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    for (WeaviateRole role : dataSource.getRoles(monitor)) {
                        if (!NOT_ASSIGNABLE.contains(role.getName())) {
                            available.add(role.getName());
                        }
                    }
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            DBWorkbench.getPlatformUI().showError(TITLE,
                "Cannot read the role list", e.getTargetException());
            return;
        } catch (InterruptedException e) {
            return;
        }

        WeaviateRoleAssignmentDialog dialog = new WeaviateRoleAssignmentDialog(
            HandlerUtil.getActiveShell(event), user.getName(), "User", available,
            new LinkedHashSet<>(user.getUserInfo().roles()),
            user.isEnvUser()
                ? "This user comes from the server's environment. Its roles can still be changed."
                : null);
        if (dialog.open() != IDialogConstants.OK_ID) {
            return;
        }
        applyRoleChange(event, dataSource, user.getName(), "db",
            dialog.getToAssign(), dialog.getToRevoke());
    }

    /** Shared by the user and group paths: assign what was added, revoke what was removed. */
    static void applyRoleChange(
        @NotNull ExecutionEvent event,
        @NotNull WeaviateDataSource dataSource,
        @NotNull String subjectId,
        @NotNull String subjectType,
        @NotNull List<String> toAssign,
        @NotNull List<String> toRevoke
    ) {
        if (toAssign.isEmpty() && toRevoke.isEmpty()) {
            return;
        }
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    if ("db".equals(subjectType)) {
                        WeaviateRbacRest.assignRolesToUser(dataSource, subjectId, toAssign, "db");
                        WeaviateRbacRest.revokeRolesFromUser(dataSource, subjectId, toRevoke, "db");
                    } else {
                        WeaviateRbacRest.assignRolesToGroup(dataSource, subjectId, toAssign);
                        WeaviateRbacRest.revokeRolesFromGroup(dataSource, subjectId, toRevoke);
                    }
                    WeaviateRbacRefresh.after(monitor, dataSource);
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot change roles of " + subjectId, e.getTargetException());
            DBWorkbench.getPlatformUI().showError(TITLE,
                "Cannot change the roles of " + subjectId, e.getTargetException());
        } catch (InterruptedException e) {
            // Cancelled partway; whatever was sent stands and the refresh shows it.
        }
    }



    private void rotateKey(
        @NotNull ExecutionEvent event,
        @NotNull WeaviateDataSource dataSource,
        @Nullable WeaviateDbUser user
    ) {
        if (!requireOne(user, "rotate the key for") || refuseEnvUser(user, "rotate the key of")) {
            return;
        }
        if (!DBWorkbench.getPlatformUI().confirmAction(TITLE, MessageFormat.format(
            "Rotate the API key of {0}? The current key stops working at once, and anything "
                + "using it will need the new one.", user.getName()), "Rotate", true)) {
            return;
        }
        String[] key = new String[1];
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    key[0] = WeaviateRbacRest.rotateDbUserKey(dataSource, user.getName());
                    WeaviateRbacRefresh.after(monitor, dataSource);
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot rotate the key of " + user.getName(), e.getTargetException());
            DBWorkbench.getPlatformUI().showError(TITLE,
                "Cannot rotate the key of " + user.getName(), e.getTargetException());
            return;
        } catch (InterruptedException e) {
            return;
        }
        if (key[0] != null) {
            new WeaviateApiKeyDialog(
                HandlerUtil.getActiveShell(event), user.getName(), key[0], true).open();
        }
    }

    /** One write plus a refresh, reporting whatever the server said. */
    private interface Write {
        void run(org.jkiss.dbeaver.model.runtime.DBRProgressMonitor monitor) throws DBException;
    }

    private void run(
        @NotNull ExecutionEvent event,
        @NotNull WeaviateDataSource dataSource,
        @NotNull String what,
        @NotNull Write write
    ) {
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    write.run(monitor);
                    WeaviateRbacRefresh.after(monitor, dataSource);
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot " + what, e.getTargetException());
            DBWorkbench.getPlatformUI().showError(TITLE, "Cannot " + what, e.getTargetException());
        } catch (InterruptedException e) {
            // Cancelled.
        }
    }
}
