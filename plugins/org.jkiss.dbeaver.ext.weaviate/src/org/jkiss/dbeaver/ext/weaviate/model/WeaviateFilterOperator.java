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
import org.jkiss.dbeaver.model.exec.DBCLogicalOperator;

/**
 * The filter predicates the Query panel offers, and the single vocabulary the translator speaks.
 * <p>
 * The panel used to borrow DBeaver's {@link DBCLogicalOperator} directly, which cost more than it
 * saved: that enum has no constant for {@code containsNone}, and its {@code IN}/{@code CONTAINS}
 * names say nothing useful about an array property. This enum names what Weaviate actually does,
 * and {@link #fromDataFilterOperator} maps DBeaver's constants onto it so the result-grid column
 * filters keep working through the same translation.
 * <p>
 * Declaration order is the order of the operator dropdown.
 */
public enum WeaviateFilterOperator {

    EQUALS("= (equals)", Arity.SCALAR),
    NOT_EQUALS("!= (not equals)", Arity.SCALAR),
    GREATER("> (greater)", Arity.SCALAR),
    GREATER_EQUALS(">= (greater or equal)", Arity.SCALAR),
    LESS("< (less)", Arity.SCALAR),
    LESS_EQUALS("<= (less or equal)", Arity.SCALAR),
    BETWEEN("BETWEEN (low, high)", Arity.RANGE),
    LIKE("LIKE (* and ? wildcards)", Arity.SCALAR),
    NOT_LIKE("NOT LIKE", Arity.SCALAR),
    CONTAINS_ANY("CONTAINS ANY (comma-separated)", Arity.LIST),
    CONTAINS_ALL("CONTAINS ALL (comma-separated)", Arity.LIST),
    CONTAINS_NONE("CONTAINS NONE (comma-separated)", Arity.LIST),
    IS_NULL("IS NULL", Arity.NONE),
    IS_NOT_NULL("IS NOT NULL", Arity.NONE);

    /** How many values the operator reads from the row's single value cell. */
    private enum Arity {
        /** None -- the value cell is disabled. */
        NONE,
        /** One. */
        SCALAR,
        /** Exactly two, comma-separated: the low and high of a range. */
        RANGE,
        /** Any number, comma-separated. */
        LIST
    }

    private final String label;
    private final Arity arity;

    WeaviateFilterOperator(String label, Arity arity) {
        this.label = label;
        this.arity = arity;
    }

    @NotNull
    public String getLabel() {
        return label;
    }

    public boolean takesValue() {
        return arity != Arity.NONE;
    }

    /** Whether the value cell holds a comma-separated list rather than one value. */
    public boolean takesList() {
        return arity == Arity.LIST || arity == Arity.RANGE;
    }

    /** How many values the cell must yield, or -1 for any number. */
    public int valueCount() {
        return switch (arity) {
            case NONE -> 0;
            case SCALAR -> 1;
            case RANGE -> 2;
            case LIST -> -1;
        };
    }

    /**
     * The equivalent of one of DBeaver's own operators, or null when Weaviate cannot express it.
     * <p>
     * {@code IN} and {@code CONTAINS} both land on a contains predicate -- {@code IN} is "any of
     * these", {@code CONTAINS} is "all of these" -- which is the closest honest reading of two
     * names that predate this plugin.
     * <p>
     * {@code ILIKE} deliberately returns null. Weaviate's {@code Like} is case-sensitive and the
     * client offers no case-insensitive form, so the operator used to be accepted and quietly
     * translated to {@code LIKE}: a filter that ignored the one thing the user chose it for.
     * A refusal they can see is better. {@code REGEX}, {@code SOUNDS} and {@code CONTAINS_KEY}
     * have no counterpart at all.
     */
    @Nullable
    public static WeaviateFilterOperator fromDataFilterOperator(@Nullable DBCLogicalOperator op) {
        if (op == null) {
            return null;
        }
        return switch (op) {
            case EQUALS -> EQUALS;
            case NOT_EQUALS -> NOT_EQUALS;
            case GREATER -> GREATER;
            case GREATER_EQUALS -> GREATER_EQUALS;
            case LESS -> LESS;
            case LESS_EQUALS -> LESS_EQUALS;
            case BETWEEN -> BETWEEN;
            case LIKE -> LIKE;
            case NOT_LIKE -> NOT_LIKE;
            case IN -> CONTAINS_ANY;
            case CONTAINS -> CONTAINS_ALL;
            case IS_NULL -> IS_NULL;
            case IS_NOT_NULL -> IS_NOT_NULL;
            case ILIKE, REGEX, SOUNDS, CONTAINS_KEY -> null;
        };
    }
}
