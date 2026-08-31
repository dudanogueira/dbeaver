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
import org.eclipse.jface.viewers.ISelection;
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
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateApiKeyDialog;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateRbacRefresh;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import java.util.Map;
import org.jkiss.dbeaver.ui.UIIcon;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.dialogs.EnterNameDialog;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

/**
 * Actions on the Users folder itself: create one, or act on all of them.
 * <p>
 * Separate from {@link WeaviateDbUserHandler} because {@code setEnabled} is given an evaluation
 * context and no way to see which {@code operation} an invocation carries. A single handler
 * covering both scopes has to enable every operation wherever any of them applies, which is how
 * "Deactivate User" came to appear on this folder and do nothing but ask for a user.
 */
public class WeaviateUsersFolderHandler extends AbstractHandler implements IElementUpdater {

    private static final Log log = Log.getLog(WeaviateUsersFolderHandler.class);

    private static final String PARAM_OPERATION = "operation";

    @Override
    public void setEnabled(Object evaluationContext) {
        // Shape only: whether the folder holds anything worth acting on would mean reading it,
        // on the UI thread, while the menu is being built.
        ISelection selection = WeaviateSecurityNodes.selectionOf(evaluationContext);
        setBaseEnabled(selection != null
            && WeaviateSecurityNodes.dataSourceOf(selection) != null
            && isUsersFolder(selection));
    }

    private static boolean isUsersFolder(@Nullable ISelection selection) {
        if (selection == null) {
            return false;
        }
        List<DBNNode> nodes = NavigatorUtils.getSelectedNodes(selection);
        return nodes.size() == 1
            && "dbUsers".equals(WeaviateSecurityNodes.folderId(nodes.get(0)));
    }

    @Override
    public Object execute(ExecutionEvent event) {
        String operation = event.getParameter(PARAM_OPERATION);
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        WeaviateDataSource dataSource = WeaviateSecurityNodes.dataSourceOf(selection);
        if (dataSource == null) {
            return null;
        }
        switch (operation == null ? "" : operation) {
            case "create" -> createUser(event, dataSource);
            case "activateAll" -> onAll(event, dataSource,
                WeaviateUserActions.Operation.ACTIVATE);
            case "deactivateAll" -> onAll(event, dataSource,
                WeaviateUserActions.Operation.DEACTIVATE);
            default -> log.debug("Unknown users folder operation: " + operation);
        }
        return null;
    }

    /**
     * Reads the folder before acting, rather than using whatever the tree happens to hold.
     * <p>
     * "All users" has to mean all of them, including any the tree has not been expanded to show;
     * a folder-level action that quietly meant "the ones already loaded" would be worse than not
     * offering it.
     */
    private void onAll(
        @NotNull ExecutionEvent event,
        @NotNull WeaviateDataSource dataSource,
        @NotNull WeaviateUserActions.Operation operation
    ) {
        List<WeaviateDbUser> users = new ArrayList<>();
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    users.addAll(WeaviateUserActions.allUsers(dataSource, monitor));
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            DBWorkbench.getPlatformUI().showError(WeaviateUserActions.TITLE,
                "Cannot read the user list", e.getTargetException());
            return;
        } catch (InterruptedException e) {
            return;
        }
        WeaviateUserActions.run(
            HandlerUtil.getActiveShell(event), dataSource, users, operation);
    }

    @Override
    public void updateElement(UIElement element, @SuppressWarnings("rawtypes") Map parameters) {
        Object operation = parameters == null ? null : parameters.get(PARAM_OPERATION);
        if (!(operation instanceof String op)) {
            return;
        }
        switch (op) {
            case "create" -> element.setIcon(DBeaverIcons.getImageDescriptor(UIIcon.ADD));
            case "activateAll" -> element.setIcon(DBeaverIcons.getImageDescriptor(UIIcon.ACCEPT));
            case "deactivateAll" -> element.setIcon(DBeaverIcons.getImageDescriptor(UIIcon.REJECT));
            default -> {
                // Keeps the command icon.
            }
        }
    }

    private void createUser(@NotNull ExecutionEvent event, @NotNull WeaviateDataSource dataSource) {
        String userId = EnterNameDialog.chooseName(
            HandlerUtil.getActiveShell(event), "New database user", "");
        if (userId == null || userId.isBlank()) {
            return;
        }
        String trimmed = userId.trim();
        String[] key = new String[1];
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    key[0] = WeaviateRbacRest.createDbUser(dataSource, trimmed);
                    WeaviateRbacRefresh.after(monitor, dataSource);
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot create user " + trimmed, e.getTargetException());
            DBWorkbench.getPlatformUI().showError(WeaviateUserActions.TITLE,
                "Cannot create the user " + trimmed, e.getTargetException());
            return;
        } catch (InterruptedException e) {
            return;
        }
        if (key[0] != null) {
            // Shown once, because the server will not show it again.
            new WeaviateApiKeyDialog(
                HandlerUtil.getActiveShell(event), trimmed, key[0], false).open();
        }
    }
}
