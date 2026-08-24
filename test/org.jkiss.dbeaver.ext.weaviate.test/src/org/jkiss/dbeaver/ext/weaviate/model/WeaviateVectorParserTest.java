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

    @Test
    public void parsesMultiVectorMatrix() {
        float[][] m = WeaviateVectorParser.parseMulti("[[0.1, 0.2], [0.3, 0.4]]");
        Assertions.assertNotNull(m);
        Assertions.assertEquals(2, m.length);
        Assertions.assertArrayEquals(new float[]{0.1f, 0.2f}, m[0], 0.0001f);
        Assertions.assertArrayEquals(new float[]{0.3f, 0.4f}, m[1], 0.0001f);
    }

    /**
     * The outer brackets are decoration; the inner ones are what separates one vector from the
     * next, so a bare comma-separated run of rows has to parse the same way.
     */
    @Test
    public void outerBracketsAreOptionalOnAMatrix() {
        Assertions.assertArrayEquals(
            WeaviateVectorParser.parseMulti("[[1, 2], [3, 4]]"),
            WeaviateVectorParser.parseMulti("[1, 2], [3, 4]"));
    }

    /**
     * A single flat vector is one vector, not a one-row matrix: "[0.1, 0.2]" must keep both
     * components rather than being read as the matrix "[0.1, 0.2]" of two 1-component rows.
     */
    @Test
    public void singleRowKeepsItsComponents() {
        float[][] m = WeaviateVectorParser.parseMulti("[[0.1, 0.2]]");
        Assertions.assertEquals(1, m.length);
        Assertions.assertArrayEquals(new float[]{0.1f, 0.2f}, m[0], 0.0001f);
    }

    @Test
    public void rejectsRaggedMatrix() {
        NumberFormatException e = Assertions.assertThrows(NumberFormatException.class,
            () -> WeaviateVectorParser.parseMulti("[[1, 2], [3]]"));
        Assertions.assertTrue(e.getMessage().contains("Vector 2"),
            "the message should name the offending row, was: " + e.getMessage());
    }

    @Test
    public void rejectsMatrixRowWithoutBrackets() {
        Assertions.assertThrows(NumberFormatException.class,
            () -> WeaviateVectorParser.parseMulti("[1, 2, [3, 4]]"));
    }

    @Test
    public void rejectsUnclosedMatrixRow() {
        Assertions.assertThrows(NumberFormatException.class,
            () -> WeaviateVectorParser.parseMulti("[[1, 2], [3, 4"));
    }

    @Test
    public void emptyMatrixInputIsNull() {
        Assertions.assertNull(WeaviateVectorParser.parseMulti(null));
        Assertions.assertNull(WeaviateVectorParser.parseMulti("   "));
    }

    /**
     * A matrix shown in the result grid has to be pastable straight back into a Near Vector
     * query, which only holds while format and parse agree on the shape.
     */
    @Test
    public void formatMultiRoundTrips() {
        float[][] original = {{0.1f, 0.2f}, {0.3f, 0.4f}};
        Assertions.assertArrayEquals(original,
            WeaviateVectorParser.parseMulti(WeaviateVectorParser.formatMulti(original)));
    }

    @Test
    public void formatMultiOfEmptyIsBlank() {
        Assertions.assertEquals("", WeaviateVectorParser.formatMulti(null));
        Assertions.assertEquals("", WeaviateVectorParser.formatMulti(new float[0][]));
    }

    @Test
    public void toleratesSpacedOuterBrackets() {
        float[][] m = WeaviateVectorParser.parseMulti("[ [1, 2], [3, 4] ]");
        Assertions.assertEquals(2, m.length);
        Assertions.assertArrayEquals(new float[]{3f, 4f}, m[1], 0.0001f);
    }

    /**
     * A single bracketed run reads as a matrix of one vector, which is what a one-token ColBERT
     * query is. Telling that apart from a flat vector is not the parser's job -- the panel knows
     * the target's index and picks the parser accordingly.
     */
    @Test
    public void singleBracketedRunIsAOneVectorMatrix() {
        float[][] m = WeaviateVectorParser.parseMulti("[0.1, 0.2]");
        Assertions.assertEquals(1, m.length);
        Assertions.assertArrayEquals(new float[]{0.1f, 0.2f}, m[0], 0.0001f);
    }
}
