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

import io.weaviate.client6.v1.api.collections.query.Filter;
import io.weaviate.client6.v1.api.collections.query.FilterOperand;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.data.DBDAttributeConstraint;
import org.jkiss.dbeaver.model.data.DBDDataFilter;
import org.jkiss.dbeaver.model.exec.DBCLogicalOperator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class WeaviateFilterTranslator {

    public static final String UUID_COLUMN = "uuid";

    private WeaviateFilterTranslator() {
    }

    @Nullable
    public static Filter translate(@Nullable DBDDataFilter dataFilter) {
        if (dataFilter == null) {
            return null;
        }
        List<FilterOperand> operands = new ArrayList<>();
        for (DBDAttributeConstraint c : dataFilter.getConstraints()) {
            FilterOperand op = constraintToOperand(c);
            if (op != null) {
                operands.add(op);
            }
        }
        if (operands.isEmpty()) {
            return null;
        }
        if (operands.size() == 1 && operands.get(0) instanceof Filter f) {
            return f;
        }
        return dataFilter.isAnyConstraint() ? Filter.or(operands) : Filter.and(operands);
    }

    /**
     * Translate user-built filter rows from the panel into a single {@link Filter}.
     * Returns {@code null} if the row list is empty or every row is a no-op.
     */
    @Nullable
    public static Filter translateRows(@Nullable List<WeaviateFilterRow> rows, boolean anyConstraint) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        List<FilterOperand> operands = new ArrayList<>(rows.size());
        for (WeaviateFilterRow row : rows) {
            Filter f = applyOperator(row.property(), row.operator(), row.coercedValue());
            if (f != null) {
                operands.add(f);
            }
        }
        if (operands.isEmpty()) {
            return null;
        }
        if (operands.size() == 1 && operands.get(0) instanceof Filter f) {
            return f;
        }
        return anyConstraint ? Filter.or(operands) : Filter.and(operands);
    }

    /**
     * AND-combine two optional filters. Returns {@code null} if both are null,
     * the non-null one if exactly one is present, or {@code Filter.and(a, b)} otherwise.
     */
    @Nullable
    public static Filter and(@Nullable Filter a, @Nullable Filter b) {
        if (a == null) return b;
        if (b == null) return a;
        return Filter.and(a, b);
    }

    @Nullable
    private static FilterOperand constraintToOperand(@NotNull DBDAttributeConstraint c) {
        if (!c.hasCondition()) {
            return null;
        }
        DBCLogicalOperator op = c.getOperator();
        if (op == null) {
            return null;
        }
        String name = constraintAttributeName(c);
        if (name == null || name.isEmpty()) {
            return null;
        }
        Object value = c.getValue();
        boolean reverse = c.isReverseOperator();
        Filter f = applyOperator(name, op, value);
        if (f == null) {
            return null;
        }
        return reverse ? f.not() : f;
    }

    @Nullable
    private static Filter applyOperator(
        @NotNull String name,
        @NotNull DBCLogicalOperator op,
        @Nullable Object value
    ) {
        boolean isUuid = UUID_COLUMN.equalsIgnoreCase(name);
        switch (op) {
            case EQUALS:
                if (isUuid && value != null) {
                    return Filter.uuid().eq(value.toString());
                }
                if (value == null) {
                    return Filter.property(name).isNull();
                }
                return Filter.property(name).eq(value);
            case NOT_EQUALS:
                if (isUuid && value != null) {
                    return Filter.uuid().ne(value.toString());
                }
                if (value == null) {
                    return Filter.property(name).isNotNull();
                }
                return Filter.property(name).ne(value);
            case GREATER:
                if (value == null) return null;
                if (isUuid) return Filter.uuid().gt(value.toString());
                return Filter.property(name).gt(value);
            case GREATER_EQUALS:
                if (value == null) return null;
                if (isUuid) return Filter.uuid().gte(value.toString());
                return Filter.property(name).gte(value);
            case LESS:
                if (value == null) return null;
                if (isUuid) return Filter.uuid().lt(value.toString());
                return Filter.property(name).lt(value);
            case LESS_EQUALS:
                if (value == null) return null;
                if (isUuid) return Filter.uuid().lte(value.toString());
                return Filter.property(name).lte(value);
            case IS_NULL:
                if (isUuid) return null;
                return Filter.property(name).isNull();
            case IS_NOT_NULL:
                if (isUuid) return null;
                return Filter.property(name).isNotNull();
            case LIKE:
            case ILIKE:
                if (value == null) return null;
                return Filter.property(name).like(value.toString());
            case NOT_LIKE:
                if (value == null) return null;
                return Filter.property(name).like(value.toString()).not();
            case IN:
                String[] inValues = toStringArray(value);
                if (inValues.length == 0) return null;
                if (isUuid) {
                    return Filter.uuid().containsAny(inValues);
                }
                return Filter.property(name).containsAny(inValues);
            case BETWEEN:
                Object[] range = toRange(value);
                if (range == null) return null;
                Filter low = Filter.property(name).gte(range[0]);
                Filter high = Filter.property(name).lte(range[1]);
                return Filter.and(low, high);
            default:
                return null;
        }
    }

    @Nullable
    private static String constraintAttributeName(@NotNull DBDAttributeConstraint c) {
        if (c.getAttribute() != null) {
            return c.getAttribute().getName();
        }
        return c.getAttributeName();
    }

    @NotNull
    private static String[] toStringArray(@Nullable Object value) {
        if (value == null) {
            return new String[0];
        }
        if (value instanceof Object[] arr) {
            String[] out = new String[arr.length];
            for (int i = 0; i < arr.length; i++) {
                out[i] = arr[i] == null ? null : arr[i].toString();
            }
            return out;
        }
        if (value instanceof Collection<?> col) {
            String[] out = new String[col.size()];
            int i = 0;
            for (Object o : col) {
                out[i++] = o == null ? null : o.toString();
            }
            return out;
        }
        return new String[]{value.toString()};
    }

    @Nullable
    private static Object[] toRange(@Nullable Object value) {
        if (value instanceof Object[] arr && arr.length == 2) {
            return arr;
        }
        if (value instanceof Collection<?> col && col.size() == 2) {
            return col.toArray();
        }
        return null;
    }
}
