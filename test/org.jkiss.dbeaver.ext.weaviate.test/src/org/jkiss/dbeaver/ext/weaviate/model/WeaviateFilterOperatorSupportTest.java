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
        for (DBCLogicalOperator op : WeaviateFilterRow.SUPPORTED_OPERATORS) {
            WeaviateFilterRow row = new WeaviateFilterRow("title", op, "value", DBPDataKind.STRING);
            Assertions.assertDoesNotThrow(
                () -> WeaviateFilterTranslator.translateRows(List.of(row), false),
                () -> "operator offered by the filter builder but not translatable: " + op);
        }
    }

    @Test
    public void everyOfferedOperatorProducesAFilter() {
        for (DBCLogicalOperator op : WeaviateFilterRow.SUPPORTED_OPERATORS) {
            WeaviateFilterRow row = new WeaviateFilterRow("title", op, "value", DBPDataKind.STRING);
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
    public void betweenIsTranslatableEvenThoughTheBuilderOmitsIt() {
        // Documents the deliberate asymmetry: the builder has one value field so it cannot offer
        // BETWEEN, but the grid's column filter can and must still work.
        Assertions.assertFalse(WeaviateFilterRow.SUPPORTED_OPERATORS.contains(DBCLogicalOperator.BETWEEN));

        DBDAttributeConstraint constraint = new DBDAttributeConstraint("price", 0);
        constraint.setOperator(DBCLogicalOperator.BETWEEN);
        constraint.setValue(new Object[]{10, 20});

        Assertions.assertNotNull(WeaviateFilterTranslator.translate(new DBDDataFilter(List.of(constraint))));
    }
}
