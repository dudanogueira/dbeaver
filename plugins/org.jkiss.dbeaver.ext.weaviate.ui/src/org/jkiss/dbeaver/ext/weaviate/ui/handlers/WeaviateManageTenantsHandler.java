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
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateTenant;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateTenantNode;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateTenantManageDialog;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

/**
 * Opens the tenant list of a multi-tenant collection for activating and deactivating.
 * <p>
 * Separate from "Select Tenant...", which answers a different question. That one picks which
 * tenant the Data tab reads and changes nothing on the server; this one changes tenants and does
 * not touch what is being read.
 * <p>
 * Reachable from every node a person might be looking at when the thought occurs: the collection,
 * its Multi-Tenancy folder, the Tenants folder inside it, and a tenant itself. They are four
 * views of one thing, and having the action on only one of them means guessing which.
 * <p>
 * Enablement is computed in {@link #setEnabled} rather than by an {@code <enabledWhen>}
 * expression. Folders are matched on their meta id, which no built-in property tester exposes,
 * and a tester contributed from this lazily-activated bundle would evaluate as
 * {@code NOT_LOADED} before the bundle starts -- so the item would never appear and the bundle
 * would never activate to make it appear. {@code WeaviateReadDocsHandler} records the same.
 */
public class WeaviateManageTenantsHandler extends AbstractHandler {

    private static final Log log = Log.getLog(WeaviateManageTenantsHandler.class);

    /**
     * Tenants to arrive selected: every tenant node in the selection, so picking several in the
     * tree and choosing Manage Tenants carries all of them through rather than only the one the
     * menu happened to be opened over.
     */
    @NotNull
    private static List<String> tenantsOf(@NotNull ISelection selection) {
        List<String> names = new ArrayList<>();
        for (WeaviateTenantNode tenant : WeaviateTenancyNodes.selectedTenants(selection)) {
            names.add(tenant.getName());
        }
        return names;
    }

    @Override
    public void setEnabled(Object evaluationContext) {
        setBaseEnabled(WeaviateTenancyNodes.multiTenantCollection(
            WeaviateTenancyNodes.selectedNode(evaluationContext)) != null);
    }

    @Override
    public Object execute(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        DBNNode node = NavigatorUtils.getSelectedNode(selection);
        WeaviateCollection collection = WeaviateTenancyNodes.multiTenantCollection(node);
        if (collection == null) {
            return null;
        }
        List<String> initialTenants = tenantsOf(selection);
        Shell shell = HandlerUtil.getActiveShell(event);

        // One request, but it crosses the network, so it runs with a progress dialog rather
        // than freezing the workbench.
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
                WeaviateUIMessages.tenant_manage_title_plain,
                WeaviateUIMessages.tenant_list_failed,
                e.getTargetException());
            return null;
        } catch (InterruptedException e) {
            return null;
        }

        List<WeaviateTenant> tenants = holder[0];
        if (tenants == null || tenants.isEmpty()) {
            DBWorkbench.getPlatformUI().showMessageBox(
                WeaviateUIMessages.tenant_manage_title_plain,
                WeaviateUIMessages.tenant_manage_none,
                false);
            return null;
        }
        new WeaviateTenantManageDialog(shell, collection, tenants, initialTenants).open();
        return null;
    }
}
