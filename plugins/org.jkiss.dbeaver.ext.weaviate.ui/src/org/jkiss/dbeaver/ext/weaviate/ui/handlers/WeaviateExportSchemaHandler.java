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
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.dialogs.DialogUtils;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Writes the selected collection's definition to a JSON file.
 * <p>
 * The output is the same REST schema format the
 * <a href="https://weaviate.github.io/weaviate-add-collection/">weaviate-add-collection</a> builder
 * produces, so an exported file can be edited there and fed straight back into "Create New Collection".
 */
public class WeaviateExportSchemaHandler extends AbstractHandler {

    private static final Log log = Log.getLog(WeaviateExportSchemaHandler.class);

    @Override
    public Object execute(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        DBNNode node = NavigatorUtils.getSelectedNode(selection);
        if (!(node instanceof DBNDatabaseNode databaseNode)
            || !(databaseNode.getObject() instanceof WeaviateCollection collection)) {
            return null;
        }
        Shell shell = HandlerUtil.getActiveShell(event);
        // Serialization is a pure in-memory transform of the already-loaded config,
        // so there is no server round-trip to push off the UI thread here.
        String json = collection.toSchemaJson();

        File file = DialogUtils.selectFileForSave(
            shell,
            WeaviateUIMessages.export_schema_title,
            new String[]{"*.json", "*", "*.*"},
            collection.getName() + ".json");
        if (file == null) {
            return null;
        }
        try {
            Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Cannot write collection schema to " + file, e);
            DBWorkbench.getPlatformUI().showError(
                WeaviateUIMessages.export_schema_title,
                WeaviateUIMessages.export_schema_error,
                e);
        }
        return null;
    }
}
