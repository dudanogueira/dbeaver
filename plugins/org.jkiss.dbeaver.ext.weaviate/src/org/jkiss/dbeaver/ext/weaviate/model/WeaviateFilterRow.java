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

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;

public record WeaviateFilterRow(
    @NotNull String property,
    @NotNull WeaviateFilterOperator operator,
    @Nullable String rawValue,
    @NotNull DBPDataKind dataKind
) {
    /**
     * Operators offered by the visual filter builder: all of them.
     * <p>
     * The list used to be a hand-picked subset of DBeaver's operators, with the invariant that
     * every entry had to be translatable. {@link WeaviateFilterOperator} now *is* the translatable
     * set, so the subset and the invariant both go away.
     */
    public static final List<WeaviateFilterOperator> SUPPORTED_OPERATORS =
        List.of(WeaviateFilterOperator.values());

    /** Always writes seconds, which {@link OffsetDateTime#toString()} does not. */
    private static final DateTimeFormatter RFC3339 =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    public boolean takesValue() {
        return operator.takesValue();
    }

    /**
     * Coerce {@link #rawValue} into the type expected for {@link #operator} given
     * {@link #dataKind}, in a form the client's {@code Filter} builders accept.
     * <p>
     * List and range operators split on comma and coerce <em>each element</em>. That matters: the
     * old code returned {@code String[]} for every list, so a contains filter on an {@code int[]}
     * property sent text operands and matched nothing. The element type also decides which typed
     * overload the translator can reach.
     *
     * @return null when the operator takes no value; a single value; or an array of values
     */
    @Nullable
    public Object coercedValue() {
        if (!takesValue() || rawValue == null) {
            return null;
        }
        if (operator.takesList()) {
            String[] parts = Arrays.stream(rawValue.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
            return coerceEach(parts, dataKind);
        }
        return coerceScalar(rawValue.strip(), dataKind);
    }

    /**
     * Coerce every element to the column's type, boxed into an array whose component type the
     * client can dispatch on -- it inspects the array type, so {@code Object[]} of Longs is not
     * the same to it as {@code Long[]}.
     */
    @NotNull
    private static Object coerceEach(@NotNull String[] parts, @NotNull DBPDataKind kind) {
        Object[] coerced = new Object[parts.length];
        for (int i = 0; i < parts.length; i++) {
            coerced[i] = coerceScalar(parts[i], kind);
        }
        return switch (kind) {
            case BOOLEAN -> Arrays.copyOf(coerced, coerced.length, Boolean[].class);
            case NUMERIC -> allLongs(coerced)
                ? Arrays.copyOf(coerced, coerced.length, Long[].class)
                : toDoubles(coerced);
            default -> Arrays.copyOf(coerced, coerced.length, String[].class);
        };
    }

    private static boolean allLongs(@NotNull Object[] values) {
        for (Object v : values) {
            if (!(v instanceof Long)) {
                return false;
            }
        }
        return true;
    }

    /**
     * A numeric list has to be all one type for the client's array dispatch, so a mixed
     * "1, 2.5" widens to Double rather than failing.
     */
    @NotNull
    private static Double[] toDoubles(@NotNull Object[] values) {
        Double[] out = new Double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i] instanceof Number n ? n.doubleValue() : null;
        }
        return out;
    }

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
                    // Reported, not silently passed through as text: a text operand against a
                    // number property matches nothing, and looks like "the filter found no rows".
                    throw new WeaviateUnsupportedFilterException(
                        "\"" + trimmed + "\" is not a number.");
                }
            case DATETIME:
                try {
                    // Parsed to validate, then re-emitted as RFC3339 text rather than handed over
                    // as an OffsetDateTime. The client serialises those with toString(), which
                    // omits the seconds on a whole minute ("2024-03-01T00:00Z") -- and Weaviate's
                    // RFC3339 parser rejects that, so every filter on a round time failed. The
                    // formatter below always writes seconds. Verified both ways against 1.39.
                    return RFC3339.format(OffsetDateTime.parse(trimmed));
                } catch (DateTimeParseException e) {
                    throw new WeaviateUnsupportedFilterException(
                        "\"" + trimmed + "\" is not an ISO-8601 date-time "
                            + "(for example 2024-01-01T00:00:00Z).");
                }
            case STRING:
            default:
                return trimmed;
        }
    }
}
