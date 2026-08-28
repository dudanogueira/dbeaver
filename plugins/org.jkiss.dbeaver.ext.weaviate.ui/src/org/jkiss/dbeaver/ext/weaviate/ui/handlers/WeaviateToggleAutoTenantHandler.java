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
import org.eclipse.ui.ISources;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateServerFeature;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseFolder;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;
import org.jkiss.dbeaver.ui.navigator.actions.NavigatorHandlerRefresh;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;

/**
 * Turns automatic tenant creation or activation on and off from the Multi-Tenancy folder, where
 * the current values are already on display.
 * <p>
 * Checkable menu items rather than a dialog: each is one boolean, the folder beneath already
 * lists what they are, and a dialog to tick one box is a dialog too many. The tick shows the
 * current value, so opening the menu is also how the settings are read.
 * <p>
 * One command with an {@code option} parameter rather than two commands. The two settings differ
 * only in which field they write; everything around that -- resolving the collection, gating on
 * version, sending both values together, refreshing the tree -- is the same. The parameter is
 * also what {@link #updateElement} needs, since a {@link UIElement} does not carry a command id.
 */
public class WeaviateToggleAutoTenantHandler extends AbstractHandler implements IElementUpdater {

    private static final Log log = Log.getLog(WeaviateToggleAutoTenantHandler.class);

    /** Command parameter naming which setting an invocation is about. */
    public static final String PARAM_OPTION = "option";
    public static final String OPTION_CREATION = "creation";

    /** The folder these commands belong to. */
    private static final String MULTI_TENANCY_FOLDER = "multiTenancy";

    /**
     * The collection whose Multi-Tenancy folder is selected, or null for anything else.
     * <p>
     * Deliberately narrower than Manage Tenants, which answers from four different nodes. These
     * two settings are what the Multi-Tenancy folder is <em>for</em>, and putting them on the
     * collection as well would add two more entries to a menu that already carries several.
     */
    @Nullable
    private static WeaviateCollection collectionOf(@Nullable DBNNode node) {
        if (!(node instanceof DBNDatabaseFolder folder)
            || !MULTI_TENANCY_FOLDER.equals(folder.getNodeId())
            || !(folder.getParentObject() instanceof WeaviateCollection collection)
            || !collection.isMultiTenant()
        ) {
            return null;
        }
        // A server without these settings reports neither, and a tick that cannot mean anything
        // is worse than no entry at all.
        return collection.getDataSource() instanceof WeaviateDataSource ds
            && ds.supports(WeaviateServerFeature.AUTO_TENANT_CREATION)
            ? collection : null;
    }

    @Nullable
    private static DBNNode selectionFrom(Object evaluationContext) {
        if (!(evaluationContext instanceof IEvaluationContext context)) {
            return null;
        }
        Object selection = context.getVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME);
        return selection instanceof ISelection sel ? NavigatorUtils.getSelectedNode(sel) : null;
    }

    @Override
    public void setEnabled(Object evaluationContext) {
        setBaseEnabled(collectionOf(selectionFrom(evaluationContext)) != null);
    }

    private static boolean current(@NotNull WeaviateCollection collection, boolean creation) {
        Boolean value = creation
            ? collection.getAutoTenantCreation()
            : collection.getAutoTenantActivation();
        return Boolean.TRUE.equals(value);
    }

    @Override
    public Object execute(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        DBNNode node = NavigatorUtils.getSelectedNode(selection);
        WeaviateCollection collection = collectionOf(node);
        if (collection == null) {
            return null;
        }
        boolean creation = OPTION_CREATION.equals(event.getParameter(PARAM_OPTION));
        // Both values always travel together: the server takes the multi-tenancy block whole, so
        // sending only the one that changed resets the other.
        boolean wantCreation = creation ? !current(collection, true) : current(collection, true);
        boolean wantActivation = creation ? current(collection, false) : !current(collection, false);

        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    collection.setAutoTenantOptions(monitor, wantCreation, wantActivation);
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot update multi-tenancy settings", e.getTargetException());
            DBWorkbench.getPlatformUI().showError(
                WeaviateUIMessages.tenant_manage_auto_group,
                WeaviateUIMessages.tenant_manage_auto_failed,
                e.getTargetException());
            return null;
        } catch (InterruptedException e) {
            return null;
        }
        // The folder lists these values as rows, and they were just rewritten.
        if (node != null) {
            NavigatorHandlerRefresh.refreshNavigator(List.of(node));
        }
        return null;
    }

    /**
     * Draws the tick, and names the setting. Both come from the collection rather than from static
     * text, so a menu opened on a collection whose settings changed elsewhere is still right.
     */
    @Override
    public void updateElement(UIElement element, Map parameters) {
        IWorkbenchWindow window = element.getServiceLocator().getService(IWorkbenchWindow.class);
        if (window == null || window.getSelectionService() == null) {
            return;
        }
        WeaviateCollection collection =
            collectionOf(NavigatorUtils.getSelectedNode(window.getSelectionService().getSelection()));
        if (collection == null) {
            return;
        }
        boolean creation = OPTION_CREATION.equals(parameters.get(PARAM_OPTION));
        element.setText(creation
            ? WeaviateUIMessages.tenant_auto_creation_menu
            : WeaviateUIMessages.tenant_auto_activation_menu);
        element.setChecked(current(collection, creation));
    }
}
