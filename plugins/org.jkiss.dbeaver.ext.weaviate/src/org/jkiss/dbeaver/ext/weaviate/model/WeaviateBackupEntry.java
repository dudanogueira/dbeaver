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
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.meta.Association;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;

import java.util.List;

/**
 * Something that sits directly under the Backups folder.
 * <p>
 * A base class rather than two unrelated types because the folder holds two kinds of row: the
 * backends, and -- when no backup module is enabled at all -- one line saying so. The navigator
 * declares a single type per folder, and both need to satisfy it.
 * <p>
 * The advisory row exists because a missing backup module is not a permanent fact about this
 * server, it is a configuration someone can change. The plugin hides what can never apply and
 * explains what merely does not apply yet; this is the second kind.
 */
public abstract class WeaviateBackupEntry implements DBSObject {

    protected final WeaviateDataSource dataSource;

    protected WeaviateBackupEntry(@NotNull WeaviateDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Backups held here. Empty for anything that is not a usable backend, including a backend
     * whose module is switched off -- asking the server about it answers 422, and a row that
     * already says "module not enabled" has nothing to gain from the round trip.
     */
    @NotNull
    @Association
    public List<WeaviateBackupNode> getBackups(@NotNull DBRProgressMonitor monitor) throws DBException {
        return List.of();
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
