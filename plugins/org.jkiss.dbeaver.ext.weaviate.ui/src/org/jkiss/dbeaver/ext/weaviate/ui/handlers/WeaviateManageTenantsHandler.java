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
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateTenant;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateTenantNode;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateTenantManageDialog;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseFolder;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;

import java.lang.reflect.InvocationTargetException;
import java.text.MessageFormat;
import java.util.List;
import java.util.Set;

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

    /** Folder meta ids that stand for the same collection's tenancy. */
    private static final Set<String> TENANCY_FOLDERS = Set.of("multiTenancy", "tenants");

    /**
     * The collection a node is talking about, whether the node is the collection, one of its
     * tenancy folders, or a single tenant.
     */
    @Nullable
    private static WeaviateCollection collectionOf(@Nullable DBNNode node) {
        if (node instanceof DBNDatabaseFolder folder) {
            if (!TENANCY_FOLDERS.contains(folder.getNodeId())) {
                return null;
            }
            // A folder node's getObject() returns the folder itself, not what it hangs under;
            // getParentObject() is the collection.
            return folder.getParentObject() instanceof WeaviateCollection collection
                ? collection : null;
        }
        if (node instanceof DBNDatabaseNode databaseNode) {
            DBSObject object = databaseNode.getObject();
            if (object instanceof WeaviateCollection collection) {
                return collection;
            }
            if (object instanceof WeaviateTenantNode tenant
                && tenant.getParentObject() instanceof WeaviateCollection collection) {
                return collection;
            }
        }
        return null;
    }

    /** The tenant to arrive selected, when the action was invoked on one. */
    @Nullable
    private static String tenantOf(@Nullable DBNNode node) {
        return node instanceof DBNDatabaseNode databaseNode
            && databaseNode.getObject() instanceof WeaviateTenantNode tenant
            ? tenant.getName()
            : null;
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

    @Override
    public Object execute(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        DBNNode node = NavigatorUtils.getSelectedNode(selection);
        WeaviateCollection collection = collectionOf(node);
        if (collection == null) {
            return null;
        }
        String initialTenant = tenantOf(node);
        Shell shell = HandlerUtil.getActiveShell(event);
        if (!collection.isMultiTenant()) {
            DBWorkbench.getPlatformUI().showMessageBox(
                WeaviateUIMessages.tenant_manage_title_plain,
                MessageFormat.format(WeaviateUIMessages.tenant_not_multi_tenant, collection.getName()),
                false);
            return null;
        }

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
        new WeaviateTenantManageDialog(shell, collection, tenants, initialTenant).open();
        return null;
    }
}
