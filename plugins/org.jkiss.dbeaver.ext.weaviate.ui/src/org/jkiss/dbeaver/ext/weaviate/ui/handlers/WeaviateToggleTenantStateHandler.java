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
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateTenant;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateTenantNode;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateTenantStatus;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateNavigatorRefresh;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIIcon;
import org.jkiss.dbeaver.ui.UIUtils;

import java.lang.reflect.InvocationTargetException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Activates or deactivates the selected tenants straight from the tree.
 * <p>
 * One entry rather than two, naming the action it will perform. A menu offering both "Activate"
 * and "Deactivate" makes the reader work out which one applies; an entry that already says
 * "Deactivate Tenant" has answered that before it is read.
 * <p>
 * With a mixed selection the offer is Activate. That is the direction that restores access: an
 * unwanted activate costs memory until it is undone, an unwanted deactivate makes data unreadable
 * for whoever was using it.
 */
public class WeaviateToggleTenantStateHandler extends AbstractHandler implements IElementUpdater {

    private static final Log log = Log.getLog(WeaviateToggleTenantStateHandler.class);

    /**
     * What pressing the entry would do to this selection: activate unless everything in it is
     * already active.
     */
    private static boolean wouldActivate(@NotNull List<WeaviateTenantNode> tenants) {
        for (WeaviateTenantNode tenant : tenants) {
            if (tenant.getTenantStatus() != WeaviateTenantStatus.ACTIVE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Selected tenants, but only when the whole selection is tenants of one multi-tenant
     * collection. A selection spanning two collections cannot be one request, and mixing a tenant
     * with something else is not an instruction anyone meant to give.
     */
    @NotNull
    private static List<WeaviateTenantNode> subjects(@Nullable ISelection selection) {
        List<WeaviateTenantNode> tenants = WeaviateTenancyNodes.selectedTenants(selection);
        if (tenants.isEmpty()) {
            return List.of();
        }
        WeaviateCollection owner = null;
        for (WeaviateTenantNode tenant : tenants) {
            if (!(tenant.getParentObject() instanceof WeaviateCollection collection)) {
                return List.of();
            }
            if (owner == null) {
                owner = collection;
            } else if (owner != collection) {
                return List.of();
            }
            if (!tenant.getTenantStatus().isSettable()) {
                // Offloading and onloading are the server's business; there is nothing to ask for.
                return List.of();
            }
        }
        return tenants;
    }

    @Override
    public void setEnabled(Object evaluationContext) {
        setBaseEnabled(!subjects(WeaviateTenancyNodes.selectionOf(evaluationContext)).isEmpty());
    }

    @Override
    public Object execute(ExecutionEvent event) {
        List<WeaviateTenantNode> nodes = subjects(HandlerUtil.getCurrentSelection(event));
        if (nodes.isEmpty()) {
            return null;
        }
        WeaviateCollection collection = (WeaviateCollection) nodes.get(0).getParentObject();
        boolean activate = wouldActivate(nodes);
        WeaviateTenantStatus target =
            activate ? WeaviateTenantStatus.ACTIVE : WeaviateTenantStatus.INACTIVE;

        List<WeaviateTenant> tenants = new ArrayList<>(nodes.size());
        for (WeaviateTenantNode node : nodes) {
            tenants.add(new WeaviateTenant(node.getName(), node.getTenantStatus()));
        }

        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    collection.setTenantStatus(monitor, tenants, target);
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot change tenant state", e.getTargetException());
            DBWorkbench.getPlatformUI().showError(
                WeaviateUIMessages.tenant_manage_title_plain,
                WeaviateUIMessages.tenant_manage_failed,
                e.getTargetException());
            return null;
        } catch (InterruptedException e) {
            return null;
        }
        WeaviateNavigatorRefresh.afterTenantChange(collection);
        return null;
    }

    /**
     * Names the direction and matches the icon to it, both read from the current selection.
     */
    @Override
    public void updateElement(UIElement element, Map parameters) {
        IWorkbenchWindow window = element.getServiceLocator().getService(IWorkbenchWindow.class);
        if (window == null || window.getSelectionService() == null) {
            return;
        }
        List<WeaviateTenantNode> tenants = subjects(window.getSelectionService().getSelection());
        if (tenants.isEmpty()) {
            return;
        }
        boolean activate = wouldActivate(tenants);
        String label = activate
            ? WeaviateUIMessages.tenant_state_activate
            : WeaviateUIMessages.tenant_state_deactivate;
        element.setText(tenants.size() == 1
            ? label
            : MessageFormat.format(WeaviateUIMessages.tenant_state_many, label, tenants.size()));
        // A green bullet for the tenant that will answer afterwards, a dark one for the tenant
        // that will not -- the same on/off reading the status column gives, rather than a tick
        // and a cross, which would say "confirm/abort" instead of naming a state.
        element.setIcon(DBeaverIcons.getImageDescriptor(
            activate ? UIIcon.BULLET_GREEN : UIIcon.BULLET_BLACK));
    }
}
