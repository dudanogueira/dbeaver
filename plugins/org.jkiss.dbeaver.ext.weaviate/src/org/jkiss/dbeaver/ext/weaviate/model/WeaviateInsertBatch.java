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

import io.weaviate.client6.v1.api.collections.Vectors;
import io.weaviate.client6.v1.api.collections.WeaviateObject;
import io.weaviate.client6.v1.api.collections.data.InsertManyResponse;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.edit.DBEPersistAction;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatistics;
import org.jkiss.dbeaver.model.struct.DBSDataManipulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rows added in the grid, sent as one batch.
 * <p>
 * DBeaver calls {@link #add} per new row and {@link #execute} once on save, so a paste of two
 * hundred rows is one request rather than two hundred -- the same shape as
 * {@link WeaviateDeleteBatch}.
 * <p>
 * An id may be left blank, in which case the server assigns one. That is the ordinary case, and it
 * is why the uuid column is not required here as it is for a delete: a new row has no id yet.
 * <p>
 * <b>Only declared properties and vectors are sent.</b> The classification lives in
 * {@link WeaviateWriteColumns} and it is load-bearing rather than tidy: with auto-schema on, a
 * stray {@code _score} in the properties map would not fail, it would add a permanent
 * {@code _score} property to the collection.
 */
final class WeaviateInsertBatch implements DBSDataManipulator.ExecuteBatch {

    private static final Log log = Log.getLog(WeaviateInsertBatch.class);

    private final WeaviateCollection collection;
    private final WeaviateWriteColumns columns;
    private final String tenant;
    private final List<WeaviateObject<Map<String, Object>>> pending = new ArrayList<>();

    WeaviateInsertBatch(
        @NotNull WeaviateCollection collection,
        @NotNull WeaviateWriteColumns columns,
        @Nullable String tenant
    ) {
        this.collection = collection;
        this.columns = columns;
        this.tenant = tenant;
    }

    @NotNull
    @Override
    public DBSDataManipulator.ExecuteBatch add(@NotNull Object[] values) throws DBCException {
        Map<String, Object> properties = columns.propertiesOf(values, null);
        Map<String, float[]> vectors = columns.vectorsOf(values, null);
        String id = columns.idOf(values);
        if (properties.isEmpty() && vectors.isEmpty()) {
            throw new DBCException("A new row needs at least one value");
        }
        pending.add(WeaviateObject.<Map<String, Object>>of(o -> {
            o.properties(properties);
            if (id != null) {
                o.uuid(id);
            }
            if (tenant != null) {
                o.tenant(tenant);
            }
            for (Map.Entry<String, float[]> vector : vectors.entrySet()) {
                o.vectors(Vectors.of(vector.getKey(), vector.getValue()));
            }
            return o;
        }));
        return this;
    }

    @NotNull
    @Override
    public DBCStatistics execute(
        @NotNull DBCSession session, @NotNull Map<String, Object> options
    ) throws DBCException {
        DBCStatistics statistics = new DBCStatistics();
        if (pending.isEmpty()) {
            return statistics;
        }
        long started = System.currentTimeMillis();
        try {
            InsertManyResponse response = collection.handle(tenant).data.insertMany(pending);
            List<String> errors = response.errors();
            if (errors != null && !errors.isEmpty()) {
                // insertMany is partial by nature: some objects land, some do not, and the
                // response carries a message per failure. Reporting the first with the count is
                // more use than a generic failure over a batch that partly succeeded.
                throw new DBCException(errors.size() + " of " + pending.size()
                    + " rows were refused. First: " + errors.get(0));
            }
            statistics.setRowsUpdated(pending.size());
        } catch (DBCException e) {
            throw e;
        } catch (Exception e) {
            throw new DBCException("Cannot add rows to " + collection.getName() + ": "
                + WeaviateWriteErrors.describe(e), e);
        } finally {
            statistics.addExecuteTime(System.currentTimeMillis() - started);
            pending.clear();
        }
        return statistics;
    }

    @Override
    public void generatePersistActions(
        @NotNull DBCSession session,
        @NotNull List<DBEPersistAction> actions,
        @NotNull Map<String, Object> options
    ) {
        actions.add(new org.jkiss.dbeaver.model.impl.edit.SQLDatabasePersistActionComment(
            session.getDataSource(),
            "// Add " + pending.size() + " object(s) to " + collection.getName()
                + " using the Java client v6\n"
                + "client.collections.use(\"" + collection.getName() + "\").data.insertMany(objects);"));
    }

    @Override
    public void close() {
        pending.clear();
    }
}
