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

import io.weaviate.client6.v1.api.collections.DataType;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.DBPToolTipObject;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class WeaviatePropertyTest extends DBeaverUnitTest {

    @Test
    public void textMapsToString() {
        Assertions.assertEquals(DBPDataKind.STRING, WeaviateProperty.mapDataKind(DataType.TEXT));
    }

    @Test
    public void textArrayMapsToString() {
        Assertions.assertEquals(DBPDataKind.STRING, WeaviateProperty.mapDataKind(DataType.TEXT_ARRAY));
    }

    @Test
    public void uuidMapsToString() {
        Assertions.assertEquals(DBPDataKind.STRING, WeaviateProperty.mapDataKind(DataType.UUID));
    }

    @Test
    public void uuidArrayMapsToString() {
        Assertions.assertEquals(DBPDataKind.STRING, WeaviateProperty.mapDataKind(DataType.UUID_ARRAY));
    }

    @Test
    public void phoneNumberMapsToString() {
        Assertions.assertEquals(DBPDataKind.STRING, WeaviateProperty.mapDataKind(DataType.PHONE_NUMBER));
    }

    @Test
    public void intMapsToNumeric() {
        Assertions.assertEquals(DBPDataKind.NUMERIC, WeaviateProperty.mapDataKind(DataType.INT));
    }

    @Test
    public void intArrayMapsToNumeric() {
        Assertions.assertEquals(DBPDataKind.NUMERIC, WeaviateProperty.mapDataKind(DataType.INT_ARRAY));
    }

    @Test
    public void numberMapsToNumeric() {
        Assertions.assertEquals(DBPDataKind.NUMERIC, WeaviateProperty.mapDataKind(DataType.NUMBER));
    }

    @Test
    public void numberArrayMapsToNumeric() {
        Assertions.assertEquals(DBPDataKind.NUMERIC, WeaviateProperty.mapDataKind(DataType.NUMBER_ARRAY));
    }

    @Test
    public void boolMapsToBoolean() {
        Assertions.assertEquals(DBPDataKind.BOOLEAN, WeaviateProperty.mapDataKind(DataType.BOOL));
    }

    @Test
    public void boolArrayMapsToBoolean() {
        Assertions.assertEquals(DBPDataKind.BOOLEAN, WeaviateProperty.mapDataKind(DataType.BOOL_ARRAY));
    }

    @Test
    public void dateMapsToDatetime() {
        Assertions.assertEquals(DBPDataKind.DATETIME, WeaviateProperty.mapDataKind(DataType.DATE));
    }

    @Test
    public void dateArrayMapsToDatetime() {
        Assertions.assertEquals(DBPDataKind.DATETIME, WeaviateProperty.mapDataKind(DataType.DATE_ARRAY));
    }

    @Test
    public void blobMapsToBinary() {
        Assertions.assertEquals(DBPDataKind.BINARY, WeaviateProperty.mapDataKind(DataType.BLOB));
    }

    @Test
    public void objectMapsToObject() {
        Assertions.assertEquals(DBPDataKind.OBJECT, WeaviateProperty.mapDataKind(DataType.OBJECT));
    }

    @Test
    public void objectArrayMapsToObject() {
        Assertions.assertEquals(DBPDataKind.OBJECT, WeaviateProperty.mapDataKind(DataType.OBJECT_ARRAY));
    }

    @Test
    public void geoCoordinatesMapsToObject() {
        Assertions.assertEquals(DBPDataKind.OBJECT, WeaviateProperty.mapDataKind(DataType.GEO_COORDINATES));
    }

    @Test
    public void crossReferenceMapsToObject() {
        // cross-references to other collections are represented as the target class name
        Assertions.assertEquals(DBPDataKind.OBJECT, WeaviateProperty.mapDataKind("Article"));
        Assertions.assertEquals(DBPDataKind.OBJECT, WeaviateProperty.mapDataKind("SomeOtherCollection"));
    }

    @Test
    public void unknownTypeMapsToObject() {
        Assertions.assertEquals(DBPDataKind.OBJECT, WeaviateProperty.mapDataKind("_unknown_future_type_"));
    }

    /**
     * The navigator appends this in parentheses after the name, so a text property reads
     * {@code title (text, word)} rather than a bare {@code title}.
     * <p>
     * Every JDBC-backed column gets this free from {@code AbstractAttribute}; this class
     * implements {@code DBSEntityAttribute} directly and so had to opt in. Asserted here because
     * the symptom of losing it is subtle -- the tree still works, it just says less than every
     * other driver does.
     */
    @Test
    public void propertyAndUuidBothAdvertiseATooltip() {
        Assertions.assertTrue(DBPToolTipObject.class.isAssignableFrom(WeaviateProperty.class),
            "WeaviateProperty must implement DBPToolTipObject or the navigator shows a bare name");
        Assertions.assertTrue(DBPToolTipObject.class.isAssignableFrom(WeaviateUuidAttribute.class),
            "the synthetic uuid column should read like the real ones");
    }
}
