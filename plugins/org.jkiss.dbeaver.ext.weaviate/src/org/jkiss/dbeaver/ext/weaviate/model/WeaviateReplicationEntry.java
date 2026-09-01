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
import org.jkiss.dbeaver.model.meta.Association;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;

/**
 * A row under Replication: an operation, or an explanation of why there are none.
 * <p>
 * The folder is not gated. Unlike RBAC and backups, the server here answers plainly -- every
 * replication endpoint returns 501 when {@code REPLICA_MOVEMENT_ENABLED} is false -- so an empty
 * list really does mean "nothing is happening" and the advisory row really does mean "switched
 * off". Both are worth showing; neither is worth hiding the folder for.
 */
public abstract class WeaviateReplicationEntry implements DBSObject {

    protected final WeaviateDataSource dataSource;

    protected WeaviateReplicationEntry(@NotNull WeaviateDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * The states this row has been through. Empty for anything that is not an operation --
     * an advisory row has no history, and the navigator asks every child of the folder.
     */
    @NotNull
    @Association
    public java.util.List<WeaviateReplicationStep> getSteps(@NotNull DBRProgressMonitor monitor) {
        return java.util.List.of();
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
    }

    @NotNull
    @Override
    public DBSObject getParentObject() {
        return dataSource;
    }

    @NotNull
    @Override
    public DBPDataSource getDataSource() {
        return dataSource;
    }

    @Override
    public boolean isPersisted() {
        return true;
    }
}
