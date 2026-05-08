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
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class WeaviateFilterRowTest extends DBeaverUnitTest {

    @Test
    public void numericValueIsCoercedToLong() {
        WeaviateFilterRow row = new WeaviateFilterRow("price", DBCLogicalOperator.EQUALS, "42", DBPDataKind.NUMERIC);
        Object v = row.coercedValue();
        Assert.assertTrue("expected Long, got " + (v == null ? "null" : v.getClass()), v instanceof Long);
        Assert.assertEquals(42L, ((Long) v).longValue());
    }

    @Test
    public void numericFloatGoesToDouble() {
        WeaviateFilterRow row = new WeaviateFilterRow("price", DBCLogicalOperator.EQUALS, "3.14", DBPDataKind.NUMERIC);
        Object v = row.coercedValue();
        Assert.assertTrue(v instanceof Double);
        Assert.assertEquals(3.14, (Double) v, 0.0001);
    }

    @Test
    public void booleanIsParsed() {
        WeaviateFilterRow row = new WeaviateFilterRow("active", DBCLogicalOperator.EQUALS, "true", DBPDataKind.BOOLEAN);
        Assert.assertEquals(Boolean.TRUE, row.coercedValue());
    }

    @Test
    public void stringPassesThrough() {
        WeaviateFilterRow row = new WeaviateFilterRow("name", DBCLogicalOperator.EQUALS, "alice", DBPDataKind.STRING);
        Assert.assertEquals("alice", row.coercedValue());
    }

    @Test
    public void inSplitsOnComma() {
        WeaviateFilterRow row = new WeaviateFilterRow("tag", DBCLogicalOperator.IN, "a, b ,c", DBPDataKind.STRING);
        Object v = row.coercedValue();
        Assert.assertTrue(v instanceof String[]);
        Assert.assertArrayEquals(new String[]{"a", "b", "c"}, (String[]) v);
    }

    @Test
    public void isNullDoesNotTakeValue() {
        WeaviateFilterRow row = new WeaviateFilterRow("name", DBCLogicalOperator.IS_NULL, "ignored", DBPDataKind.STRING);
        Assert.assertFalse(row.takesValue());
        Assert.assertNull(row.coercedValue());
    }

    @Test
    public void translateRowsBuildsAndFilter() {
        List<WeaviateFilterRow> rows = List.of(
            new WeaviateFilterRow("name", DBCLogicalOperator.EQUALS, "alice", DBPDataKind.STRING),
            new WeaviateFilterRow("age", DBCLogicalOperator.GREATER, "30", DBPDataKind.NUMERIC)
        );
        Filter f = WeaviateFilterTranslator.translateRows(rows, false);
        Assert.assertNotNull(f);
        Assert.assertFalse(f.isEmpty());
    }

    @Test
    public void translateRowsBuildsOrFilter() {
        List<WeaviateFilterRow> rows = List.of(
            new WeaviateFilterRow("name", DBCLogicalOperator.EQUALS, "alice", DBPDataKind.STRING),
            new WeaviateFilterRow("name", DBCLogicalOperator.EQUALS, "bob", DBPDataKind.STRING)
        );
        Filter f = WeaviateFilterTranslator.translateRows(rows, true);
        Assert.assertNotNull(f);
    }

    @Test
    public void emptyRowsReturnNull() {
        Assert.assertNull(WeaviateFilterTranslator.translateRows(null, false));
        Assert.assertNull(WeaviateFilterTranslator.translateRows(List.of(), false));
    }

    @Test
    public void andCombinerHandlesNulls() {
        Assert.assertNull(WeaviateFilterTranslator.and(null, null));

        Filter only = WeaviateFilterTranslator.translateRows(
            List.of(new WeaviateFilterRow("x", DBCLogicalOperator.EQUALS, "1", DBPDataKind.STRING)),
            false
        );
        Assert.assertSame(only, WeaviateFilterTranslator.and(only, null));
        Assert.assertSame(only, WeaviateFilterTranslator.and(null, only));

        Filter other = WeaviateFilterTranslator.translateRows(
            List.of(new WeaviateFilterRow("y", DBCLogicalOperator.EQUALS, "2", DBPDataKind.STRING)),
            false
        );
        Filter combined = WeaviateFilterTranslator.and(only, other);
        Assert.assertNotNull(combined);
        Assert.assertNotSame(only, combined);
        Assert.assertNotSame(other, combined);
    }

    @Test
    public void specCarriesFilterRowsAndAnyFilter() {
        WeaviateQuerySpec spec = WeaviateQuerySpec.builder(WeaviateQueryMode.FETCH)
            .filterRows(List.of(new WeaviateFilterRow("a", DBCLogicalOperator.EQUALS, "x", DBPDataKind.STRING)))
            .anyFilter(true)
            .build();
        Assert.assertEquals(1, spec.getFilterRows().size());
        Assert.assertTrue(spec.isAnyFilter());
    }
}
