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
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateServerFeature;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateNavigatorRefresh;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIIcon;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;
import org.jkiss.dbeaver.ui.navigator.actions.NavigatorHandlerRefresh;

import java.lang.reflect.InvocationTargetException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns automatic tenant creation or activation on and off from the Multi-Tenancy folder, where
 * the current values are already on display.
 * <p>
 * Menu entries rather than a dialog: each is one boolean, and a dialog to change one is a dialog
 * too many. Each names the change it will make -- "Enable Create Automatically" or
 * "Disable ..." -- so the current value is legible from the entry itself without a tick to
 * interpret.
 * <p>
 * Works across a multi-selection, so a setting can be applied to many collections at once. With
 * a mixed selection the offer is Enable, and the entry says how many collections it will touch;
 * the alternative -- flipping each collection independently -- would leave the selection in the
 * state it started in, which is not what pressing one entry should mean.
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

    /**
     * Collections whose server actually has these settings.
     * <p>
     * A server without them reports neither value, and an entry that cannot mean anything is
     * worse than no entry at all -- so an old server drops them from the selection rather than
     * showing a control that would silently do nothing.
     */
    @NotNull
    private static List<WeaviateCollection> supported(@NotNull List<WeaviateCollection> collections) {
        List<WeaviateCollection> supported = new ArrayList<>(collections.size());
        for (WeaviateCollection collection : collections) {
            if (collection.getDataSource() instanceof WeaviateDataSource ds
                && ds.supports(WeaviateServerFeature.AUTO_TENANT_CREATION)
            ) {
                supported.add(collection);
            }
        }
        return supported;
    }

    @NotNull
    private static String label(boolean creation, boolean enable) {
        if (creation) {
            return enable
                ? WeaviateUIMessages.tenant_auto_creation_enable
                : WeaviateUIMessages.tenant_auto_creation_disable;
        }
        return enable
            ? WeaviateUIMessages.tenant_auto_activation_enable
            : WeaviateUIMessages.tenant_auto_activation_disable;
    }

    @Override
    public void setEnabled(Object evaluationContext) {
        setBaseEnabled(!supported(WeaviateTenancyNodes.selectedCollections(
            WeaviateTenancyNodes.selectionOf(evaluationContext))).isEmpty());
    }

    private static boolean current(@NotNull WeaviateCollection collection, boolean creation) {
        Boolean value = creation
            ? collection.getAutoTenantCreation()
            : collection.getAutoTenantActivation();
        return Boolean.TRUE.equals(value);
    }

    /**
     * What the entry would do to this selection: enable unless every collection in it already
     * has the setting on.
     */
    private static boolean wouldEnable(@NotNull List<WeaviateCollection> collections, boolean creation) {
        for (WeaviateCollection collection : collections) {
            if (!current(collection, creation)) {
                return true;
            }
        }
        return false;
    }

    /** Collections in the selection that this change would actually alter. */
    @NotNull
    private static List<WeaviateCollection> changing(
        @NotNull List<WeaviateCollection> collections, boolean creation, boolean enable
    ) {
        List<WeaviateCollection> changing = new ArrayList<>();
        for (WeaviateCollection collection : collections) {
            if (current(collection, creation) != enable) {
                changing.add(collection);
            }
        }
        return changing;
    }

    @Override
    public Object execute(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        List<WeaviateCollection> collections = supported(
            WeaviateTenancyNodes.selectedCollections(selection));
        if (collections.isEmpty()) {
            return null;
        }
        boolean creation = OPTION_CREATION.equals(event.getParameter(PARAM_OPTION));
        boolean enable = wouldEnable(collections, creation);
        List<WeaviateCollection> targets = changing(collections, creation, enable);
        if (targets.isEmpty()) {
            return null;
        }

        // Changing how writes behave on several collections at once is worth stating before it
        // happens; on a single collection it is ordinary interactive work and asking is noise.
        if (targets.size() > 1) {
            String question = MessageFormat.format(WeaviateUIMessages.tenant_auto_confirm,
                label(creation, enable), targets.size());
            if (!UIUtils.confirmAction(
                HandlerUtil.getActiveShell(event), WeaviateUIMessages.tenant_manage_auto_group, question)
            ) {
                return null;
            }
        }

        List<String> failed = new ArrayList<>();
        List<WeaviateCollection> changed = new ArrayList<>();
        try {
            UIUtils.runInProgressService(monitor -> {
                monitor.beginTask(label(creation, enable), targets.size());
                try {
                    for (WeaviateCollection collection : targets) {
                        if (monitor.isCanceled()) {
                            break;
                        }
                        monitor.subTask(collection.getName());
                        // Both values always travel together: the server takes the multi-tenancy
                        // block whole, so sending only the one that changed resets the other.
                        boolean wantCreation = creation ? enable : current(collection, true);
                        boolean wantActivation = creation ? current(collection, false) : enable;
                        try {
                            collection.setAutoTenantOptions(monitor, wantCreation, wantActivation);
                            changed.add(collection);
                        } catch (DBException e) {
                            // One collection refusing should not abandon the rest of a bulk
                            // change; what failed is reported once at the end.
                            log.error("Cannot update multi-tenancy settings of "
                                + collection.getName(), e);
                            failed.add(collection.getName());
                        }
                        monitor.worked(1);
                    }
                } finally {
                    monitor.done();
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
            // Whatever was applied before the cancel stands, so fall through and refresh.
        }

        for (WeaviateCollection collection : changed) {
            WeaviateNavigatorRefresh.afterTenantChange(collection);
        }
        if (!failed.isEmpty()) {
            DBWorkbench.getPlatformUI().showMessageBox(
                WeaviateUIMessages.tenant_manage_auto_group,
                MessageFormat.format(WeaviateUIMessages.tenant_auto_partial,
                    changed.size(), String.join(", ", failed)),
                true);
        }
        return null;
    }

    /**
     * Names the change and matches the icon to it, both read from the whole selection, so the
     * entry is accurate whether one collection is selected or twenty.
     */
    @Override
    public void updateElement(UIElement element, Map parameters) {
        IWorkbenchWindow window = element.getServiceLocator().getService(IWorkbenchWindow.class);
        if (window == null || window.getSelectionService() == null) {
            return;
        }
        List<WeaviateCollection> collections = supported(
            WeaviateTenancyNodes.selectedCollections(window.getSelectionService().getSelection()));
        if (collections.isEmpty()) {
            return;
        }
        boolean creation = OPTION_CREATION.equals(parameters.get(PARAM_OPTION));
        boolean enable = wouldEnable(collections, creation);
        String text = label(creation, enable);
        int affected = changing(collections, creation, enable).size();
        if (collections.size() > 1) {
            text = MessageFormat.format(WeaviateUIMessages.tenant_auto_many, text, affected);
        }
        element.setText(text);
        // Same green/dark reading as Activate/Deactivate Tenant: the bullet shows the state the
        // setting will be in once the entry is pressed.
        element.setIcon(DBeaverIcons.getImageDescriptor(
            enable ? UIIcon.BULLET_GREEN : UIIcon.BULLET_BLACK));
    }
}
