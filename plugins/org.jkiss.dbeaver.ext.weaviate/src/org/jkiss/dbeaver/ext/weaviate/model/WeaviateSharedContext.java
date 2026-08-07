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
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionContextDefaults;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCInvalidatePhase;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.impl.AbstractExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSInstance;

import java.util.Map;

/**
 * A borrowed view of {@link WeaviateDataSource}'s connection, handed out where DBeaver asks for an
 * isolated one.
 * <p>
 * Weaviate has no per-connection session state to isolate - the client is a shared HTTP/gRPC handle
 * - so an "isolated" context is the same connection under a different name. What matters is
 * ownership: callers that open an isolated context also close it, and returning the data source
 * itself meant that close tore down the shared client, leaving every later call to fail with
 * {@code getClient() is null}. Data transfer does exactly this (see
 * {@code DatabaseTransferProducer}, which opens a context for the export and closes it at the end),
 * which is why exporting a collection broke the connection.
 * <p>
 * Everything here delegates to the data source except {@link #close()}, which is deliberately inert:
 * this context does not own the client, so it must not dispose of it.
 */
public class WeaviateSharedContext implements DBCExecutionContext {

    private final WeaviateDataSource dataSource;
    private final long id;
    private final String purpose;

    public WeaviateSharedContext(@NotNull WeaviateDataSource dataSource, @NotNull String purpose) {
        this.dataSource = dataSource;
        this.id = AbstractExecutionContext.generateContextId();
        this.purpose = purpose;
    }

    @Override
    public long getContextId() {
        return id;
    }

    @NotNull
    @Override
    public String getContextName() {
        return purpose;
    }

    @NotNull
    @Override
    public DBPDataSource getDataSource() {
        return dataSource;
    }

    @NotNull
    @Override
    public DBSInstance getOwnerInstance() {
        return dataSource;
    }

    @Override
    public boolean isConnected() {
        return dataSource.isConnected();
    }

    @NotNull
    @Override
    public DBCSession openSession(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionPurpose purpose,
        @NotNull String task
    ) {
        return dataSource.openSession(monitor, purpose, task);
    }

    @Override
    public void checkContextAlive(DBRProgressMonitor monitor) throws DBException {
        dataSource.checkContextAlive(monitor);
    }

    @Override
    public void invalidateContext(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCInvalidatePhase phase
    ) throws DBException {
        dataSource.invalidateContext(monitor, phase);
    }

    @Nullable
    @Override
    public DBCExecutionContextDefaults getContextDefaults() {
        return dataSource.getContextDefaults();
    }

    /**
     * No-op. The client belongs to the data source and outlives this context; closing it here is
     * what previously broke the connection mid-session.
     */
    @Override
    public void close() {
        // intentionally empty - see class javadoc
    }

    @Nullable
    @Override
    public Object getContextAttribute(@NotNull String attributeName) {
        return dataSource.getContextAttribute(attributeName);
    }

    @Override
    public void setContextAttribute(@NotNull String attributeName, @Nullable Object attributeValue) {
        dataSource.setContextAttribute(attributeName, attributeValue);
    }

    @Override
    public void removeContextAttribute(@NotNull String attributeName) {
        dataSource.removeContextAttribute(attributeName);
    }

    @NotNull
    @Override
    public Map<String, ?> getContextAttributes() {
        return dataSource.getContextAttributes();
    }
}
