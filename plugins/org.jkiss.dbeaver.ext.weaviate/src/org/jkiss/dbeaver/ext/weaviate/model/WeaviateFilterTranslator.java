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
        // A custom WHERE expression typed into the grid's filter box is not translatable:
        // there is no SQL engine behind Weaviate to hand it to. Dropping it silently would
        // run the query unfiltered and return rows the user asked to exclude, so refuse it
        // outright -- same contract as an untranslatable operator.
        String where = dataFilter.getWhere();
        if (where != null && !where.isBlank()) {
            throw new WeaviateUnsupportedFilterException(
                "Weaviate cannot evaluate the custom filter expression \"" + where.strip()
                    + "\". Use the column filters or the Weaviate Query panel's filter rows instead.");
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
        // Everything funnels through one vocabulary, so the grid path and the panel path cannot
        // translate the same predicate two different ways.
        WeaviateFilterOperator mapped = WeaviateFilterOperator.fromDataFilterOperator(op);
        if (mapped == null) {
            throw WeaviateUnsupportedFilterException.forOperator(name, op);
        }
        Object value = c.getValue();
        // hasCondition() is true as soon as an operator is set, which is the state a column
        // filter is left in once its value is cleared. That means "no condition", so it is
        // skipped -- unlike a panel row, which only exists because someone built it and whose
        // empty value cell is a mistake worth reporting.
        if (value == null && needsValue(mapped)) {
            return null;
        }
        boolean reverse = c.isReverseOperator();
        Filter f = applyOperator(name, mapped, value);
        if (f == null) {
            return null;
        }
        return reverse ? f.not() : f;
    }

    /**
     * The one place a predicate becomes a client {@code Filter}.
     * <p>
     * A required value that is missing is an error, not a no-op. Returning null here would drop
     * the row while the panel still showed it as an active filter -- the user would be looking at
     * more rows than they asked for, with nothing to say why.
     */
    @Nullable
    private static Filter applyOperator(
        @NotNull String name,
        @NotNull WeaviateFilterOperator op,
        @Nullable Object value
    ) {
        if (UUID_COLUMN.equalsIgnoreCase(name)) {
            return uuidFilter(name, op, value);
        }
        if (WeaviateColumns.CREATED.equals(name) || WeaviateColumns.UPDATED.equals(name)) {
            return timestampFilter(name, op, value);
        }
        Filter.FilterBuilder property = Filter.property(name);
        switch (op) {
            case EQUALS:
                // A null value here is DBeaver's way of writing "= NULL" in a column filter.
                return value == null ? property.isNull() : wrap(name, () -> property.eq(value));
            case NOT_EQUALS:
                return value == null ? property.isNotNull() : wrap(name, () -> property.ne(value));
            case GREATER:
                return wrap(name, () -> property.gt(require(name, op, value)));
            case GREATER_EQUALS:
                return wrap(name, () -> property.gte(require(name, op, value)));
            case LESS:
                return wrap(name, () -> property.lt(require(name, op, value)));
            case LESS_EQUALS:
                return wrap(name, () -> property.lte(require(name, op, value)));
            case IS_NULL:
                return property.isNull();
            case IS_NOT_NULL:
                return property.isNotNull();
            case LIKE:
                return property.like(require(name, op, value).toString());
            case NOT_LIKE:
                return property.like(require(name, op, value).toString()).not();
            case CONTAINS_ANY:
                return wrap(name, () -> containsAny(property, requireList(name, op, value)));
            case CONTAINS_ALL:
                return wrap(name, () -> containsAll(property, requireList(name, op, value)));
            case CONTAINS_NONE:
                return wrap(name, () -> containsNone(property, requireList(name, op, value)));
            case BETWEEN:
                Object[] range = toRange(requireList(name, op, value));
                if (range == null) {
                    throw new WeaviateUnsupportedFilterException(
                        "BETWEEN on \"" + name + "\" needs exactly two values, a low and a high.");
                }
                return wrap(name, () -> Filter.and(property.gte(range[0]), property.lte(range[1])));
            default:
                throw new WeaviateUnsupportedFilterException(
                    "Weaviate does not support the '" + op.getLabel() + "' operator on \""
                        + name + "\".");
        }
    }

    // The contains predicates have one overload per element type and no Object form, so the
    // array the row coerced decides which one is reachable.

    @NotNull
    private static Filter containsAny(@NotNull Filter.FilterBuilder p, @NotNull Object values) {
        if (values instanceof Long[] v) return p.containsAny(v);
        if (values instanceof Double[] v) return p.containsAny(v);
        if (values instanceof Boolean[] v) return p.containsAny(v);
        return p.containsAny(toStringArray(values));
    }

    @NotNull
    private static Filter containsAll(@NotNull Filter.FilterBuilder p, @NotNull Object values) {
        if (values instanceof Long[] v) return p.containsAll(v);
        if (values instanceof Double[] v) return p.containsAll(v);
        if (values instanceof Boolean[] v) return p.containsAll(v);
        return p.containsAll(toStringArray(values));
    }

    @NotNull
    private static Filter containsNone(@NotNull Filter.FilterBuilder p, @NotNull Object values) {
        if (values instanceof Long[] v) return p.containsNone(v);
        if (values instanceof Double[] v) return p.containsNone(v);
        if (values instanceof Boolean[] v) return p.containsNone(v);
        return p.containsNone(toStringArray(values));
    }

    /**
     * The synthetic uuid column. {@code UuidProperty} is a much narrower type than a property
     * path -- no like, no containsAll, no null test -- so what it cannot do is named rather than
     * left to fail as a confusing server error.
     */
    @NotNull
    private static Filter uuidFilter(
        @NotNull String name,
        @NotNull WeaviateFilterOperator op,
        @Nullable Object value
    ) {
        switch (op) {
            case EQUALS:
                return Filter.uuid().eq(require(name, op, value).toString());
            case NOT_EQUALS:
                return Filter.uuid().ne(require(name, op, value).toString());
            case GREATER:
                return Filter.uuid().gt(require(name, op, value).toString());
            case GREATER_EQUALS:
                return Filter.uuid().gte(require(name, op, value).toString());
            case LESS:
                return Filter.uuid().lt(require(name, op, value).toString());
            case LESS_EQUALS:
                return Filter.uuid().lte(require(name, op, value).toString());
            case CONTAINS_ANY:
                return Filter.uuid().containsAny(toStringArray(requireList(name, op, value)));
            case CONTAINS_NONE:
                return Filter.uuid().containsNone(toStringArray(requireList(name, op, value)));
            case IS_NULL:
            case IS_NOT_NULL:
                throw new WeaviateUnsupportedFilterException(
                    "Weaviate cannot test the object UUID for NULL - every object always has one.");
            default:
                throw new WeaviateUnsupportedFilterException(
                    "The object UUID does not support '" + op.getLabel()
                        + "'. It accepts =, !=, the comparisons, CONTAINS ANY and CONTAINS NONE.");
        }
    }

    /**
     * The synthetic creation/update-time columns. These are object metadata, not properties, and
     * the client reaches them through their own builders -- filtering them as a property silently
     * matches nothing, since no property of that name exists.
     */
    @NotNull
    private static Filter timestampFilter(
        @NotNull String name,
        @NotNull WeaviateFilterOperator op,
        @Nullable Object value
    ) {
        // Weaviate's own name for the metadata path. Reached as a property with an RFC3339 text
        // operand rather than through Filter.createdAt(), whose OffsetDateTime overloads hit the
        // client's toString() problem (see WeaviateFilterRow) -- this route takes the string the
        // row already produced and works on a whole minute.
        Filter.FilterBuilder when = Filter.property(
            WeaviateColumns.CREATED.equals(name) ? "_creationTimeUnix" : "_lastUpdateTimeUnix");
        switch (op) {
            case EQUALS:
                return when.eq(require(name, op, value).toString());
            case NOT_EQUALS:
                return when.ne(require(name, op, value).toString());
            case GREATER:
                return when.gt(require(name, op, value).toString());
            case GREATER_EQUALS:
                return when.gte(require(name, op, value).toString());
            case LESS:
                return when.lt(require(name, op, value).toString());
            case LESS_EQUALS:
                return when.lte(require(name, op, value).toString());
            case BETWEEN:
                Object[] range = toRange(requireList(name, op, value));
                if (range == null) {
                    throw new WeaviateUnsupportedFilterException(
                        "BETWEEN on \"" + name + "\" needs exactly two values, a low and a high.");
                }
                return Filter.and(
                    when.gte(String.valueOf(range[0])), when.lte(String.valueOf(range[1])));
            default:
                throw new WeaviateUnsupportedFilterException(
                    "\"" + name + "\" is a timestamp and does not support '" + op.getLabel()
                        + "'. It accepts =, !=, the comparisons and BETWEEN.");
        }
    }

    /**
     * Whether the operator cannot be built without a value. EQUALS and NOT_EQUALS are absent on
     * purpose: with a null value they mean IS NULL and IS NOT NULL, which is how DBeaver writes
     * "= NULL" in a column filter.
     */
    private static boolean needsValue(@NotNull WeaviateFilterOperator op) {
        return switch (op) {
            case EQUALS, NOT_EQUALS, IS_NULL, IS_NOT_NULL -> false;
            default -> true;
        };
    }

    @NotNull
    private static Object require(
        @NotNull String name,
        @NotNull WeaviateFilterOperator op,
        @Nullable Object value
    ) {
        if (value == null) {
            throw new WeaviateUnsupportedFilterException(
                "'" + op.getLabel() + "' on \"" + name + "\" needs a value.");
        }
        return value;
    }

    @NotNull
    private static Object requireList(
        @NotNull String name,
        @NotNull WeaviateFilterOperator op,
        @Nullable Object value
    ) {
        Object present = require(name, op, value);
        if (present instanceof Object[] arr && arr.length == 0) {
            throw new WeaviateUnsupportedFilterException(
                "'" + op.getLabel() + "' on \"" + name + "\" needs at least one value.");
        }
        return present;
    }

    /**
     * Run a builder call, translating the client's own complaint about a value type into this
     * package's exception so every filter failure reaches the banner the same way.
     */
    @NotNull
    private static Filter wrap(@NotNull String name, @NotNull java.util.function.Supplier<Filter> call) {
        try {
            return call.get();
        } catch (IllegalArgumentException e) {
            throw new WeaviateUnsupportedFilterException(
                "Weaviate cannot filter \"" + name + "\" on that value: " + e.getMessage());
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
