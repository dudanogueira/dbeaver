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
package org.jkiss.dbeaver.ext.weaviate.ui.config;

import org.eclipse.jface.window.Window;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateCollectionEditDialog;
import org.jkiss.dbeaver.model.edit.DBECommandContext;
import org.jkiss.dbeaver.model.edit.DBEObjectConfigurator;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.ui.UITask;
import org.jkiss.dbeaver.ui.UIUtils;

import java.util.Map;

/**
 * Collects the JSON definition for a new Weaviate collection.
 * <p>
 * Runs after {@code WeaviateCollectionManager#createNewObject} has staged a starter document and
 * before the create command is executed, so cancelling here (returning {@code null}) aborts the
 * whole creation.
 */
public class WeaviateCollectionConfigurator implements DBEObjectConfigurator<WeaviateCollection> {

    @Nullable
    @Override
    public WeaviateCollection configureObject(
        @NotNull DBRProgressMonitor monitor,
        @Nullable DBECommandContext commandContext,
        @Nullable Object container,
        @NotNull WeaviateCollection collection,
        @NotNull Map<String, Object> options
    ) {
        return UITask.run(() -> {
            WeaviateCollectionEditDialog dialog = new WeaviateCollectionEditDialog(
                UIUtils.getActiveWorkbenchShell(), collection.getPendingSchemaJson());
            if (dialog.open() != Window.OK) {
                return null;
            }
            collection.setPendingSchemaJson(dialog.getJson());
            return collection;
        });
    }
}
