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
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.exec.DBCAttributeMetaData;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.impl.local.LocalResultSet;
import org.jkiss.dbeaver.model.impl.local.LocalResultSetColumn;
import org.jkiss.dbeaver.model.impl.local.LocalStatement;

/**
 * A local result set whose stored columns are editable.
 * <p>
 * {@link LocalResultSetColumn#isReadOnly()} is hard-coded to {@code true}, and
 * {@code DBExecUtils#getAttributeReadOnlyStatus} consults it before anything else -- so with the
 * plain {@link LocalResultSet} every cell of a Weaviate result was greyed out no matter what the
 * collection reported about itself. The row identifier, the primary key over {@code uuid} and the
 * update support on the collection were all in place and none of them were ever reached.
 * <p>
 * Adding a row was unaffected, which is what made this confusing rather than obvious: the viewer
 * skips the attribute check entirely for a row in {@code STATE_ADDED}
 * ({@code ResultSetViewer#getAttributeReadOnlyStatus}), so inserting worked while editing the same
 * column in an existing row did not.
 * <p>
 * The split is deliberately the same one {@link WeaviateWriteColumns} makes, because it is the
 * same question asked twice: a column is editable exactly when a write is allowed to send it.
 * Declared properties and vectors are editable; {@code uuid} and the columns the query produced
 * ({@code _score}, {@code _distance}, the group and generative columns) are not. Leaving the id
 * read-only costs nothing on insert -- a new row bypasses the check -- and on an existing row it
 * is the right answer, since changing an object's id is a move, not an edit.
 */
class WeaviateResultSet extends LocalResultSet<LocalStatement> {

    WeaviateResultSet(@NotNull DBCSession session, @NotNull LocalStatement statement) {
        super(session, statement);
    }

    /**
     * Add a column holding a value the collection actually stores, which a user may edit.
     * <p>
     * Inherited {@code addColumn} stays read-only, so a column added by a path that has not
     * thought about writes is safe by default rather than editable by accident.
     */
    @NotNull
    DBCAttributeMetaData addStoredColumn(@NotNull String label, @NotNull DBPDataKind dataKind) {
        EditableColumn column = new EditableColumn(this, getColumnCount(), label, dataKind);
        addColumn(column);
        return column;
    }

    private static class EditableColumn extends LocalResultSetColumn {

        EditableColumn(
            @NotNull WeaviateResultSet resultSet,
            int index,
            @NotNull String label,
            @NotNull DBPDataKind dataKind
        ) {
            super(resultSet, index, label, dataKind);
        }

        @Override
        public boolean isReadOnly() {
            return false;
        }
    }
}
