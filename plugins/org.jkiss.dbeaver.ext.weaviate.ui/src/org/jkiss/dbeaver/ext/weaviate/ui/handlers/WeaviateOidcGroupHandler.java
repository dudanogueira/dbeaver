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
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateOidcGroup;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateRole;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateRoleAssignmentDialog;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.dialogs.EnterNameDialog;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Grants and withdraws roles for an OIDC group.
 * <p>
 * Weaviate does not own groups -- they come from the identity provider, and all it stores is which
 * roles a group id has been granted. So there is no create and no delete here, only assignments;
 * "adding a group" means naming one the provider already has and giving it roles.
 */
public class WeaviateOidcGroupHandler extends AbstractHandler {

    private static final Log log = Log.getLog(WeaviateOidcGroupHandler.class);

    private static final String PARAM_OPERATION = "operation";
    private static final String TITLE = "Weaviate group";

    /** root is granted by environment variable; the API refuses to assign it. */
    private static final Set<String> NOT_ASSIGNABLE = Set.of("root");

    @Override
    public void setEnabled(Object evaluationContext) {
        ISelection selection = WeaviateSecurityNodes.selectionOf(evaluationContext);
        setBaseEnabled(selection != null
            && WeaviateSecurityNodes.dataSourceOf(selection) != null
            && (isGroupsFolder(selection)
                || !WeaviateSecurityNodes.selectedGroups(selection).isEmpty()));
    }

    private static boolean isGroupsFolder(@Nullable ISelection selection) {
        if (selection == null) {
            return false;
        }
        List<DBNNode> nodes = NavigatorUtils.getSelectedNodes(selection);
        return nodes.size() == 1
            && "oidcGroups".equals(WeaviateSecurityNodes.folderId(nodes.get(0)));
    }

    @Override
    public Object execute(ExecutionEvent event) {
        String operation = event.getParameter(PARAM_OPERATION);
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        WeaviateDataSource dataSource = WeaviateSecurityNodes.dataSourceOf(selection);
        if (dataSource == null) {
            return null;
        }
        List<WeaviateOidcGroup> groups = WeaviateSecurityNodes.selectedGroups(selection);
        WeaviateOidcGroup group = groups.size() == 1 ? groups.get(0) : null;

        if ("revokeAll".equals(operation)) {
            revokeAll(event, dataSource, group);
            return null;
        }
        editRoles(event, dataSource, group);
        return null;
    }

    private void editRoles(
        @NotNull ExecutionEvent event,
        @NotNull WeaviateDataSource dataSource,
        @Nullable WeaviateOidcGroup group
    ) {
        String groupId = group != null ? group.getName() : EnterNameDialog.chooseName(
            HandlerUtil.getActiveShell(event), "Group id from your identity provider", "");
        if (groupId == null || groupId.isBlank()) {
            return;
        }
        String trimmed = groupId.trim();

        List<String> available = new ArrayList<>();
        Set<String> current = new LinkedHashSet<>();
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    for (WeaviateRole role : dataSource.getRoles(monitor)) {
                        if (!NOT_ASSIGNABLE.contains(role.getName())) {
                            available.add(role.getName());
                        }
                    }
                    if (group != null) {
                        current.addAll(group.getRoleNames(monitor));
                    }
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            DBWorkbench.getPlatformUI().showError(TITLE,
                "Cannot read the roles of " + trimmed, e.getTargetException());
            return;
        } catch (InterruptedException e) {
            return;
        }

        WeaviateRoleAssignmentDialog dialog = new WeaviateRoleAssignmentDialog(
            HandlerUtil.getActiveShell(event), trimmed, "Group", available, current,
            "Groups are managed by your identity provider. Weaviate stores only which roles a "
                + "group id holds, so the id must match the provider's exactly.");
        if (dialog.open() != IDialogConstants.OK_ID) {
            return;
        }
        if (group != null) {
            group.resetRoleCache();
        }
        WeaviateDbUserHandler.applyRoleChange(event, dataSource, trimmed, "oidc",
            dialog.getToAssign(), dialog.getToRevoke());
    }

    /**
     * Withdraws every role from a group.
     * <p>
     * The nearest thing to deleting a group, and the wording has to be honest about the
     * difference: the group belongs to the identity provider and carries on existing.
     */
    private void revokeAll(
        @NotNull ExecutionEvent event,
        @NotNull WeaviateDataSource dataSource,
        @Nullable WeaviateOidcGroup group
    ) {
        if (group == null) {
            DBWorkbench.getPlatformUI().showMessageBox(TITLE,
                "Select a single group.", true);
            return;
        }
        List<String> held = new ArrayList<>();
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    held.addAll(group.getRoleNames(monitor));
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            DBWorkbench.getPlatformUI().showError(TITLE,
                "Cannot read the roles of " + group.getName(), e.getTargetException());
            return;
        } catch (InterruptedException e) {
            return;
        }
        if (held.isEmpty()) {
            DBWorkbench.getPlatformUI().showMessageBox(TITLE,
                group.getName() + " holds no roles.", false);
            return;
        }
        if (!DBWorkbench.getPlatformUI().confirmAction(TITLE,
            "Withdraw all " + held.size() + " role(s) from " + group.getName()
                + "?\n\nThe group itself belongs to your identity provider and is not affected.",
            "Withdraw", true)) {
            return;
        }
        group.resetRoleCache();
        WeaviateDbUserHandler.applyRoleChange(event, dataSource, group.getName(), "oidc",
            List.of(), held);
    }
}
