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
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSAttributeBase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which grid columns a write may touch, and what each one is.
 * <p>
 * The grid shows more than the collection stores. Beside the declared properties sit the object's
 * id, its vectors, and a dozen read-only columns the query produced -- {@code _score},
 * {@code _distance}, {@code _explainScore}, {@code _created}, the group and generative columns.
 * They arrive in the same {@code Object[]} as everything else, and nothing in the array says which
 * is which.
 * <p>
 * <b>Getting that wrong is not cosmetic.</b> With auto-schema on, sending {@code _score} as a
 * property does not fail -- it creates a permanent {@code _score} property of whatever type the
 * value had, and a property's type cannot be changed afterwards. So the classification here is
 * what stands between a routine cell edit and a collection quietly acquiring junk fields it can
 * never lose.
 */
final class WeaviateWriteColumns {

    enum Kind {
        /** The object id. Not a property: it addresses the object rather than living in it. */
        UUID,
        /** A declared property, carrying the type its value must be coerced to. */
        PROPERTY,
        /** An embedding, written back through the same text form the grid shows. */
        VECTOR,
        /** Produced by the query, not stored. Never sent. */
        READ_ONLY
    }

    record Column(
        int index,
        @NotNull String name,
        @NotNull Kind kind,
        @Nullable String weaviateType,
        @Nullable String vectorName
    ) {
    }

    private final List<Column> columns;
    private final int uuidIndex;
    private final List<String> ignored;

    private WeaviateWriteColumns(List<Column> columns, int uuidIndex, List<String> ignored) {
        this.columns = columns;
        this.uuidIndex = uuidIndex;
        this.ignored = ignored;
    }

    /**
     * Classify the attributes DBeaver is about to hand over.
     *
     * @param declaredVectors the collection's vector names, needed because a single-vector
     *                        collection calls its column {@code _vector} with no name in it
     */
    @NotNull
    static WeaviateWriteColumns of(
        @NotNull WeaviateCollection collection,
        @NotNull DBSAttributeBase[] attributes,
        @NotNull DBRProgressMonitor monitor
    ) throws DBException {
        Map<String, String> types = new LinkedHashMap<>();
        for (WeaviateProperty property : collection.getProperties(monitor)) {
            String type = property.getWeaviateType();
            if (type != null) {
                types.put(property.getName(), type);
            }
        }
        List<String> declaredVectors = collection.getVectorNames();
        String defaultVector = declaredVectors.size() == 1 ? declaredVectors.get(0) : null;

        List<Column> columns = new ArrayList<>(attributes.length);
        List<String> ignored = new ArrayList<>();
        int uuidIndex = -1;
        for (int i = 0; i < attributes.length; i++) {
            String name = attributes[i].getName();
            if (WeaviateColumns.UUID.equalsIgnoreCase(name)) {
                uuidIndex = i;
                columns.add(new Column(i, name, Kind.UUID, null, null));
                continue;
            }
            String type = types.get(name);
            if (type != null) {
                columns.add(new Column(i, name, Kind.PROPERTY, type, null));
                continue;
            }
            String vectorName = WeaviateColumns.vectorNameOf(name, defaultVector);
            if (vectorName != null && declaredVectors.contains(vectorName)) {
                columns.add(new Column(i, name, Kind.VECTOR, null, vectorName));
                continue;
            }
            // Everything else the query produced. Named here so a write can say what it left
            // alone rather than dropping it in silence.
            columns.add(new Column(i, name, Kind.READ_ONLY, null, null));
            ignored.add(name);
        }
        return new WeaviateWriteColumns(columns, uuidIndex, ignored);
    }

    @NotNull
    List<Column> all() {
        return columns;
    }

    /** Index of the id column, or -1. */
    int uuidIndex() {
        return uuidIndex;
    }

    /** Read-only columns present in this write, for the warning. Never sent. */
    @NotNull
    List<String> ignored() {
        return ignored;
    }

    /**
     * The id in a row, or null when the column is absent or empty.
     *
     * @throws DBCException when the value is not a uuid -- addressing the wrong object is worse
     *                      than refusing
     */
    @Nullable
    String idOf(@NotNull Object[] values) throws DBCException {
        if (uuidIndex < 0 || uuidIndex >= values.length) {
            return null;
        }
        Object value = values[uuidIndex];
        String id = value == null ? null : value.toString().strip();
        if (id == null || id.isEmpty()) {
            return null;
        }
        try {
            return java.util.UUID.fromString(id).toString();
        } catch (IllegalArgumentException e) {
            throw new DBCException("\"" + id + "\" is not a valid " + WeaviateColumns.UUID);
        }
    }

    /**
     * Turn a row into the properties to send, skipping everything that is not a stored property.
     *
     * @param onlyChanged when non-null, the columns DBeaver says were edited; the rest are left
     *                    out so an update merges rather than overwrites
     */
    @NotNull
    Map<String, Object> propertiesOf(
        @NotNull Object[] values, @Nullable java.util.Set<String> onlyChanged
    ) throws DBCException {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Column column : columns) {
            if (column.kind() != Kind.PROPERTY || column.index() >= values.length) {
                continue;
            }
            if (onlyChanged != null && !onlyChanged.contains(column.name())) {
                continue;
            }
            Object wire = WeaviateValueCoercion.toWireValue(
                values[column.index()], column.weaviateType(), column.name());
            if (wire != null) {
                properties.put(column.name(), wire);
            }
        }
        return properties;
    }

    /** The vectors in a row, by name, parsed back from the text the grid shows. */
    @NotNull
    Map<String, float[]> vectorsOf(
        @NotNull Object[] values, @Nullable java.util.Set<String> onlyChanged
    ) throws DBCException {
        Map<String, float[]> vectors = new LinkedHashMap<>();
        for (Column column : columns) {
            if (column.kind() != Kind.VECTOR || column.index() >= values.length) {
                continue;
            }
            if (onlyChanged != null && !onlyChanged.contains(column.name())) {
                continue;
            }
            Object value = values[column.index()];
            String text = value == null ? null : value.toString().strip();
            if (text == null || text.isEmpty()) {
                continue;
            }
            try {
                // The same text a Near Vector search accepts, which is what the grid renders --
                // so an embedding round-trips through the cell without losing a dimension.
                vectors.put(column.vectorName(), WeaviateVectorParser.parse(text));
            } catch (RuntimeException e) {
                throw new DBCException(
                    "Cannot read " + column.name() + " as a vector: " + e.getMessage(), e);
            }
        }
        return vectors;
    }
}
