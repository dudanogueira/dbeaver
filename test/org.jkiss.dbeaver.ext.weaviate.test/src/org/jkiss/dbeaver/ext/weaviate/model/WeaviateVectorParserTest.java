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
import org.junit.Assert;
import org.junit.Test;

public class WeaviateVectorParserTest extends DBeaverUnitTest {

    @Test
    public void parsesPlainCommaSeparated() {
        float[] v = WeaviateVectorParser.parse("0.1, 0.2, 0.3");
        Assert.assertNotNull(v);
        Assert.assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, v, 0.0001f);
    }

    @Test
    public void parsesBracketedVector() {
        float[] v = WeaviateVectorParser.parse("[1.0, -2.5, 3]");
        Assert.assertArrayEquals(new float[]{1.0f, -2.5f, 3.0f}, v, 0.0001f);
    }

    @Test
    public void parsesWhitespaceVariants() {
        float[] v = WeaviateVectorParser.parse("  [  1 ,2,3 ]  ");
        Assert.assertArrayEquals(new float[]{1.0f, 2.0f, 3.0f}, v, 0.0001f);
    }

    @Test
    public void emptyInputReturnsNull() {
        Assert.assertNull(WeaviateVectorParser.parse(null));
        Assert.assertNull(WeaviateVectorParser.parse(""));
        Assert.assertNull(WeaviateVectorParser.parse("   "));
        Assert.assertNull(WeaviateVectorParser.parse("[]"));
    }

    @Test(expected = NumberFormatException.class)
    public void invalidNumberThrows() {
        WeaviateVectorParser.parse("1, abc, 3");
    }

    @Test(expected = NumberFormatException.class)
    public void emptyComponentThrows() {
        WeaviateVectorParser.parse("1, , 3");
    }

    @Test(expected = NumberFormatException.class)
    public void nonFiniteThrows() {
        WeaviateVectorParser.parse("1, NaN, 3");
    }

    @Test
    public void formatRoundTrips() {
        float[] in = {0.1f, 0.2f, 0.3f};
        String formatted = WeaviateVectorParser.format(in);
        float[] out = WeaviateVectorParser.parse(formatted);
        Assert.assertArrayEquals(in, out, 0.0001f);
    }

    @Test
    public void formatNullOrEmptyReturnsEmptyString() {
        Assert.assertEquals("", WeaviateVectorParser.format(null));
        Assert.assertEquals("", WeaviateVectorParser.format(new float[0]));
    }
}
