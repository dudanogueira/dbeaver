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
import org.jkiss.dbeaver.model.DBPToolTipObject;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.model.DBIconComposite;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.struct.DBSObject;

/**
 * One tenant of a multi-tenant collection, as a navigator node.
 * <p>
 * The state is on the node itself rather than only in its properties. An inactive tenant refuses
 * every read, so someone about to click into one needs to know before they do -- the alternative
 * is the server's own answer, which arrives as
 * {@code UNKNOWN: explorer: list class: search: ... tenant not active}.
 */
public class WeaviateTenantNode implements DBSObject, DBPImageProvider, DBPToolTipObject {

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
     * Puts the state in the label, as {@code acme-ap-south (Inactive)}. Active tenants are left
     * unadorned: the normal case should not be noisy, and it is the exception that has to carry
     * a warning.
     */
    @Nullable
    @Override
    public String getObjectToolTip() {
        return tenant.isActive() ? null : tenant.status().getLabel();
    }

    /**
     * A partition, since that is what a tenant is. An inactive one gets the platform's greyed
     * treatment plus a lock, which is the same visual language the navigator already uses for
     * something present but not available -- rather than a new icon nobody has seen before.
     */
    @NotNull
    @Override
    public DBPImage getObjectImage() {
        if (tenant.isActive()) {
            return DBIcon.TREE_PARTITION;
        }
        return new DBIconComposite(DBIcon.TREE_PARTITION, true, null, null, null, DBIcon.OVER_LOCK);
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
