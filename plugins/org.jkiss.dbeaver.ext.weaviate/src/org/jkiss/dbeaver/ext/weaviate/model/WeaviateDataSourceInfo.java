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

import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.weaviate.WeaviateConstants;
import org.jkiss.dbeaver.model.DBPTransactionIsolation;
import org.jkiss.dbeaver.model.impl.AbstractDataSourceInfo;
import org.osgi.framework.Version;

import java.util.Collection;

public class WeaviateDataSourceInfo extends AbstractDataSourceInfo {

    private static final String UNKNOWN_VERSION = "unknown";

    @Nullable
    private final String serverVersion;

    public WeaviateDataSourceInfo() {
        this(null);
    }

    public WeaviateDataSourceInfo(@Nullable String serverVersion) {
        this.serverVersion = serverVersion;
    }

    /**
     * Rows can be deleted from the grid, so the data container is not read-only.
     * <p>
     * This flag is checked before anything else: {@code DBExecUtils#getResultSetReadOnlyStatus}
     * returns "Read-only data container" and disables every edit action, so with it set the
     * {@code DBSDataManipulator} implemented by {@link WeaviateCollection} is never consulted
     * and Delete never appears.
     * <p>
     * Insert and update remain unimplemented, and are held back by the feature list rather than
     * by this flag: {@code WeaviateCollection} advertises only {@code data.delete}, and
     * {@code DBExecUtils#getAttributeReadOnlyStatus} reports every attribute as read-only unless
     * {@code data.update} is advertised, which is what keeps cell editing switched off.
     */
    @Override
    public boolean isReadOnlyData() {
        return false;
    }

    /**
     * Metadata is partially writable - collections can be created and dropped
     * (see {@code WeaviateCollectionManager}), so this is not a fully read-only catalog.
     */
    @Override
    public boolean isReadOnlyMetaData() {
        return false;
    }

    @Override
    public String getDatabaseProductName() {
        return "Weaviate";
    }

    @Override
    public String getDatabaseProductVersion() {
        return serverVersion == null ? UNKNOWN_VERSION : serverVersion;
    }

    /**
     * Parsed form of the server version reported by {@code meta()}. Weaviate uses plain
     * {@code major.minor.patch}, but pre-release suffixes (e.g. {@code 1.30.0-rc.1}) are not
     * valid OSGi qualifiers, so fall back to {@link Version#emptyVersion} rather than throwing.
     */
    @Override
    public Version getDatabaseVersion() {
        if (serverVersion == null) {
            return Version.emptyVersion;
        }
        try {
            return Version.parseVersion(serverVersion);
        } catch (IllegalArgumentException e) {
            return Version.emptyVersion;
        }
    }

    @Override
    public String getDriverName() {
        return "Weaviate Java Client v6";
    }

    @Override
    public String getDriverVersion() {
        return WeaviateConstants.CLIENT_VERSION;
    }

    @Override
    public String getSchemaTerm() {
        return "Schema";
    }

    @Override
    public String getProcedureTerm() {
        return "Procedure";
    }

    @Override
    public String getCatalogTerm() {
        return "Catalog";
    }

    @Override
    public boolean supportsTransactions() {
        return false;
    }

    @Override
    public boolean supportsSavepoints() {
        return false;
    }

    @Override
    public boolean supportsReferentialIntegrity() {
        return false;
    }

    @Override
    public boolean supportsIndexes() {
        return true;
    }

    @Override
    public boolean supportsStoredCode() {
        return false;
    }

    @Override
    public Collection<DBPTransactionIsolation> getSupportedTransactionsIsolation() {
        return null;
    }

    @Override
    public boolean supportsBatchUpdates() {
        return false;
    }

    @Override
    public boolean supportsResultSetLimit() {
        return true;
    }
}
