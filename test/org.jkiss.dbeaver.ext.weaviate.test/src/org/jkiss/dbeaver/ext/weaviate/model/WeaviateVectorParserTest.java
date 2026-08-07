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

import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class WeaviateVectorParserTest extends DBeaverUnitTest {

    @Test
    public void parsesPlainCommaSeparated() {
        float[] v = WeaviateVectorParser.parse("0.1, 0.2, 0.3");
        Assertions.assertNotNull(v);
        Assertions.assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, v, 0.0001f);
    }

    @Test
    public void parsesBracketedVector() {
        float[] v = WeaviateVectorParser.parse("[1.0, -2.5, 3]");
        Assertions.assertArrayEquals(new float[]{1.0f, -2.5f, 3.0f}, v, 0.0001f);
    }

    @Test
    public void parsesWhitespaceVariants() {
        float[] v = WeaviateVectorParser.parse("  [  1 ,2,3 ]  ");
        Assertions.assertArrayEquals(new float[]{1.0f, 2.0f, 3.0f}, v, 0.0001f);
    }

    @Test
    public void emptyInputReturnsNull() {
        Assertions.assertNull(WeaviateVectorParser.parse(null));
        Assertions.assertNull(WeaviateVectorParser.parse(""));
        Assertions.assertNull(WeaviateVectorParser.parse("   "));
        Assertions.assertNull(WeaviateVectorParser.parse("[]"));
    }

    @Test
    public void invalidNumberThrows() {
        Assertions.assertThrows(NumberFormatException.class, () -> WeaviateVectorParser.parse("1, abc, 3"));
    }

    @Test
    public void emptyComponentThrows() {
        Assertions.assertThrows(NumberFormatException.class, () -> WeaviateVectorParser.parse("1, , 3"));
    }

    @Test
    public void nonFiniteThrows() {
        Assertions.assertThrows(NumberFormatException.class, () -> WeaviateVectorParser.parse("1, NaN, 3"));
    }

    @Test
    public void formatRoundTrips() {
        float[] in = {0.1f, 0.2f, 0.3f};
        String formatted = WeaviateVectorParser.format(in);
        float[] out = WeaviateVectorParser.parse(formatted);
        Assertions.assertArrayEquals(in, out, 0.0001f);
    }

    @Test
    public void formatNullOrEmptyReturnsEmptyString() {
        Assertions.assertEquals("", WeaviateVectorParser.format(null));
        Assertions.assertEquals("", WeaviateVectorParser.format(new float[0]));
    }
}
