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
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateTenant;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateTenantSelectDialog;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIUtils;

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

    /**
     * Offered only where there is a tenant to pick.
     * <p>
     * This used to be an {@code <enabledWhen>} testing {@code instanceof WeaviateCollection}, which
     * put the entry on every collection and left "this one has no tenants" to a message box after
     * the click. Whether a collection is multi-tenant is a permanent fact about it, so the entry
     * belongs to the standing rule the rest of this branch follows: hide what can never apply, and
     * explain only what merely does not apply yet. A core expression cannot ask the question --
     * it has no way to call {@code isMultiTenant()} -- so the test moves here, where the other
     * tenancy handlers already make theirs.
     */
    @Override
    public void setEnabled(Object evaluationContext) {
        setBaseEnabled(WeaviateTenancyNodes.selectedCollections(
            WeaviateTenancyNodes.selectionOf(evaluationContext)).size() == 1);
    }

    @Override
    public Object execute(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        // Resolved the same way the enablement was, so the action works from wherever it appears:
        // the collection row, its Multi-Tenancy folder, or one of the setting rows inside it.
        List<WeaviateCollection> collections = WeaviateTenancyNodes.selectedCollections(selection);
        if (collections.size() != 1) {
            return null;
        }
        WeaviateCollection collection = collections.get(0);
        Shell shell = HandlerUtil.getActiveShell(event);
        if (!collection.isMultiTenant()) {
            // Unreachable through the menu now that enablement asks the same question. Kept for a
            // selection that went stale between the menu opening and the click.
            DBWorkbench.getPlatformUI().showMessageBox(
                WeaviateUIMessages.tenant_dialog_title_plain,
                MessageFormat.format(WeaviateUIMessages.tenant_not_multi_tenant, collection.getName()),
                false);
            return null;
        }

        // Listing can be slow on a collection with many tenants, so it runs with a progress
        // dialog rather than freezing the workbench.
        List<WeaviateTenant>[] holder = new List[1];
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    holder[0] = collection.listTenants(monitor);
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

        List<WeaviateTenant> tenants = holder[0];
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
