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
import org.jkiss.dbeaver.model.data.DBDAttributeConstraint;
import org.jkiss.dbeaver.model.data.DBDDataFilter;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.exec.DBCLogicalOperator;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class WeaviateFilterTranslateTest extends DBeaverUnitTest {

    @Test
    public void nullFilterReturnsNull() {
        Assertions.assertNull(WeaviateFilterTranslator.translate(null));
    }

    @Test
    public void filterWithoutConstraintsReturnsNull() {
        Assertions.assertNull(WeaviateFilterTranslator.translate(new DBDDataFilter()));
    }

    @Test
    public void singleEqualsConstraintBecomesPropertyEq() {
        DBDDataFilter f = filter(constraint("name", DBCLogicalOperator.EQUALS, "alice"));
        Filter filter = WeaviateFilterTranslator.translate(f);
        Assertions.assertNotNull(filter);
        Assertions.assertFalse(filter.isEmpty());
    }

    @Test
    public void uuidEqualsUsesUuidPath() {
        DBDDataFilter f = filter(constraint("uuid", DBCLogicalOperator.EQUALS, "abc"));
        Filter filter = WeaviateFilterTranslator.translate(f);
        Assertions.assertNotNull(filter);
        // Best-effort smoke check on toString — uuid filters mention "_id" or "uuid"
        String s = filter.toString().toLowerCase();
        Assertions.assertTrue(s.contains("_id") || s.contains("uuid"),
            "uuid filter should reference _id/uuid: " + s);
    }

    @Test
    public void isNullProducesIsNull() {
        DBDDataFilter f = filter(constraint("title", DBCLogicalOperator.IS_NULL, null));
        Filter filter = WeaviateFilterTranslator.translate(f);
        Assertions.assertNotNull(filter);
    }

    @Test
    public void inProducesContainsAny() {
        DBDDataFilter f = filter(constraint("tag", DBCLogicalOperator.IN, new Object[]{"a", "b", "c"}));
        Filter filter = WeaviateFilterTranslator.translate(f);
        Assertions.assertNotNull(filter);
    }

    @Test
    public void multipleConstraintsAreAndedByDefault() {
        DBDDataFilter f = filter(
            constraint("a", DBCLogicalOperator.EQUALS, "x"),
            constraint("b", DBCLogicalOperator.EQUALS, "y")
        );
        Filter filter = WeaviateFilterTranslator.translate(f);
        Assertions.assertNotNull(filter);
        Assertions.assertFalse(filter.isEmpty());
    }

    @Test
    public void anyConstraintProducesOr() {
        DBDDataFilter f = new DBDDataFilter(Arrays.asList(
            constraint("a", DBCLogicalOperator.EQUALS, "x"),
            constraint("b", DBCLogicalOperator.EQUALS, "y")
        ));
        f.setAnyConstraint(true);
        Filter filter = WeaviateFilterTranslator.translate(f);
        Assertions.assertNotNull(filter);
        Assertions.assertFalse(filter.isEmpty());
    }

    @Test
    public void reverseOperatorWrapsInNot() {
        DBDAttributeConstraint c = constraint("name", DBCLogicalOperator.EQUALS, "alice");
        c.setReverseOperator(true);
        DBDDataFilter f = filter(c);
        Filter filter = WeaviateFilterTranslator.translate(f);
        Assertions.assertNotNull(filter);
        Assertions.assertFalse(filter.isEmpty());
    }

    @Test
    public void betweenProducesAndOfGteAndLte() {
        DBDDataFilter f = filter(constraint("price", DBCLogicalOperator.BETWEEN, new Object[]{10, 20}));
        Filter filter = WeaviateFilterTranslator.translate(f);
        Assertions.assertNotNull(filter);
        Assertions.assertFalse(filter.isEmpty());
    }

    @Test
    public void notEqualsProducesNotEqualOperator() {
        DBDDataFilter f = filter(constraint("answer", DBCLogicalOperator.NOT_EQUALS, "Yucatan"));
        Filter filter = WeaviateFilterTranslator.translate(f);
        Assertions.assertNotNull(filter);
        Assertions.assertTrue(filter.toString().contains("NotEqual"),
            "expected a NotEqual operand, got: " + filter);
    }

    @Test
    public void notEqualsWithNullValueBecomesIsNotNull() {
        DBDDataFilter f = filter(constraint("answer", DBCLogicalOperator.NOT_EQUALS, null));
        Filter filter = WeaviateFilterTranslator.translate(f);
        Assertions.assertNotNull(filter);
        Assertions.assertTrue(filter.toString().contains("IsNull"),
            "null-valued != should test for presence, got: " + filter);
    }

    /**
     * The shape from the Weaviate Python client's docs:
     * round == "Double Jeopardy!" AND points &lt; 600 AND NOT(answer == "Yucatan").
     * Built through the Query panel's filter rows, which is the path users hit.
     */
    @Test
    public void panelRowsBuildAndedFilterWithNegation() {
        List<WeaviateFilterRow> rows = List.of(
            new WeaviateFilterRow("round", WeaviateFilterOperator.EQUALS, "Double Jeopardy!", DBPDataKind.STRING),
            new WeaviateFilterRow("points", WeaviateFilterOperator.LESS, "600", DBPDataKind.NUMERIC),
            new WeaviateFilterRow("answer", WeaviateFilterOperator.NOT_EQUALS, "Yucatan", DBPDataKind.STRING));
        Filter filter = WeaviateFilterTranslator.translateRows(rows, false);
        Assertions.assertNotNull(filter);
        String s = filter.toString();
        Assertions.assertTrue(s.contains("And"), "rows should be ANDed: " + s);
        Assertions.assertTrue(s.contains("NotEqual"), "negation should survive: " + s);
        Assertions.assertTrue(s.contains("LessThan"), "numeric compare should survive: " + s);
    }

    @Test
    public void panelRowsHonourMatchAnyForOr() {
        List<WeaviateFilterRow> rows = List.of(
            new WeaviateFilterRow("round", WeaviateFilterOperator.EQUALS, "Jeopardy!", DBPDataKind.STRING),
            new WeaviateFilterRow("answer", WeaviateFilterOperator.NOT_EQUALS, "Yucatan", DBPDataKind.STRING));
        Filter filter = WeaviateFilterTranslator.translateRows(rows, true);
        Assertions.assertNotNull(filter);
        Assertions.assertTrue(filter.toString().contains("Or"),
            "Match=any should OR the rows: " + filter);
    }

    /**
     * A typed filter expression has no SQL engine behind it. Dropping it silently would
     * widen the result set, so it must fail loudly instead.
     */
    @Test
    public void customWhereExpressionIsRejectedNotIgnored() {
        DBDDataFilter f = filter(constraint("answer", DBCLogicalOperator.EQUALS, "x"));
        f.setWhere("answer != 'Yucatan'");
        WeaviateUnsupportedFilterException e = Assertions.assertThrows(
            WeaviateUnsupportedFilterException.class,
            () -> WeaviateFilterTranslator.translate(f));
        Assertions.assertTrue(e.getMessage().contains("Yucatan"), e.getMessage());
    }

    @Test
    public void blankCustomWhereIsIgnored() {
        DBDDataFilter f = filter(constraint("answer", DBCLogicalOperator.EQUALS, "x"));
        f.setWhere("   ");
        Assertions.assertNotNull(WeaviateFilterTranslator.translate(f));
    }

    private static DBDDataFilter filter(DBDAttributeConstraint... constraints) {
        return new DBDDataFilter(Arrays.asList(constraints));
    }

    private static DBDAttributeConstraint constraint(String name, DBCLogicalOperator op, Object value) {
        DBDAttributeConstraint c = new DBDAttributeConstraint(name, 0);
        c.setOperator(op);
        c.setValue(value);
        return c;
    }

    @SuppressWarnings("unused")
    private static List<Object> emptyArgs() {
        return Collections.emptyList();
    }

    /**
     * The three contains predicates are distinct client calls, not variations on one. Asserting
     * the emitted operator name is what tells CONTAINS ALL apart from CONTAINS ANY -- a non-null
     * assertion would pass either way.
     */
    @Test
    public void eachContainsOperatorEmitsItsOwnPredicate() {
        record Case(WeaviateFilterOperator op, String expected) { }
        for (Case c : java.util.List.of(
            new Case(WeaviateFilterOperator.CONTAINS_ANY, "ContainsAny"),
            new Case(WeaviateFilterOperator.CONTAINS_ALL, "ContainsAll"),
            new Case(WeaviateFilterOperator.CONTAINS_NONE, "ContainsNone"))
        ) {
            WeaviateFilterRow row =
                new WeaviateFilterRow("tags", c.op(), "x, y", DBPDataKind.STRING);
            Filter f = WeaviateFilterTranslator.translateRows(java.util.List.of(row), false);
            Assertions.assertNotNull(f, () -> c.op() + " produced no filter");
            Assertions.assertTrue(f.toString().contains(c.expected()),
                () -> c.op() + " should emit " + c.expected() + ", got: " + f);
        }
    }

    /**
     * The creation and update times are object metadata, not properties. Filtering them as a
     * property targets a path that does not exist, so the query matches nothing.
     */
    @Test
    public void timestampColumnsUseTheMetadataPathNotAProperty() {
        for (String column : java.util.List.of(WeaviateColumns.CREATED, WeaviateColumns.UPDATED)) {
            WeaviateFilterRow row = new WeaviateFilterRow(
                column, WeaviateFilterOperator.GREATER,
                "2024-01-01T00:00:00Z", DBPDataKind.DATETIME);
            Filter f = WeaviateFilterTranslator.translateRows(java.util.List.of(row), false);
            Assertions.assertNotNull(f);
            Assertions.assertFalse(f.toString().contains(column),
                () -> column + " was filtered as a property path: " + f);
        }
    }

    @Test
    public void timestampColumnsRefuseOperatorsTheyCannotExpress() {
        WeaviateFilterRow row = new WeaviateFilterRow(
            WeaviateColumns.CREATED, WeaviateFilterOperator.LIKE, "2024*", DBPDataKind.DATETIME);
        Assertions.assertThrows(
            WeaviateUnsupportedFilterException.class,
            () -> WeaviateFilterTranslator.translateRows(java.util.List.of(row), false));
    }

    @Test
    public void uuidRefusesOperatorsItHasNoMethodFor() {
        for (WeaviateFilterOperator op : java.util.List.of(
            WeaviateFilterOperator.LIKE, WeaviateFilterOperator.CONTAINS_ALL)
        ) {
            WeaviateFilterRow row = new WeaviateFilterRow(
                WeaviateFilterTranslator.UUID_COLUMN, op, "abc", DBPDataKind.STRING);
            Assertions.assertThrows(
                WeaviateUnsupportedFilterException.class,
                () -> WeaviateFilterTranslator.translateRows(java.util.List.of(row), false),
                () -> "uuid has no " + op + " method and must say so");
        }
    }

    @Test
    public void uuidAcceptsContainsNone() {
        WeaviateFilterRow row = new WeaviateFilterRow(
            WeaviateFilterTranslator.UUID_COLUMN, WeaviateFilterOperator.CONTAINS_NONE,
            "a, b", DBPDataKind.STRING);
        Assertions.assertNotNull(
            WeaviateFilterTranslator.translateRows(java.util.List.of(row), false));
    }

    /**
     * hasCondition() is true as soon as an operator is set, even with no value -- which is the
     * state a column filter is left in after being cleared. That is "no condition", not a broken
     * filter, so it must be skipped. Treating it as an error fails the whole read and empties the
     * grid, which looks exactly like the filter matching nothing.
     */
    @Test
    public void gridConstraintWithoutAValueIsSkippedNotRejected() {
        for (DBCLogicalOperator op : java.util.List.of(
            DBCLogicalOperator.GREATER, DBCLogicalOperator.LESS, DBCLogicalOperator.LIKE,
            DBCLogicalOperator.IN, DBCLogicalOperator.BETWEEN)
        ) {
            DBDAttributeConstraint c = new DBDAttributeConstraint("title", 0);
            c.setOperator(op);
            // no value set
            Assertions.assertDoesNotThrow(
                () -> WeaviateFilterTranslator.translate(new DBDDataFilter(java.util.List.of(c))),
                () -> op + " with no value must be skipped, not rejected");
            Assertions.assertNull(
                WeaviateFilterTranslator.translate(new DBDDataFilter(java.util.List.of(c))),
                () -> op + " with no value must contribute nothing");
        }
    }

    /**
     * The panel is the opposite case: its rows are only created deliberately, so an empty value
     * cell is a mistake worth reporting rather than a row to ignore.
     */
    @Test
    public void panelRowWithoutAValueIsStillRejected() {
        WeaviateFilterRow row = new WeaviateFilterRow(
            "title", WeaviateFilterOperator.GREATER, "", DBPDataKind.STRING);
        Assertions.assertThrows(
            WeaviateUnsupportedFilterException.class,
            () -> WeaviateFilterTranslator.translateRows(java.util.List.of(row), false));
    }
}
