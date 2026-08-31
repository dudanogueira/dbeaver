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
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateRbacAction;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateRbacRest;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateRole;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateRoleRule;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateChangeConfirmDialog;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateRbacRefresh;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateRoleDialog;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIIcon;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;

import java.lang.reflect.InvocationTargetException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Editing and deleting roles picked in the tree.
 * <p>
 * Creating one lives in {@link WeaviateRolesFolderHandler}, because
 * {@code AbstractHandler#setEnabled} is handed an evaluation context with no way to see which
 * {@code operation} an invocation carries. A single handler spanning both scopes has to enable
 * every operation wherever any of them applies, which put "Delete Role" on the Roles folder where
 * it could only ask for a role.
 * <p>
 * Delete takes a multi-selection; editing is about one role by its nature.
 * <p>
 * Editing saves a <em>diff</em>, never a replace. See {@link #save}.
 */
public class WeaviateRoleHandler extends AbstractHandler implements IElementUpdater {

    private static final Log log = Log.getLog(WeaviateRoleHandler.class);

    private static final String PARAM_OPERATION = "operation";
    private static final String OP_EDIT = "edit";
    private static final String OP_DELETE = "delete";

    static final String TITLE = "Weaviate roles";

    @Override
    public void setEnabled(Object evaluationContext) {
        // Shape only, and computed here rather than in an <enabledWhen>: the condition depends on
        // a navigator folder's meta id, which no built-in property tester exposes, and a tester
        // shipped from this lazily-activated bundle would evaluate as NOT_LOADED before the bundle
        // starts -- so the item would never appear and the bundle would never activate to show it.
        ISelection selection = WeaviateSecurityNodes.selectionOf(evaluationContext);
        setBaseEnabled(selection != null
            && WeaviateSecurityNodes.dataSourceOf(selection) != null
            && (!WeaviateSecurityNodes.selectedRoles(selection).isEmpty()
                || WeaviateSecurityNodes.singleRole(selection) != null));
    }

    @Override
    public Object execute(ExecutionEvent event) {
        String operation = event.getParameter(PARAM_OPERATION);
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        WeaviateDataSource dataSource = WeaviateSecurityNodes.dataSourceOf(selection);
        if (dataSource == null) {
            return null;
        }
        if (OP_DELETE.equals(operation)) {
            deleteRoles(event, dataSource, WeaviateSecurityNodes.selectedRoles(selection));
            return null;
        }
        WeaviateRole role = WeaviateSecurityNodes.singleRole(selection);
        if (OP_EDIT.equals(operation) && role == null) {
            DBWorkbench.getPlatformUI().showMessageBox(TITLE,
                "Select a single role to edit.", true);
            return null;
        }
        editRole(event, dataSource, role);
        return null;
    }

    /**
     * One command serves both entries, so without this they would share the icon from
     * {@code <commandImages>} and read as the same action twice. Delete also names how many roles
     * it would remove, from what the tree already holds -- no fetching, this runs while the menu
     * is being built.
     */
    @Override
    public void updateElement(UIElement element, @SuppressWarnings("rawtypes") Map parameters) {
        Object operation = parameters == null ? null : parameters.get(PARAM_OPERATION);
        if (!(operation instanceof String op)) {
            return;
        }
        if (OP_EDIT.equals(op)) {
            element.setIcon(DBeaverIcons.getImageDescriptor(UIIcon.EDIT));
            return;
        }
        if (!OP_DELETE.equals(op)) {
            return;
        }
        element.setIcon(DBeaverIcons.getImageDescriptor(UIIcon.DELETE));
        IWorkbenchWindow window = element.getServiceLocator().getService(IWorkbenchWindow.class);
        if (window == null || window.getSelectionService() == null) {
            return;
        }
        int count = WeaviateSecurityNodes
            .selectedRoles(window.getSelectionService().getSelection()).size();
        element.setText(count > 1 ? "Delete " + count + " Roles" : "Delete Role");
    }

    private void editRole(
        @NotNull ExecutionEvent event,
        @NotNull WeaviateDataSource dataSource,
        @Nullable WeaviateRole role
    ) {
        List<WeaviateRoleRule> rules = role == null
            ? List.of()
            : WeaviateRoleRule.fromPermissions(role.getPermissionInfos());
        WeaviateRoleDialog dialog = new WeaviateRoleDialog(
            HandlerUtil.getActiveShell(event),
            role == null ? null : role.getName(),
            rules,
            role != null && role.isBuiltIn(),
            WeaviateRbacAction.editableDomains());
        if (dialog.open() != IDialogConstants.OK_ID) {
            return;
        }
        String name = dialog.getRoleName();
        List<WeaviateRbacRest.PermissionInfo> wanted =
            WeaviateRoleRule.toPermissions(dialog.getRules());

        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    if (role == null) {
                        WeaviateRbacRest.createRole(dataSource, name, wanted);
                    } else {
                        save(dataSource, name, wanted);
                    }
                    WeaviateRbacRefresh.after(monitor, dataSource);
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot save role " + name, e.getTargetException());
            DBWorkbench.getPlatformUI().showError(TITLE,
                "Cannot save the role " + name, e.getTargetException());
        } catch (InterruptedException e) {
            // Cancelled; whatever the server already accepted stands and the refresh will show it.
        }
    }

    /**
     * Applies the wanted permissions as a difference against what the role holds now.
     * <p>
     * Three reasons this is not a remove-everything-then-add:
     * <ul>
     *   <li>there is no transaction, so a replace leaves a window in which the role grants
     *       nothing, and anyone holding it loses access for the length of it;</li>
     *   <li>the role is re-read here rather than trusted from when the dialog opened, so an edit
     *       made next to somebody else's is merged instead of reverting theirs;</li>
     *   <li>a permission the editor could not draw -- from a newer server, or a legacy spelling --
     *       is in neither the add set nor the remove set, so it is left exactly as it was.</li>
     * </ul>
     */
    private static void save(
        @NotNull WeaviateDataSource dataSource,
        @NotNull String name,
        @NotNull List<WeaviateRbacRest.PermissionInfo> wanted
    ) throws DBException {
        WeaviateRbacRest.RoleInfo current = WeaviateRbacRest.getRole(dataSource, name);
        List<WeaviateRbacRest.PermissionInfo> held =
            current == null ? List.of() : current.permissions();

        Set<String> wantedKeys = new HashSet<>();
        for (WeaviateRbacRest.PermissionInfo permission : wanted) {
            wantedKeys.add(permission.key());
        }
        Set<String> heldKeys = new HashSet<>();
        for (WeaviateRbacRest.PermissionInfo permission : held) {
            heldKeys.add(permission.key());
        }

        List<WeaviateRbacRest.PermissionInfo> toAdd = new ArrayList<>();
        for (WeaviateRbacRest.PermissionInfo permission : wanted) {
            if (!heldKeys.contains(permission.key())) {
                toAdd.add(permission);
            }
        }
        List<WeaviateRbacRest.PermissionInfo> toRemove = new ArrayList<>();
        for (WeaviateRbacRest.PermissionInfo permission : held) {
            if (!wantedKeys.contains(permission.key())) {
                toRemove.add(permission);
            }
        }
        // Added before removed: at no point does the role hold less than both versions grant.
        WeaviateRbacRest.addPermissions(dataSource, name, toAdd);
        WeaviateRbacRest.removePermissions(dataSource, name, toRemove);
    }

    /**
     * Deletes the selected roles, leaving the built-in ones alone.
     * <p>
     * A built-in role is named rather than silently skipped: somebody who selected admin along
     * with three of their own deserves to know why only three went. The server refuses it with a
     * 400 in any case, so sending it would only trade a clear sentence for a status code.
     */
    private void deleteRoles(
        @NotNull ExecutionEvent event,
        @NotNull WeaviateDataSource dataSource,
        @NotNull List<WeaviateRole> roles
    ) {
        if (roles.isEmpty()) {
            DBWorkbench.getPlatformUI().showMessageBox(TITLE, "Select a role to delete.", true);
            return;
        }
        // Built-in roles stay in the list rather than being filtered out of it: somebody who
        // selected admin alongside three of their own should see admin there, marked, not simply
        // find that three went.
        List<WeaviateChangeConfirmDialog.Row> rows = new ArrayList<>();
        List<WeaviateRole> targets = new ArrayList<>();
        for (WeaviateRole role : roles) {
            boolean included = !role.isBuiltIn();
            rows.add(WeaviateChangeConfirmDialog.Row.of(role, "Role",
                included ? "will be deleted" : "left alone: built-in, Weaviate refuses to delete it",
                included));
            if (included) {
                targets.add(role);
            }
        }
        if (targets.isEmpty()) {
            StringBuilder message = new StringBuilder("Nothing to delete.\n\n");
            for (WeaviateChangeConfirmDialog.Row row : rows) {
                message.append(row.name()).append(" - ").append(row.outcome()).append('\n');
            }
            DBWorkbench.getPlatformUI().showMessageBox(TITLE, message.toString(), false);
            return;
        }

        WeaviateChangeConfirmDialog dialog = new WeaviateChangeConfirmDialog(
            HandlerUtil.getActiveShell(event), TITLE,
            MessageFormat.format(
                "Delete {0} role(s)?\n\nAnyone holding them loses what they granted. "
                    + "This cannot be undone.", targets.size()),
            rows, "Delete");
        if (dialog.open() != IDialogConstants.OK_ID) {
            return;
        }

        Map<String, String> failed = new LinkedHashMap<>();
        try {
            UIUtils.runInProgressService(monitor -> {
                monitor.beginTask("Delete " + targets.size() + " role(s)", targets.size());
                try {
                    for (WeaviateRole role : targets) {
                        if (monitor.isCanceled()) {
                            break;
                        }
                        monitor.subTask(role.getName());
                        try {
                            WeaviateRbacRest.deleteRole(dataSource, role.getName());
                        } catch (DBException e) {
                            // One refusal should not abandon the rest.
                            log.error("Cannot delete role " + role.getName(), e);
                            failed.put(role.getName(), e.getMessage() == null
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
                "Cannot delete the selected roles", e.getTargetException());
            return;
        } catch (InterruptedException e) {
            // Cancelled partway; what was deleted stays deleted and the refresh shows it.
        }
        if (!failed.isEmpty()) {
            StringBuilder report = new StringBuilder("These roles were not deleted:\n\n");
            failed.forEach((name, reason) ->
                report.append(name).append(" - ").append(reason).append('\n'));
            DBWorkbench.getPlatformUI().showMessageBox(TITLE, report.toString(), true);
        }
    }
}
