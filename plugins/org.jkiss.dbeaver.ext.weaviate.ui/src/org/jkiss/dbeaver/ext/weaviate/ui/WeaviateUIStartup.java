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

import org.eclipse.ui.IStartup;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateTenantPrompt;
import org.jkiss.dbeaver.ui.UIUtils;

/**
 * Registers the tenant picker with the model bundle at workbench startup.
 * <p>
 * The read path needs to ask for a tenant before any Weaviate UI has necessarily been opened, so
 * registration cannot wait for the connection page or the query panel to load. If early startup
 * is disabled the model falls back to the platform's own choice dialog, which is worse but not
 * broken.
 */
public class WeaviateUIStartup implements IStartup {

    @Override
    public void earlyStartup() {
        WeaviateTenantPrompt.setProvider((collectionName, tenants, current) -> {
            String[] chosen = new String[1];
            UIUtils.syncExec(() -> {
                WeaviateTenantSelectDialog dialog = new WeaviateTenantSelectDialog(
                    UIUtils.getActiveWorkbenchShell(), collectionName, tenants, current);
                if (dialog.open() == org.eclipse.jface.dialogs.IDialogConstants.OK_ID) {
                    chosen[0] = dialog.getSelectedTenant();
                }
            });
            return chosen[0];
        });
    }
}
