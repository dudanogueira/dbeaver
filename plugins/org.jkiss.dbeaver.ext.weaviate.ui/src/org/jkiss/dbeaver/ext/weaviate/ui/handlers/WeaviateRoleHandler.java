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
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateRbacAction;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateRbacRest;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateRole;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateRoleRule;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateRbacRefresh;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateRoleDialog;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;

import java.lang.reflect.InvocationTargetException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Create, edit and delete a role. One handler, told apart by the {@code operation} parameter.
 * <p>
 * Editing saves a <em>diff</em>, never a replace. See {@link #save}.
 */
public class WeaviateRoleHandler extends AbstractHandler {

    private static final Log log = Log.getLog(WeaviateRoleHandler.class);

    private static final String PARAM_OPERATION = "operation";
    private static final String OP_CREATE = "create";
    private static final String OP_EDIT = "edit";
    private static final String OP_DELETE = "delete";

    private static final String TITLE = "Weaviate role";

    @Override
    public void setEnabled(Object evaluationContext) {
        // Shape only, and computed here rather than in an <enabledWhen>: the condition depends on
        // a navigator folder's meta id, which no built-in property tester exposes, and a tester
        // shipped from this lazily-activated bundle would evaluate as NOT_LOADED before the bundle
        // starts -- so the item would never appear and the bundle would never activate to show it.
        ISelection selection = WeaviateSecurityNodes.selectionOf(evaluationContext);
        setBaseEnabled(selection != null
            && WeaviateSecurityNodes.dataSourceOf(selection) != null
            && (isRolesFolder(selection) || WeaviateSecurityNodes.singleRole(selection) != null));
    }

    private static boolean isRolesFolder(@Nullable ISelection selection) {
        if (selection == null) {
            return false;
        }
        List<DBNNode> nodes = NavigatorUtils.getSelectedNodes(selection);
        return nodes.size() == 1
            && "roles".equals(WeaviateSecurityNodes.folderId(nodes.get(0)));
    }

    @Override
    public Object execute(ExecutionEvent event) {
        String operation = event.getParameter(PARAM_OPERATION);
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        WeaviateDataSource dataSource = WeaviateSecurityNodes.dataSourceOf(selection);
        if (dataSource == null) {
            return null;
        }
        WeaviateRole role = WeaviateSecurityNodes.singleRole(selection);

        if (OP_DELETE.equals(operation)) {
            deleteRole(event, dataSource, role);
            return null;
        }
        if (OP_EDIT.equals(operation) && role == null) {
            DBWorkbench.getPlatformUI().showMessageBox(TITLE,
                "Select a single role to edit.", true);
            return null;
        }
        editRole(event, dataSource, OP_CREATE.equals(operation) ? null : role);
        return null;
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

    private void deleteRole(
        @NotNull ExecutionEvent event,
        @NotNull WeaviateDataSource dataSource,
        @Nullable WeaviateRole role
    ) {
        if (role == null) {
            DBWorkbench.getPlatformUI().showMessageBox(TITLE,
                "Select a single role to delete.", true);
            return;
        }
        if (role.isBuiltIn()) {
            DBWorkbench.getPlatformUI().showMessageBox(TITLE, MessageFormat.format(
                "{0} is a built-in role. Weaviate does not allow it to be deleted.",
                role.getName()), true);
            return;
        }
        if (!DBWorkbench.getPlatformUI().confirmAction(TITLE, MessageFormat.format(
            "Delete the role {0}? Anyone holding it loses what it granted. This cannot be undone.",
            role.getName()), "Delete", true)) {
            return;
        }
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    WeaviateRbacRest.deleteRole(dataSource, role.getName());
                    WeaviateRbacRefresh.after(monitor, dataSource);
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot delete role " + role.getName(), e.getTargetException());
            DBWorkbench.getPlatformUI().showError(TITLE,
                "Cannot delete the role " + role.getName(), e.getTargetException());
        } catch (InterruptedException e) {
            // Cancelled.
        }
    }
}
