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

import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.data.DBDAttributeConstraint;
import org.jkiss.dbeaver.model.data.DBDDataFilter;
import org.jkiss.dbeaver.model.exec.DBCLogicalOperator;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Guards the contract between the filter builder's operator list and what the translator can
 * actually express. Offering an operator the translator rejects would only fail at query time.
 */
public class WeaviateFilterOperatorSupportTest extends DBeaverUnitTest {

    @Test
    public void everyOfferedOperatorIsTranslatable() {
        for (WeaviateFilterOperator op : WeaviateFilterRow.SUPPORTED_OPERATORS) {
            // BETWEEN needs two values; everything else is happy with one.
            String value = op == WeaviateFilterOperator.BETWEEN ? "a,b" : "value";
            WeaviateFilterRow row = new WeaviateFilterRow("title", op, value, DBPDataKind.STRING);
            Assertions.assertDoesNotThrow(
                () -> WeaviateFilterTranslator.translateRows(List.of(row), false),
                () -> "operator offered by the filter builder but not translatable: " + op);
        }
    }

    @Test
    public void everyOfferedOperatorProducesAFilter() {
        for (WeaviateFilterOperator op : WeaviateFilterRow.SUPPORTED_OPERATORS) {
            String value = op == WeaviateFilterOperator.BETWEEN ? "a,b" : "value";
            WeaviateFilterRow row = new WeaviateFilterRow("title", op, value, DBPDataKind.STRING);
            Assertions.assertNotNull(
                WeaviateFilterTranslator.translateRows(List.of(row), false),
                () -> "operator silently produced no filter: " + op);
        }
    }

    @Test
    public void unsupportedOperatorIsReportedNotDropped() {
        // REGEX has no Weaviate equivalent. Previously this returned null and the filter
        // vanished, so the grid showed unfiltered rows while the filter bar looked active.
        DBDAttributeConstraint constraint = new DBDAttributeConstraint("title", 0);
        constraint.setOperator(DBCLogicalOperator.REGEX);
        constraint.setValue("^a.*");

        Assertions.assertThrows(
            WeaviateUnsupportedFilterException.class,
            () -> WeaviateFilterTranslator.translate(new DBDDataFilter(List.of(constraint))));
    }

    @Test
    public void nullTestOnUuidIsReportedNotDropped() {
        DBDAttributeConstraint constraint =
            new DBDAttributeConstraint(WeaviateFilterTranslator.UUID_COLUMN, 0);
        constraint.setOperator(DBCLogicalOperator.IS_NULL);

        Assertions.assertThrows(
            WeaviateUnsupportedFilterException.class,
            () -> WeaviateFilterTranslator.translate(new DBDDataFilter(List.of(constraint))));
    }

    @Test
    public void betweenIsNowOfferedByTheBuilderToo() {
        // It was omitted while the builder had one value field and no notion of arity. The
        // operator enum has both, so the asymmetry with the grid path is gone.
        Assertions.assertTrue(
            WeaviateFilterRow.SUPPORTED_OPERATORS.contains(WeaviateFilterOperator.BETWEEN));

        DBDAttributeConstraint constraint = new DBDAttributeConstraint("price", 0);
        constraint.setOperator(DBCLogicalOperator.BETWEEN);
        constraint.setValue(new Object[]{10, 20});

        Assertions.assertNotNull(WeaviateFilterTranslator.translate(new DBDDataFilter(List.of(constraint))));
    }

    /**
     * Every DBeaver operator either maps to something Weaviate does, or is refused. Silence is
     * the one outcome that must never happen, since it widens the result set.
     */
    @Test
    public void everyDBeaverOperatorIsMappedOrRefused() {
        for (DBCLogicalOperator op : DBCLogicalOperator.values()) {
            WeaviateFilterOperator mapped = WeaviateFilterOperator.fromDataFilterOperator(op);
            if (mapped != null) {
                continue;
            }
            DBDAttributeConstraint constraint = new DBDAttributeConstraint("title", 0);
            constraint.setOperator(op);
            constraint.setValue("value");
            Assertions.assertThrows(
                WeaviateUnsupportedFilterException.class,
                () -> WeaviateFilterTranslator.translate(new DBDDataFilter(List.of(constraint))),
                () -> op + " maps to nothing and must be refused, not dropped");
        }
    }

    /**
     * ILIKE used to be offered and translated to LIKE. Weaviate's Like is case-sensitive, so the
     * operator quietly ignored the one thing it was chosen for.
     */
    @Test
    public void ilikeIsRefusedRatherThanAliasedToLike() {
        Assertions.assertNull(
            WeaviateFilterOperator.fromDataFilterOperator(DBCLogicalOperator.ILIKE));

        DBDAttributeConstraint constraint = new DBDAttributeConstraint("title", 0);
        constraint.setOperator(DBCLogicalOperator.ILIKE);
        constraint.setValue("abc");
        Assertions.assertThrows(
            WeaviateUnsupportedFilterException.class,
            () -> WeaviateFilterTranslator.translate(new DBDDataFilter(List.of(constraint))));
    }

    @Test
    public void arityIsSelfConsistent() {
        for (WeaviateFilterOperator op : WeaviateFilterOperator.values()) {
            if (!op.takesValue()) {
                Assertions.assertEquals(0, op.valueCount(), op + " takes no value");
                Assertions.assertFalse(op.takesList(), op + " cannot be both valueless and a list");
            } else {
                Assertions.assertNotEquals(0, op.valueCount(), op + " takes a value");
            }
            Assertions.assertFalse(op.getLabel().isBlank(), op + " has no label");
        }
    }

}
