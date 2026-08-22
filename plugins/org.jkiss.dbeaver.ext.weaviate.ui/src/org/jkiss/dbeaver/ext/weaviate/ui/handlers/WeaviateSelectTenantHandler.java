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
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateTenantSelectDialog;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;

import java.lang.reflect.InvocationTargetException;
import java.text.MessageFormat;
import java.util.List;

/**
 * Chooses the tenant a multi-tenant collection's data view reads from.
 * <p>
 * Lives on the collection's context menu rather than only in the Query panel: the panel is not
 * necessarily open -- and for a multi-tenant collection the data view shows nothing at all until
 * a tenant is picked, so the way to pick one cannot be hidden behind a panel the user has to
 * know to open.
 */
public class WeaviateSelectTenantHandler extends AbstractHandler {

    private static final Log log = Log.getLog(WeaviateSelectTenantHandler.class);

    @Override
    public Object execute(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        DBNNode node = NavigatorUtils.getSelectedNode(selection);
        if (!(node instanceof DBNDatabaseNode databaseNode)
            || !(databaseNode.getObject() instanceof WeaviateCollection collection)) {
            return null;
        }
        Shell shell = HandlerUtil.getActiveShell(event);
        if (!collection.isMultiTenant()) {
            DBWorkbench.getPlatformUI().showMessageBox(
                WeaviateUIMessages.tenant_dialog_title_plain,
                MessageFormat.format(WeaviateUIMessages.tenant_not_multi_tenant, collection.getName()),
                false);
            return null;
        }

        // Listing can be slow on a collection with many tenants, so it runs with a progress
        // dialog rather than freezing the workbench.
        List<String>[] holder = new List[1];
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    holder[0] = collection.listTenantNames(monitor);
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot list tenants", e.getTargetException());
            DBWorkbench.getPlatformUI().showError(
                WeaviateUIMessages.tenant_dialog_title_plain,
                WeaviateUIMessages.tenant_list_failed,
                e.getTargetException());
            return null;
        } catch (InterruptedException e) {
            return null;
        }

        List<String> tenants = holder[0];
        if (tenants == null || tenants.isEmpty()) {
            DBWorkbench.getPlatformUI().showMessageBox(
                WeaviateUIMessages.tenant_dialog_title_plain,
                WeaviateUIMessages.query_tenant_none,
                false);
            return null;
        }

        WeaviateTenantSelectDialog dialog = new WeaviateTenantSelectDialog(
            shell, collection.getName(), tenants, collection.getQuerySpec().getTenant());
        if (dialog.open() != IDialogConstants.OK_ID || dialog.getSelectedTenant() == null) {
            return null;
        }
        collection.setQuerySpec(collection.getQuerySpec().withTenant(dialog.getSelectedTenant()));
        DBWorkbench.getPlatformUI().showMessageBox(
            WeaviateUIMessages.tenant_dialog_title_plain,
            MessageFormat.format(
                WeaviateUIMessages.tenant_selected, dialog.getSelectedTenant(), collection.getName()),
            false);
        return null;
    }
}
