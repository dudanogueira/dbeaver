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
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.exec.DBCLogicalOperator;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class WeaviateFilterRowTest extends DBeaverUnitTest {

    @Test
    public void numericValueIsCoercedToLong() {
        WeaviateFilterRow row = new WeaviateFilterRow("price", WeaviateFilterOperator.EQUALS, "42", DBPDataKind.NUMERIC);
        Object v = row.coercedValue();
        Assertions.assertTrue(v instanceof Long, () -> "expected Long, got " + (v == null ? "null" : v.getClass()));
        Assertions.assertEquals(42L, ((Long) v).longValue());
    }

    @Test
    public void numericFloatGoesToDouble() {
        WeaviateFilterRow row = new WeaviateFilterRow("price", WeaviateFilterOperator.EQUALS, "3.14", DBPDataKind.NUMERIC);
        Object v = row.coercedValue();
        Assertions.assertTrue(v instanceof Double);
        Assertions.assertEquals(3.14, (Double) v, 0.0001);
    }

    @Test
    public void booleanIsParsed() {
        WeaviateFilterRow row = new WeaviateFilterRow("active", WeaviateFilterOperator.EQUALS, "true", DBPDataKind.BOOLEAN);
        Assertions.assertEquals(Boolean.TRUE, row.coercedValue());
    }

    @Test
    public void stringPassesThrough() {
        WeaviateFilterRow row = new WeaviateFilterRow("name", WeaviateFilterOperator.EQUALS, "alice", DBPDataKind.STRING);
        Assertions.assertEquals("alice", row.coercedValue());
    }

    @Test
    public void inSplitsOnComma() {
        WeaviateFilterRow row = new WeaviateFilterRow("tag", WeaviateFilterOperator.CONTAINS_ANY, "a, b ,c", DBPDataKind.STRING);
        Object v = row.coercedValue();
        Assertions.assertTrue(v instanceof String[]);
        Assertions.assertArrayEquals(new String[]{"a", "b", "c"}, (String[]) v);
    }

    @Test
    public void isNullDoesNotTakeValue() {
        WeaviateFilterRow row = new WeaviateFilterRow("name", WeaviateFilterOperator.IS_NULL, "ignored", DBPDataKind.STRING);
        Assertions.assertFalse(row.takesValue());
        Assertions.assertNull(row.coercedValue());
    }

    @Test
    public void translateRowsBuildsAndFilter() {
        List<WeaviateFilterRow> rows = List.of(
            new WeaviateFilterRow("name", WeaviateFilterOperator.EQUALS, "alice", DBPDataKind.STRING),
            new WeaviateFilterRow("age", WeaviateFilterOperator.GREATER, "30", DBPDataKind.NUMERIC)
        );
        Filter f = WeaviateFilterTranslator.translateRows(rows, false);
        Assertions.assertNotNull(f);
        Assertions.assertFalse(f.isEmpty());
    }

    @Test
    public void translateRowsBuildsOrFilter() {
        List<WeaviateFilterRow> rows = List.of(
            new WeaviateFilterRow("name", WeaviateFilterOperator.EQUALS, "alice", DBPDataKind.STRING),
            new WeaviateFilterRow("name", WeaviateFilterOperator.EQUALS, "bob", DBPDataKind.STRING)
        );
        Filter f = WeaviateFilterTranslator.translateRows(rows, true);
        Assertions.assertNotNull(f);
    }

    @Test
    public void emptyRowsReturnNull() {
        Assertions.assertNull(WeaviateFilterTranslator.translateRows(null, false));
        Assertions.assertNull(WeaviateFilterTranslator.translateRows(List.of(), false));
    }

    @Test
    public void andCombinerHandlesNulls() {
        Assertions.assertNull(WeaviateFilterTranslator.and(null, null));

        Filter only = WeaviateFilterTranslator.translateRows(
            List.of(new WeaviateFilterRow("x", WeaviateFilterOperator.EQUALS, "1", DBPDataKind.STRING)),
            false
        );
        Assertions.assertSame(only, WeaviateFilterTranslator.and(only, null));
        Assertions.assertSame(only, WeaviateFilterTranslator.and(null, only));

        Filter other = WeaviateFilterTranslator.translateRows(
            List.of(new WeaviateFilterRow("y", WeaviateFilterOperator.EQUALS, "2", DBPDataKind.STRING)),
            false
        );
        Filter combined = WeaviateFilterTranslator.and(only, other);
        Assertions.assertNotNull(combined);
        Assertions.assertNotSame(only, combined);
        Assertions.assertNotSame(other, combined);
    }

    @Test
    public void specCarriesFilterRowsAndAnyFilter() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.FETCH)
            .filterRows(List.of(new WeaviateFilterRow("a", WeaviateFilterOperator.EQUALS, "x", DBPDataKind.STRING)))
            .anyFilter(true)
            .build();
        Assertions.assertEquals(1, spec.getFilterRows().size());
        Assertions.assertTrue(spec.isAnyFilter());
    }

    /**
     * The bug this class did not catch before: every list was coerced to String[], so a contains
     * filter on an int[] property sent text operands and matched nothing. The element type also
     * decides which typed client overload the translator can reach.
     */
    @Test
    public void numericListCoercesEveryElement() {
        WeaviateFilterRow row = new WeaviateFilterRow(
            "nums", WeaviateFilterOperator.CONTAINS_ANY, "1, 2, 3", DBPDataKind.NUMERIC);
        Object value = row.coercedValue();
        Assertions.assertInstanceOf(Long[].class, value, "must reach the Long overload");
        Assertions.assertArrayEquals(new Long[]{1L, 2L, 3L}, (Long[]) value);
    }

    @Test
    public void mixedNumericListWidensToDouble() {
        WeaviateFilterRow row = new WeaviateFilterRow(
            "nums", WeaviateFilterOperator.CONTAINS_ANY, "1, 2.5", DBPDataKind.NUMERIC);
        Object value = row.coercedValue();
        Assertions.assertInstanceOf(Double[].class, value, "one array type, so the list widens");
        Assertions.assertArrayEquals(new Double[]{1.0d, 2.5d}, (Double[]) value);
    }

    @Test
    public void booleanListCoercesEveryElement() {
        WeaviateFilterRow row = new WeaviateFilterRow(
            "flags", WeaviateFilterOperator.CONTAINS_ALL, "true, false", DBPDataKind.BOOLEAN);
        Assertions.assertArrayEquals(
            new Boolean[]{Boolean.TRUE, Boolean.FALSE}, (Boolean[]) row.coercedValue());
    }

    @Test
    public void textListStaysStrings() {
        WeaviateFilterRow row = new WeaviateFilterRow(
            "tags", WeaviateFilterOperator.CONTAINS_NONE, "x, y", DBPDataKind.STRING);
        Assertions.assertArrayEquals(new String[]{"x", "y"}, (String[]) row.coercedValue());
    }

    /**
     * Dates were passed through as text, which builds a text operand the client never matches
     * against a date property.
     */
    @Test
    public void dateIsCoercedToOffsetDateTime() {
        WeaviateFilterRow row = new WeaviateFilterRow(
            "when", WeaviateFilterOperator.GREATER, "2024-01-01T00:00:00Z", DBPDataKind.DATETIME);
        Assertions.assertEquals(
            java.time.OffsetDateTime.parse("2024-01-01T00:00:00Z"), row.coercedValue());
    }

    @Test
    public void unparseableDateIsReportedNotPassedThroughAsText() {
        WeaviateFilterRow row = new WeaviateFilterRow(
            "when", WeaviateFilterOperator.EQUALS, "last tuesday", DBPDataKind.DATETIME);
        Assertions.assertThrows(WeaviateUnsupportedFilterException.class, row::coercedValue);
    }

    /**
     * A non-numeric value on a number column used to fall back to text, which matches nothing --
     * indistinguishable, on screen, from a filter that simply found no rows.
     */
    @Test
    public void unparseableNumberIsReportedNotPassedThroughAsText() {
        WeaviateFilterRow row = new WeaviateFilterRow(
            "rank", WeaviateFilterOperator.EQUALS, "abc", DBPDataKind.NUMERIC);
        Assertions.assertThrows(WeaviateUnsupportedFilterException.class, row::coercedValue);
    }

    /**
     * A blank cell used to make the row translate to null: the panel showed an active filter that
     * was not applied, and the grid quietly showed more rows than asked for.
     */
    @Test
    public void blankRequiredValueIsReportedNotDropped() {
        WeaviateFilterRow row = new WeaviateFilterRow(
            "rank", WeaviateFilterOperator.GREATER, "", DBPDataKind.NUMERIC);
        Assertions.assertThrows(
            WeaviateUnsupportedFilterException.class,
            () -> WeaviateFilterTranslator.translateRows(java.util.List.of(row), false));
    }

    @Test
    public void betweenNeedsExactlyTwoValues() {
        for (String raw : new String[]{"1", "1,2,3"}) {
            WeaviateFilterRow row = new WeaviateFilterRow(
                "rank", WeaviateFilterOperator.BETWEEN, raw, DBPDataKind.NUMERIC);
            Assertions.assertThrows(
                WeaviateUnsupportedFilterException.class,
                () -> WeaviateFilterTranslator.translateRows(java.util.List.of(row), false),
                () -> "BETWEEN accepted " + raw);
        }
    }
}
