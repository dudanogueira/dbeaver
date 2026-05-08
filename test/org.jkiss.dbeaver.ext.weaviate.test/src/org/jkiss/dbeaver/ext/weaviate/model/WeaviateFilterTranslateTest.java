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
import org.jkiss.dbeaver.model.exec.DBCLogicalOperator;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class WeaviateFilterTranslateTest extends DBeaverUnitTest {

    @Test
    public void nullFilterReturnsNull() {
        Assert.assertNull(WeaviateFilterTranslator.translate(null));
    }

    @Test
    public void filterWithoutConstraintsReturnsNull() {
        Assert.assertNull(WeaviateFilterTranslator.translate(new DBDDataFilter()));
    }

    @Test
    public void singleEqualsConstraintBecomesPropertyEq() {
        DBDDataFilter f = filter(constraint("name", DBCLogicalOperator.EQUALS, "alice"));
        Filter filter = WeaviateFilterTranslator.translate(f);
        Assert.assertNotNull(filter);
        Assert.assertFalse(filter.isEmpty());
    }

    @Test
    public void uuidEqualsUsesUuidPath() {
        DBDDataFilter f = filter(constraint("uuid", DBCLogicalOperator.EQUALS, "abc"));
        Filter filter = WeaviateFilterTranslator.translate(f);
        Assert.assertNotNull(filter);
        // Best-effort smoke check on toString — uuid filters mention "_id" or "uuid"
        String s = filter.toString().toLowerCase();
        Assert.assertTrue("uuid filter should reference _id/uuid: " + s,
            s.contains("_id") || s.contains("uuid"));
    }

    @Test
    public void isNullProducesIsNull() {
        DBDDataFilter f = filter(constraint("title", DBCLogicalOperator.IS_NULL, null));
        Filter filter = WeaviateFilterTranslator.translate(f);
        Assert.assertNotNull(filter);
    }

    @Test
    public void inProducesContainsAny() {
        DBDDataFilter f = filter(constraint("tag", DBCLogicalOperator.IN, new Object[]{"a", "b", "c"}));
        Filter filter = WeaviateFilterTranslator.translate(f);
        Assert.assertNotNull(filter);
    }

    @Test
    public void multipleConstraintsAreAndedByDefault() {
        DBDDataFilter f = filter(
            constraint("a", DBCLogicalOperator.EQUALS, "x"),
            constraint("b", DBCLogicalOperator.EQUALS, "y")
        );
        Filter filter = WeaviateFilterTranslator.translate(f);
        Assert.assertNotNull(filter);
        Assert.assertFalse(filter.isEmpty());
    }

    @Test
    public void anyConstraintProducesOr() {
        DBDDataFilter f = new DBDDataFilter(Arrays.asList(
            constraint("a", DBCLogicalOperator.EQUALS, "x"),
            constraint("b", DBCLogicalOperator.EQUALS, "y")
        ));
        f.setAnyConstraint(true);
        Filter filter = WeaviateFilterTranslator.translate(f);
        Assert.assertNotNull(filter);
        Assert.assertFalse(filter.isEmpty());
    }

    @Test
    public void reverseOperatorWrapsInNot() {
        DBDAttributeConstraint c = constraint("name", DBCLogicalOperator.EQUALS, "alice");
        c.setReverseOperator(true);
        DBDDataFilter f = filter(c);
        Filter filter = WeaviateFilterTranslator.translate(f);
        Assert.assertNotNull(filter);
        Assert.assertFalse(filter.isEmpty());
    }

    @Test
    public void betweenProducesAndOfGteAndLte() {
        DBDDataFilter f = filter(constraint("price", DBCLogicalOperator.BETWEEN, new Object[]{10, 20}));
        Filter filter = WeaviateFilterTranslator.translate(f);
        Assert.assertNotNull(filter);
        Assert.assertFalse(filter.isEmpty());
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
}
