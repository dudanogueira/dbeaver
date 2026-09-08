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

import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * The write side of the grid, where a mistake is expensive.
 * <p>
 * With auto-schema on -- the default -- a write can change the schema, and property types cannot
 * be changed afterwards. Measured against 1.39: an insert carrying an undeclared
 * <code>surprise: 42</code> left behind a permanent <code>number</code> property. So these tests
 * are less about conversion than about refusing to guess.
 */
public class WeaviateValueCoercionTest extends DBeaverUnitTest {

    private static Object wire(Object value, String type) throws DBCException {
        return WeaviateValueCoercion.toWireValue(value, type, "col");
    }

    @Test
    public void scalarsGoOutAsTheDeclaredType() throws DBCException {
        // Typed into a grid, everything arrives as text. What leaves has to be a JSON number,
        // boolean or string, because Weaviate refuses the mismatch: "not a string, but json.Number".
        Assertions.assertEquals("hello", wire("hello", "text"));
        Assertions.assertEquals(42L, wire("42", "int"));
        Assertions.assertEquals(1.5, wire("1.5", "number"));
        Assertions.assertEquals(true, wire("true", "boolean"));
        Assertions.assertEquals(false, wire("FALSE", "boolean"));
    }

    @Test
    public void aNumberTypedIntoAnIntIsRefusedHereRatherThanByTheServer() {
        DBCException e = Assertions.assertThrows(DBCException.class, () -> wire("1.5", "int"));
        Assertions.assertTrue(e.getMessage().contains("col"), e.getMessage());
        Assertions.assertThrows(DBCException.class, () -> wire("abc", "number"));
        // Not 1/0 and not yes/no: a boolean column that quietly takes "1" is one someone will
        // eventually put "2" into.
        Assertions.assertThrows(DBCException.class, () -> wire("1", "boolean"));
        Assertions.assertThrows(DBCException.class, () -> wire("yes", "boolean"));
    }

    @Test
    public void datesLeaveAsRfc3339AndAssumeUtcRatherThanTheLaptop() throws DBCException {
        Assertions.assertEquals("2026-09-08T14:30:00Z", wire("2026-09-08T14:30:00Z", "date"));
        // No offset typed: read as UTC. Guessing the machine's zone would make the same keystrokes
        // mean different instants on two laptops.
        Assertions.assertEquals("2026-09-08T14:30:00Z", wire("2026-09-08T14:30:00", "date"));
        Assertions.assertEquals("2026-09-08T00:00:00Z", wire("2026-09-08", "date"));
        Assertions.assertThrows(DBCException.class, () -> wire("last tuesday", "date"));
    }

    @Test
    public void anEmptyCellIsAbsentExceptForText() throws DBCException {
        // Clearing a number means "no value", and there is no empty number to send. Clearing a
        // text cell means the empty string, which Weaviate stores and reads back.
        Assertions.assertNull(wire("", "int"));
        Assertions.assertNull(wire("   ", "date"));
        Assertions.assertEquals("", wire("", "text"));
        Assertions.assertNull(wire(null, "text"));
    }

    @Test
    public void objectsAndGeoAndPhoneBecomeObjects() throws DBCException {
        // Weaviate models all three as objects, not strings.
        Assertions.assertEquals(Map.of("input", "+55 21 1234-5678"),
            wire("+55 21 1234-5678", "phoneNumber"));
        Assertions.assertEquals(Map.of("latitude", -22.9, "longitude", -43.2),
            wire("-22.9, -43.2", "geoCoordinates"));
        Assertions.assertEquals(Map.of("a", 1L),
            wire("{\"a\": 1}", "object"));
        Assertions.assertThrows(DBCException.class, () -> wire("-22.9", "geoCoordinates"));
    }

    @Test
    public void arraysAreTypedThroughTheirElement() throws DBCException {
        Assertions.assertEquals(List.of("a", "b"), wire("[\"a\", \"b\"]", "text[]"));
        Assertions.assertEquals(List.of(1L, 2L), wire("[1, 2]", "int[]"));
        Assertions.assertEquals(List.of(1L, 2L), wire(List.of("1", "2"), "int[]"));
        // The element type is enforced, so a text array of numbers fails here with the column
        // named rather than at the server with a JSON type named.
        Assertions.assertThrows(DBCException.class, () -> wire("[\"a\"]", "int[]"));
        Assertions.assertThrows(DBCException.class, () -> wire("not json", "text[]"));
        Assertions.assertThrows(DBCException.class, () -> wire("{\"a\":1}", "text[]"));
    }

    @Test
    public void aUuidIsValidatedBeforeItIsSent() throws DBCException {
        Assertions.assertEquals("4cfd6c11-a784-420f-8822-adea8ff690f7",
            wire("4cfd6c11-a784-420f-8822-adea8ff690f7", "uuid"));
        Assertions.assertThrows(DBCException.class, () -> wire("not-a-uuid", "uuid"));
    }

    @Test
    public void aTypeThisBuildDoesNotKnowIsPassedThroughRatherThanRefused() throws DBCException {
        // A property type added by a newer server. Refusing it would block a write the server
        // would have accepted; the server is the authority on its own vocabulary.
        Assertions.assertEquals("whatever", wire("whatever", "somethingNew"));
    }

    @Test
    public void jsonNumbersDoNotBecomeGsonTypes() throws DBCException {
        // Whatever comes out of the parser has to be a plain Java value: a Gson primitive reaching
        // the request serializes as an object, not as the number it stands for.
        Object parsed = wire("{\"n\": 3, \"f\": 1.5, \"b\": true, \"s\": \"x\"}", "object");
        Map<?, ?> map = (Map<?, ?>) parsed;
        Assertions.assertEquals(3L, map.get("n"));
        Assertions.assertEquals(1.5, map.get("f"));
        Assertions.assertEquals(true, map.get("b"));
        Assertions.assertEquals("x", map.get("s"));
    }
}
