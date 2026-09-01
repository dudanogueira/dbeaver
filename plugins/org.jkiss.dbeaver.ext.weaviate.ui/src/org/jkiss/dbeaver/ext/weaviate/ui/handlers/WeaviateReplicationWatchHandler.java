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

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateReplicationOp;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIIcon;

import java.util.List;
import java.util.Map;

/**
 * Follows one movement until it stops.
 * <p>
 * Offered only for a movement that is still running. Following a finished one would open a
 * progress window that closes on its first poll, which tells nobody anything.
 */
public class WeaviateReplicationWatchHandler extends WeaviateReplicationOpHandler {

    @Override
    public void setEnabled(Object evaluationContext) {
        ISelection selection = WeaviateReplicationNodes.selectionOf(evaluationContext);
        List<WeaviateReplicationOp> ops = selection == null
            ? List.of() : WeaviateReplicationNodes.selectedOps(selection);
        // One at a time: this opens a progress window and blocks on it, so following two would
        // mean watching the first and only then noticing the second.
        setBaseEnabled(dataSourceOf(selection) != null
            && ops.size() == 1
            && ops.get(0).getReplicationState().isInFlight());
    }

    @Override
    public Object execute(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        WeaviateDataSource dataSource = dataSourceOf(selection);
        List<WeaviateReplicationOp> ops = WeaviateReplicationNodes.selectedOps(selection);
        if (dataSource != null && ops.size() == 1) {
            watch(event, dataSource, ops.get(0));
        }
        return null;
    }

    @Override
    public void updateElement(UIElement element, @SuppressWarnings("rawtypes") Map parameters) {
        element.setIcon(DBeaverIcons.getImageDescriptor(UIIcon.REFRESH));
    }
}
