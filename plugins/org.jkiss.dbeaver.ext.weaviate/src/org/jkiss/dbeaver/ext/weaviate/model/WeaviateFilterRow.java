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
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.exec.DBCLogicalOperator;

import java.util.Arrays;
import java.util.List;

public record WeaviateFilterRow(
    @NotNull String property,
    @NotNull DBCLogicalOperator operator,
    @Nullable String rawValue,
    @NotNull DBPDataKind dataKind
) {
    /**
     * Operators supported by the visual filter builder. Mirrors what
     * {@link WeaviateFilterTranslator} can map to a Weaviate {@code Filter}.
     */
    public static final List<DBCLogicalOperator> SUPPORTED_OPERATORS = List.of(
        DBCLogicalOperator.EQUALS,
        DBCLogicalOperator.NOT_EQUALS,
        DBCLogicalOperator.GREATER,
        DBCLogicalOperator.GREATER_EQUALS,
        DBCLogicalOperator.LESS,
        DBCLogicalOperator.LESS_EQUALS,
        DBCLogicalOperator.LIKE,
        DBCLogicalOperator.IS_NULL,
        DBCLogicalOperator.IS_NOT_NULL,
        DBCLogicalOperator.IN
    );

    public boolean takesValue() {
        return takesValue(operator);
    }

    public static boolean takesValue(@NotNull DBCLogicalOperator op) {
        return op != DBCLogicalOperator.IS_NULL && op != DBCLogicalOperator.IS_NOT_NULL;
    }

    /**
     * Coerce {@link #rawValue} into the type expected for {@link #operator} given {@link #dataKind}.
     * For IN, splits on comma. Returns the value in a form that {@code Filter.property(name).eq(...)} accepts.
     */
    @Nullable
    public Object coercedValue() {
        if (!takesValue()) {
            return null;
        }
        if (rawValue == null) {
            return null;
        }
        if (operator == DBCLogicalOperator.IN) {
            String[] parts = Arrays.stream(rawValue.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
            return parts;
        }
        return coerceScalar(rawValue.strip(), dataKind);
    }

    @Nullable
    private static Object coerceScalar(@NotNull String trimmed, @NotNull DBPDataKind kind) {
        if (trimmed.isEmpty()) return null;
        switch (kind) {
            case BOOLEAN:
                return Boolean.parseBoolean(trimmed);
            case NUMERIC:
                try {
                    if (trimmed.contains(".") || trimmed.contains("e") || trimmed.contains("E")) {
                        return Double.parseDouble(trimmed);
                    }
                    return Long.parseLong(trimmed);
                } catch (NumberFormatException e) {
                    return trimmed;
                }
            case STRING:
            case DATETIME:
            default:
                return trimmed;
        }
    }
}
