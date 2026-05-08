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

import org.jkiss.dbeaver.model.DBPTransactionIsolation;
import org.jkiss.dbeaver.model.impl.AbstractDataSourceInfo;
import org.osgi.framework.Version;

import java.util.Collection;

public class WeaviateDataSourceInfo extends AbstractDataSourceInfo {

    @Override
    public boolean isReadOnlyData() {
        return false;
    }

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
        return "?";
    }

    @Override
    public Version getDatabaseVersion() {
        return new Version(1, 0, 0);
    }

    @Override
    public String getDriverName() {
        return "Weaviate Java Client v6";
    }

    @Override
    public String getDriverVersion() {
        return "6.2.0";
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
