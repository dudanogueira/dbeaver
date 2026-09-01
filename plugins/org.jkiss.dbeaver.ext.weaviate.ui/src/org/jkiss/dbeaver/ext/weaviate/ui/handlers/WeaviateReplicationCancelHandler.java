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
 * Stops a replica movement.
 * <p>
 * Offered only while the server would still accept it. Once the replica has joined the sharding
 * state the operation is {@code uncancelable} and a cancel answers 409, so the entry is hidden
 * rather than shown and then refused -- an action you can press and cannot use is worse than one
 * that is not there.
 */
public class WeaviateReplicationCancelHandler extends WeaviateReplicationOpHandler {

    @Override
    public void setEnabled(Object evaluationContext) {
        setBaseEnabled(anySelected(evaluationContext, WeaviateReplicationOp::canCancel));
    }

    @Override
    public Object execute(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        WeaviateDataSource dataSource = dataSourceOf(selection);
        List<WeaviateReplicationOp> ops = WeaviateReplicationNodes.selectedOps(selection);
        if (dataSource != null && !ops.isEmpty()) {
            act(event, dataSource, ops, true);
        }
        return null;
    }

    @Override
    public void updateElement(UIElement element, @SuppressWarnings("rawtypes") Map parameters) {
        int count = countIn(element.getServiceLocator().getService(IWorkbenchWindow.class),
            WeaviateReplicationOp::canCancel);
        element.setText(count > 1 ? "Cancel " + count + " Movements" : "Cancel Movement");
        element.setIcon(DBeaverIcons.getImageDescriptor(UIIcon.REJECT));
    }
}
