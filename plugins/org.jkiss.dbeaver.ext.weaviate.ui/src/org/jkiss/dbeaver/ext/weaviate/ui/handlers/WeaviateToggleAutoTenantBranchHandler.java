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

import org.eclipse.jface.viewers.ISelection;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;

import java.util.List;

/**
 * The auto-tenant toggles as they appear under Multi-Tenancy, flat rather than in a submenu.
 * <p>
 * Same command, same dialogs, same work -- the only difference is where it is offered, and that
 * has to be a difference in <em>enablement</em> rather than in visibility. The two contributions
 * use {@code visibleWhen checkEnabled}, so an entry that is visible where it is disabled shows as
 * a greyed row; keying visibility on an expression instead put the pair on every node in the tree,
 * greyed, including nodes belonging to other extensions entirely.
 * <p>
 * So each registration is enabled in exactly one place and invisible everywhere else:
 * {@link WeaviateToggleAutoTenantHandler} on a collection row, this one on the tenancy branch
 * below it.
 */
public class WeaviateToggleAutoTenantBranchHandler extends WeaviateToggleAutoTenantHandler {

    @NotNull
    @Override
    protected List<WeaviateCollection> inScope(@Nullable ISelection selection) {
        return WeaviateTenancyNodes.selectedTenancyBranch(selection);
    }
}
