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
package org.jkiss.dbeaver.ext.weaviate.ui;

import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.jkiss.dbeaver.ui.controls.resultset.IResultSetController;
import org.jkiss.dbeaver.ui.controls.resultset.IResultSetListener;
import org.jkiss.dbeaver.ui.controls.resultset.handler.ResultSetHandlerMain;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateTenantPrompt;
import org.jkiss.dbeaver.ui.UIUtils;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Registers the tenant picker with the model bundle at workbench startup.
 * <p>
 * The read path needs to ask for a tenant before any Weaviate UI has necessarily been opened, so
 * registration cannot wait for the connection page or the query panel to load. If early startup
 * is disabled the model falls back to the platform's own choice dialog, which is worse but not
 * broken.
 */
public class WeaviateUIStartup implements IStartup {

    private static final Log log = Log.getLog(WeaviateUIStartup.class);

    /** Must match the panel id in plugin.xml. */
    private static final String WEAVIATE_QUERY_PANEL_ID = "weaviate-query";

    /**
     * Result sets already handled. Weakly held so a closed editor is not kept alive, and
     * identity-based because two result sets are never interchangeable.
     */
    private static final Set<IResultSetController> seen =
        Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup() {
        installPanelOpener();
        WeaviateTenantPrompt.setProvider((collectionName, tenants, current) -> {
            String[] chosen = new String[1];
            UIUtils.syncExec(() -> {
                // Open the Weaviate Query panel while we are here. The tenant dropdown lives in
                // it, and this is the moment the user learns the collection has tenants at all.
                // The panel's own default="true" only applies to a presentation with no saved
                // panel settings, so an existing workspace never gets it.
                openQueryPanel();
                WeaviateTenantSelectDialog dialog = new WeaviateTenantSelectDialog(
                    UIUtils.getActiveWorkbenchShell(), collectionName, tenants, current);
                if (dialog.open() == org.eclipse.jface.dialogs.IDialogConstants.OK_ID) {
                    chosen[0] = dialog.getSelectedTenant();
                }
            });
            return chosen[0];
        });
    }

    /**
     * Make the Weaviate Query panel the visible one for Weaviate result sets.
     * <p>
     * The panel declares default="true", but {@code ResultSetViewer#activateDefaultPanels} only
     * consults that when a presentation has no saved panel settings at all -- so any workspace
     * that has ever touched its panels keeps its own list and never picks the default up. This
     * activates the panel explicitly when a Weaviate result set appears.
     * <p>
     * Once per result set: re-activating whenever the part is focused would override the user
     * closing the panel or switching to another one.
     */
    private static void installPanelOpener() {
        IWorkbenchWindow window = UIUtils.getActiveWorkbenchWindow();
        if (window == null || window.getActivePage() == null) {
            return;
        }
        window.getActivePage().addPartListener(new IPartListener2() {
            @Override
            public void partActivated(@NotNull IWorkbenchPartReference ref) {
                openFor(ref);
            }

            @Override
            public void partVisible(@NotNull IWorkbenchPartReference ref) {
                openFor(ref);
            }
        });
    }

    private static void openFor(@NotNull IWorkbenchPartReference ref) {
        try {
            IWorkbenchPart part = ref.getPart(false);
            if (part == null) {
                return;
            }
            IResultSetController controller = ResultSetHandlerMain.getActiveResultSet(part);
            if (controller == null || !isWeaviate(controller) || !seen.add(controller)) {
                return;
            }
            // Activated from handleResultSetLoad rather than here. The viewer restores its own
            // panels during setup -- activateDefaultPanels runs after this part listener -- so
            // anything done now is undone a moment later. The load callback fires once that
            // setup is complete, which is a defined point in the lifecycle rather than a guess
            // about timing.
            controller.addListener(new IResultSetListener() {
                @Override
                public void handleResultSetLoad() {
                    controller.removeListener(this);
                    try {
                        controller.activatePanel(WEAVIATE_QUERY_PANEL_ID, true, true);
                    } catch (Throwable e) {
                        log.debug("Cannot open the Weaviate Query panel", e);
                    }
                }

                @Override
                public void handleResultSetChange() {
                }

                @Override
                public void handleResultSetSelectionChange(SelectionChangedEvent event) {
                }
            });
        } catch (Throwable e) {
            log.debug("Cannot open the Weaviate Query panel", e);
        }
    }

    private static boolean isWeaviate(@NotNull IResultSetController controller) {
        return controller.getContainer() != null
            && controller.getContainer().getDataContainer() instanceof WeaviateCollection;
    }

    /**
     * Reveal the Weaviate Query panel on the active result set, if there is one.
     * <p>
     * Best effort: the panel is where the tenant can be changed afterwards, but failing to open
     * it must not stop the tenant prompt itself.
     */
    private static void openQueryPanel() {
        try {
            IWorkbenchWindow window = UIUtils.getActiveWorkbenchWindow();
            if (window == null || window.getActivePage() == null) {
                return;
            }
            IResultSetController controller = ResultSetHandlerMain.getActiveResultSet(
                window.getActivePage().getActivePart());
            if (controller != null) {
                controller.activatePanel(WEAVIATE_QUERY_PANEL_ID, true, true);
            }
        } catch (Throwable e) {
            log.debug("Cannot open the Weaviate Query panel", e);
        }
    }
}
