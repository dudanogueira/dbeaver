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

import io.weaviate.client6.v1.api.collections.data.DeleteManyResponse;
import io.weaviate.client6.v1.api.collections.query.Filter;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.edit.DBEPersistAction;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatistics;
import org.jkiss.dbeaver.model.impl.edit.SQLDatabasePersistActionComment;
import org.jkiss.dbeaver.model.struct.DBSDataManipulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Collects the UUIDs of the rows marked for deletion and removes them in one request.
 * <p>
 * DBeaver calls {@link #add} once per row and {@link #execute} once when the user saves, so
 * batching here turns "delete 200 selected rows" into a single call rather than 200.
 * <p>
 * A class of its own rather than an inner one so that {@link WeaviateCollection} is left holding
 * only the {@code DBSDataManipulator} contract. Its three siblings there -- insert, update and
 * truncate -- are one-line refusals today, and this is where they will land when row editing is
 * built.
 */
final class WeaviateDeleteBatch implements DBSDataManipulator.ExecuteBatch {

    private static final Log log = Log.getLog(WeaviateDeleteBatch.class);

    private final WeaviateCollection collection;

    /**
     * Collects the UUIDs of the rows marked for deletion and removes them in one request.
     * <p>
     * DBeaver calls {@link #add} once per row and {@link #execute} once when the user saves, so
     * batching here turns "delete 200 selected rows" into a single call rather than 200.
     */

    private final int uuidIndex;
    private final String tenant;
    private final List<String> ids = new ArrayList<>();

    WeaviateDeleteBatch(
    @NotNull WeaviateCollection collection, int uuidIndex, @Nullable String tenant) {
    this.collection = collection;
        this.uuidIndex = uuidIndex;
        this.tenant = tenant;
    }

    @NotNull
    @Override
    public DBSDataManipulator.ExecuteBatch add(@NotNull Object[] attributeValues) throws DBCException {
        if (uuidIndex >= attributeValues.length) {
            throw new DBCException("Row has no " + WeaviateColumns.UUID + " value to delete by");
        }
        Object value = attributeValues[uuidIndex];
        String id = value == null ? null : value.toString().trim();
        if (id == null || id.isEmpty()) {
            throw new DBCException("Cannot delete a row with an empty " + WeaviateColumns.UUID);
        }
        ids.add(id);
        return this;
    }

    @NotNull
    @Override
    public DBCStatistics execute(
        @NotNull DBCSession session,
        @NotNull Map<String, Object> options
    ) throws DBException {
        DBCStatistics statistics = new DBCStatistics();
        if (ids.isEmpty()) {
            return statistics;
        }
        long startTime = System.currentTimeMillis();
        statistics.setQueryText("DELETE " + ids.size() + " object(s) FROM " + collection.getName());
        try {
            DeleteManyResponse response = collection.handle(tenant)
                .data.deleteMany(
                    Filter.uuid().containsAny(ids.toArray(new String[0])),
                    b -> b.verbose(true));
            long failed = response.failed();
            if (failed > 0) {
                // Surfaced rather than swallowed: the grid would otherwise drop the rows
                // locally and look as though the delete had succeeded.
                throw new DBCException(
                    "Weaviate deleted " + response.successful() + " of " + ids.size()
                        + " object(s); " + failed + " failed" + firstError(response));
            }
            statistics.setRowsUpdated(response.successful());
        } catch (DBCException e) {
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Weaviate delete failed for collection " + collection.getName() + ": " + msg, e);
            throw new DBCException("Failed to delete from " + collection.getName() + ": " + msg, e);
        } finally {
            statistics.setExecuteTime(System.currentTimeMillis() - startTime);
            ids.clear();
        }
        return statistics;
    }

    @Override
    public void generatePersistActions(
        @NotNull DBCSession session,
        @NotNull List<DBEPersistAction> actions,
        @NotNull Map<String, Object> options
    ) {
        // "Generate SQL" for the pending changes. There is no SQL dialect behind Weaviate,
        // so the best that can be offered is a readable description of what would be sent.
        for (String id : ids) {
            actions.add(new SQLDatabasePersistActionComment(
                collection.getDataSource(),
                "Delete object " + id + " from " + collection.getName()));
        }
    }

    @Override
    public void close() {
        ids.clear();
    }

    @NotNull
    private static String firstError(@NotNull DeleteManyResponse response) {
    if (response.objects() == null) {
        return "";
    }
    for (DeleteManyResponse.DeletedObject o : response.objects()) {
        if (!o.successful() && o.error() != null) {
            return ": " + o.error();
        }
    }
    return "";
    }
}
