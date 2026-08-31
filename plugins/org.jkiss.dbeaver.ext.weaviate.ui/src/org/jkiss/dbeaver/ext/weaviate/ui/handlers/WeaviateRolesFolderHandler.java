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
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateRbacAction;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateRbacRest;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateRoleRule;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateRbacRefresh;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateRoleDialog;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIIcon;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;

/**
 * Creating a role, from the Roles folder.
 * <p>
 * Separate from {@link WeaviateRoleHandler} for the reason given there: {@code setEnabled} cannot
 * see which operation an invocation carries, so one handler across both scopes would have to
 * offer every operation in both places.
 */
public class WeaviateRolesFolderHandler extends AbstractHandler implements IElementUpdater {

    private static final Log log = Log.getLog(WeaviateRolesFolderHandler.class);

    @Override
    public void setEnabled(Object evaluationContext) {
        ISelection selection = WeaviateSecurityNodes.selectionOf(evaluationContext);
        setBaseEnabled(selection != null
            && WeaviateSecurityNodes.dataSourceOf(selection) != null
            && isRolesFolder(selection));
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
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        WeaviateDataSource dataSource = WeaviateSecurityNodes.dataSourceOf(selection);
        if (dataSource == null) {
            return null;
        }
        WeaviateRoleDialog dialog = new WeaviateRoleDialog(
            HandlerUtil.getActiveShell(event), null, List.of(), false,
            WeaviateRbacAction.editableDomains());
        if (dialog.open() != IDialogConstants.OK_ID) {
            return null;
        }
        String name = dialog.getRoleName();
        List<WeaviateRbacRest.PermissionInfo> permissions =
            WeaviateRoleRule.toPermissions(dialog.getRules());
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    WeaviateRbacRest.createRole(dataSource, name, permissions);
                    WeaviateRbacRefresh.after(monitor, dataSource);
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot create role " + name, e.getTargetException());
            DBWorkbench.getPlatformUI().showError(WeaviateRoleHandler.TITLE,
                "Cannot create the role " + name, e.getTargetException());
        } catch (InterruptedException e) {
            // Cancelled.
        }
        return null;
    }

    @Override
    public void updateElement(UIElement element, @SuppressWarnings("rawtypes") Map parameters) {
        element.setIcon(DBeaverIcons.getImageDescriptor(UIIcon.ADD));
    }
}
