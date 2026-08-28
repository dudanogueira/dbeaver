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
package org.jkiss.dbeaver.ext.weaviate.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPImage;
import org.jkiss.dbeaver.model.DBPImageProvider;
import org.jkiss.dbeaver.model.DBPStatefulObject;
import org.jkiss.dbeaver.model.DBPToolTipObject;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.model.DBIconComposite;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectState;

/**
 * One tenant of a multi-tenant collection, as a navigator node.
 * <p>
 * The state is on the node itself rather than only in its properties. An inactive tenant refuses
 * every read, so someone about to click into one needs to know before they do -- the alternative
 * is the server's own answer, which arrives as
 * {@code UNKNOWN: explorer: list class: search: ... tenant not active}.
 */
public class WeaviateTenantNode implements DBSObject, DBPImageProvider, DBPToolTipObject, DBPStatefulObject {

    /**
     * Overlays for the states that are not "running normally".
     * <p>
     * These reach the tree through {@code DBNDatabaseNode#getNodeIcon}, which applies a stateful
     * object's overlay unconditionally. That matters: the {@code (Inactive)} text next to the
     * name comes from {@link #getObjectToolTip()} and only appears when the "Show object tips"
     * navigator preference is on, which it is not by default. The overlay is the part that is
     * always there.
     */
    private static final DBSObjectState STATE_INACTIVE =
        new DBSObjectState("Inactive", DBIcon.OVER_LOCK);
    private static final DBSObjectState STATE_TRANSITIONAL =
        new DBSObjectState("In transition", DBIcon.OVER_UNKNOWN);

    private final WeaviateCollection collection;
    private final WeaviateTenant tenant;

    public WeaviateTenantNode(@NotNull WeaviateCollection collection, @NotNull WeaviateTenant tenant) {
        this.collection = collection;
        this.tenant = tenant;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return tenant.name();
    }

    @NotNull
    @Property(viewable = true, order = 2)
    public String getStatus() {
        return tenant.status().getLabel();
    }

    @NotNull
    public WeaviateTenantStatus getTenantStatus() {
        return tenant.status();
    }

    /**
     * Puts the state in the label, as {@code acme-ap-south (Inactive)}.
     * <p>
     * Only rendered when the navigator's "Show object tips" preference is on, so it cannot be the
     * only signal -- see {@link #getObjectState()} for the one that always shows. Given it is
     * opt-in, every tenant names its state rather than only the unusual ones: someone who turned
     * that preference on wants to read states, not infer them from an absence.
     */
    @Nullable
    @Override
    public String getObjectToolTip() {
        return tenant.status().getLabel();
    }

    /**
     * A partition, since that is what a tenant is, greyed out when the tenant will not answer.
     * <p>
     * The lock is not added here -- {@link #getObjectState()} supplies it, and the platform
     * composes the two. Doing both here would put two overlays in the same corner, since
     * {@code DBNModel#getStateOverlayImage} writes the state into the bottom right of whatever
     * composite it is handed.
     */
    @NotNull
    @Override
    public DBPImage getObjectImage() {
        return tenant.isActive()
            ? DBIcon.TREE_PARTITION
            : new DBIconComposite(DBIcon.TREE_PARTITION, true, null, null, null, null);
    }

    @NotNull
    @Override
    public DBSObjectState getObjectState() {
        if (tenant.status().isTransitional()) {
            return STATE_TRANSITIONAL;
        }
        // Active is the ordinary case and gets no overlay: marking every healthy tenant would
        // make a list of thousands noisy and leave the exception no easier to spot.
        return tenant.isActive() ? DBSObjectState.NORMAL : STATE_INACTIVE;
    }

    @Override
    public void refreshObjectState(@NotNull DBRProgressMonitor monitor) {
        // The state travels with the tenant this node was built from, and the list is rebuilt by
        // WeaviateCollection#getTenantNodes after any change, so there is nothing to re-read here.
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
    }

    @NotNull
    @Override
    public DBSObject getParentObject() {
        return collection;
    }

    @NotNull
    @Override
    public DBPDataSource getDataSource() {
        return collection.getDataSource();
    }

    @Override
    public boolean isPersisted() {
        return true;
    }
}
