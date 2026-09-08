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
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.edit.DBEPersistAction;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatistics;
import org.jkiss.dbeaver.model.struct.DBSDataManipulator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cells edited in the grid, sent one object at a time as a merge.
 * <p>
 * <b>A merge, not a replace, and the difference is the whole design.</b> The client offers both:
 * {@code replace} PUTs a whole object, and {@code update} PATCHes the fields it is given. DBeaver
 * hands over only the columns that changed, so a replace would send those and silently blank
 * everything else on the object -- editing one cell would empty the rest of the row. So each edit
 * carries only its own columns, and every untouched property keeps its value.
 * <p>
 * That also means this cannot clear a property. Sending null in a merge is indistinguishable from
 * not sending the field, so an emptied cell leaves the old value in place rather than removing it.
 * Weaviate has no "unset" in a PATCH; clearing a property means replacing the object, which is a
 * different and much more destructive operation than the one a cleared cell asks for.
 * <p>
 * One request per row rather than a batch: the client's batch endpoint inserts and replaces, and
 * has no merge. Sending them individually is slower and is the only version that does what the
 * grid means.
 */
final class WeaviateUpdateBatch implements DBSDataManipulator.ExecuteBatch {

    private static final Log log = Log.getLog(WeaviateUpdateBatch.class);

    private final WeaviateCollection collection;
    private final WeaviateWriteColumns columns;
    private final Set<String> changed;
    private final String tenant;
    private final List<Edit> pending = new ArrayList<>();

    private record Edit(String id, Map<String, Object> properties, Map<String, float[]> vectors) {
    }

    /**
     * @param changed the columns DBeaver says were edited; everything else is left out so the
     *                merge does not touch it
     */
    WeaviateUpdateBatch(
        @NotNull WeaviateCollection collection,
        @NotNull WeaviateWriteColumns columns,
        @NotNull Set<String> changed,
        @Nullable String tenant
    ) {
        this.collection = collection;
        this.columns = columns;
        this.changed = changed;
        this.tenant = tenant;
    }

    @NotNull
    @Override
    public DBSDataManipulator.ExecuteBatch add(@NotNull Object[] values) throws DBCException {
        String id = columns.idOf(values);
        if (id == null) {
            // Without an id there is nothing to address. Weaviate has no other key: a collection
            // has no primary key of its own, and matching on property values would update rows
            // nobody selected.
            throw new DBCException(
                "This row has no " + WeaviateColumns.UUID + ", so there is nothing to update. "
                    + "Add the " + WeaviateColumns.UUID + " column to the query to edit rows.");
        }
        Map<String, Object> properties = columns.propertiesOf(values, changed);
        Map<String, float[]> vectors = columns.vectorsOf(values, changed);
        if (properties.isEmpty() && vectors.isEmpty()) {
            return this;
        }
        pending.add(new Edit(id, properties, vectors));
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
        int done = 0;
        try {
            var handle = collection.handle(tenant);
            for (Edit edit : pending) {
                handle.data.update(edit.id(), u -> {
                    if (!edit.properties().isEmpty()) {
                        u.properties(new LinkedHashMap<>(edit.properties()));
                    }
                    for (Map.Entry<String, float[]> vector : edit.vectors().entrySet()) {
                        u.vectors(Vectors.of(vector.getKey(), vector.getValue()));
                    }
                    return u;
                });
                done++;
            }
            statistics.setRowsUpdated(done);
        } catch (Exception e) {
            throw new DBCException(
                (done == 0 ? "Cannot update " : "Updated " + done + " row(s), then failed on ")
                    + collection.getName() + ": " + WeaviateWriteErrors.describe(e), e);
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
            "// Merge " + pending.size() + " object(s) in " + collection.getName()
                + " using the Java client v6.\n"
                + "// update() PATCHes the named fields; replace() would blank the rest.\n"
                + "client.collections.use(\"" + collection.getName()
                + "\").data.update(id, u -> u.properties(changed));"));
    }

    @Override
    public void close() {
        pending.clear();
    }
}
