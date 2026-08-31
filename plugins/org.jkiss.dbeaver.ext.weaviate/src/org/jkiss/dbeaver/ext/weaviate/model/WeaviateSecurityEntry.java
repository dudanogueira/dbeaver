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
import org.jkiss.dbeaver.model.struct.DBSObject;

/**
 * A row directly under Security: what this connection is, or what it cannot do.
 * <p>
 * The Security folder is not gated on anything. Weaviate advertises no RBAC capability -- its
 * {@code /v1/meta} carries hostname, version, modules and a gRPC size, and the authz handlers are
 * registered whether or not RBAC is enabled, so with it off every endpoint answers 200 with an
 * empty body. An empty Roles folder therefore cannot tell "RBAC is switched off" from "nobody has
 * defined a role yet", and a folder that vanished would say neither.
 * <p>
 * So the folder always appears and these rows say which case it is. Same reasoning as
 * {@link WeaviateBackupEntry}: hide what can never apply, explain what merely does not apply yet.
 */
public abstract class WeaviateSecurityEntry implements DBSObject {

    protected final WeaviateDataSource dataSource;

    protected WeaviateSecurityEntry(@NotNull WeaviateDataSource dataSource) {
        this.dataSource = dataSource;
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
