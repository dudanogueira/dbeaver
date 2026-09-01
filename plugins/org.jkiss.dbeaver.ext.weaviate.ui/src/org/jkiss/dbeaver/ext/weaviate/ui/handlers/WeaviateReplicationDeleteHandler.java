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
 * Removes a movement's record, cancelling it first if that is still possible.
 * <p>
 * Offered only where the server would accept it: an operation past the point of no return cannot
 * be deleted either, unless it has reached READY, where there is nothing left to stop.
 */
public class WeaviateReplicationDeleteHandler extends WeaviateReplicationOpHandler {

    @Override
    public void setEnabled(Object evaluationContext) {
        setBaseEnabled(anySelected(evaluationContext, WeaviateReplicationOp::canDelete));
    }

    @Override
    public Object execute(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        WeaviateDataSource dataSource = dataSourceOf(selection);
        List<WeaviateReplicationOp> ops = WeaviateReplicationNodes.selectedOps(selection);
        if (dataSource != null && !ops.isEmpty()) {
            act(event, dataSource, ops, false);
        }
        return null;
    }

    @Override
    public void updateElement(UIElement element, @SuppressWarnings("rawtypes") Map parameters) {
        int count = countIn(element.getServiceLocator().getService(IWorkbenchWindow.class),
            WeaviateReplicationOp::canDelete);
        element.setText(count > 1 ? "Delete " + count + " Movements" : "Delete Movement");
        element.setIcon(DBeaverIcons.getImageDescriptor(UIIcon.DELETE));
    }
}
