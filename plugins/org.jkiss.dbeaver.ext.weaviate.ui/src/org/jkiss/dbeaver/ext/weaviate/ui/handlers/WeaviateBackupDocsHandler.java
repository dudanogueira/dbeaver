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
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateBackupAdvice;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateBackupBackend;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;

/**
 * Points at the documentation for setting a backup backend up.
 * <p>
 * Offered only where it answers something: a backend whose module is not enabled, and the row that
 * appears when none of them is. Those rows say what is missing; this says what to do about it, and
 * the answer is server configuration rather than anything the plugin can offer.
 * <p>
 * Not routed through {@code WeaviateDocTopics}, which is where the folder-level Read Docs entries
 * live. That table maps a navigator folder id to a page, and {@code WeaviateDocTopicsTest} pins
 * every URL in it to {@code docs.weaviate.io/weaviate/}. Backup configuration is documented under
 * {@code /deploy/}, so adding it there would have meant loosening a test that is doing its job.
 */
public class WeaviateBackupDocsHandler extends AbstractHandler {

    private static final String BACKUPS_DOCS = "https://docs.weaviate.io/deploy/configuration/backups";

    /** Whether this node is one that cannot back anything up yet. */
    private static boolean isUnconfigured(@Nullable ISelection selection) {
        if (selection == null) {
            return false;
        }
        DBNNode node = NavigatorUtils.getSelectedNode(selection);
        if (!(node instanceof DBNDatabaseNode databaseNode)) {
            return false;
        }
        DBSObject object = databaseNode.getObject();
        if (object instanceof WeaviateBackupAdvice) {
            return true;
        }
        return object instanceof WeaviateBackupBackend backend && !backend.isAvailable();
    }

    @Nullable
    private static ISelection selectionFrom(Object evaluationContext) {
        if (!(evaluationContext instanceof IEvaluationContext context)) {
            return null;
        }
        Object selection = context.getVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME);
        return selection instanceof ISelection sel ? sel : null;
    }

    @Override
    public void setEnabled(Object evaluationContext) {
        setBaseEnabled(isUnconfigured(selectionFrom(evaluationContext)));
    }

    @Override
    public Object execute(ExecutionEvent event) {
        UIUtils.openWebBrowser(BACKUPS_DOCS);
        return null;
    }
}
