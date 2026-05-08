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
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.Assert;
import org.junit.Test;

public class WeaviatePropertyTest extends DBeaverUnitTest {

    @Test
    public void textMapsToString() {
        Assert.assertEquals(DBPDataKind.STRING, WeaviateProperty.mapDataKind(DataType.TEXT));
    }

    @Test
    public void textArrayMapsToString() {
        Assert.assertEquals(DBPDataKind.STRING, WeaviateProperty.mapDataKind(DataType.TEXT_ARRAY));
    }

    @Test
    public void uuidMapsToString() {
        Assert.assertEquals(DBPDataKind.STRING, WeaviateProperty.mapDataKind(DataType.UUID));
    }

    @Test
    public void uuidArrayMapsToString() {
        Assert.assertEquals(DBPDataKind.STRING, WeaviateProperty.mapDataKind(DataType.UUID_ARRAY));
    }

    @Test
    public void phoneNumberMapsToString() {
        Assert.assertEquals(DBPDataKind.STRING, WeaviateProperty.mapDataKind(DataType.PHONE_NUMBER));
    }

    @Test
    public void intMapsToNumeric() {
        Assert.assertEquals(DBPDataKind.NUMERIC, WeaviateProperty.mapDataKind(DataType.INT));
    }

    @Test
    public void intArrayMapsToNumeric() {
        Assert.assertEquals(DBPDataKind.NUMERIC, WeaviateProperty.mapDataKind(DataType.INT_ARRAY));
    }

    @Test
    public void numberMapsToNumeric() {
        Assert.assertEquals(DBPDataKind.NUMERIC, WeaviateProperty.mapDataKind(DataType.NUMBER));
    }

    @Test
    public void numberArrayMapsToNumeric() {
        Assert.assertEquals(DBPDataKind.NUMERIC, WeaviateProperty.mapDataKind(DataType.NUMBER_ARRAY));
    }

    @Test
    public void boolMapsToBoolean() {
        Assert.assertEquals(DBPDataKind.BOOLEAN, WeaviateProperty.mapDataKind(DataType.BOOL));
    }

    @Test
    public void boolArrayMapsToBoolean() {
        Assert.assertEquals(DBPDataKind.BOOLEAN, WeaviateProperty.mapDataKind(DataType.BOOL_ARRAY));
    }

    @Test
    public void dateMapsToDatetime() {
        Assert.assertEquals(DBPDataKind.DATETIME, WeaviateProperty.mapDataKind(DataType.DATE));
    }

    @Test
    public void dateArrayMapsToDatetime() {
        Assert.assertEquals(DBPDataKind.DATETIME, WeaviateProperty.mapDataKind(DataType.DATE_ARRAY));
    }

    @Test
    public void blobMapsToBinary() {
        Assert.assertEquals(DBPDataKind.BINARY, WeaviateProperty.mapDataKind(DataType.BLOB));
    }

    @Test
    public void objectMapsToObject() {
        Assert.assertEquals(DBPDataKind.OBJECT, WeaviateProperty.mapDataKind(DataType.OBJECT));
    }

    @Test
    public void objectArrayMapsToObject() {
        Assert.assertEquals(DBPDataKind.OBJECT, WeaviateProperty.mapDataKind(DataType.OBJECT_ARRAY));
    }

    @Test
    public void geoCoordinatesMapsToObject() {
        Assert.assertEquals(DBPDataKind.OBJECT, WeaviateProperty.mapDataKind(DataType.GEO_COORDINATES));
    }

    @Test
    public void crossReferenceMapsToObject() {
        // cross-references to other collections are represented as the target class name
        Assert.assertEquals(DBPDataKind.OBJECT, WeaviateProperty.mapDataKind("Article"));
        Assert.assertEquals(DBPDataKind.OBJECT, WeaviateProperty.mapDataKind("SomeOtherCollection"));
    }

    @Test
    public void unknownTypeMapsToObject() {
        Assert.assertEquals(DBPDataKind.OBJECT, WeaviateProperty.mapDataKind("_unknown_future_type_"));
    }
}
