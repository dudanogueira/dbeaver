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

import java.util.ArrayList;
import java.util.List;

public class WeaviateTokenPreviewTest extends DBeaverUnitTest {

    @Test
    public void carriesBothTokenLists() {
        WeaviateTokenPreview preview =
            new WeaviateTokenPreview("WORD", List.of("red", "maple"), List.of("red", "maple"));
        Assertions.assertEquals("WORD", preview.getTokenization());
        Assertions.assertEquals(List.of("red", "maple"), preview.getIndexed());
        Assertions.assertEquals(List.of("red", "maple"), preview.getQuery());
        Assertions.assertFalse(preview.indexedDiffersFromQuery());
    }

    @Test
    public void spotsWhenTheTwoListsDiverge() {
        WeaviateTokenPreview preview =
            new WeaviateTokenPreview(null, List.of("a", "b"), List.of("a"));
        Assertions.assertTrue(preview.indexedDiffersFromQuery());
    }

    /**
     * Null on the per-property path, since that response does not echo the tokenizer back. The
     * preview falls back to the property's own setting for the label.
     */
    @Test
    public void tokenizationMayBeAbsent() {
        Assertions.assertNull(new WeaviateTokenPreview(null, List.of(), List.of()).getTokenization());
    }

    /** Empty lists rather than null, so the dialog never has to null-check before iterating. */
    @Test
    public void nullListsBecomeEmpty() {
        WeaviateTokenPreview preview = new WeaviateTokenPreview("WORD", null, null);
        Assertions.assertNotNull(preview.getIndexed());
        Assertions.assertNotNull(preview.getQuery());
        Assertions.assertTrue(preview.getIndexed().isEmpty());
        Assertions.assertFalse(preview.indexedDiffersFromQuery(), "two empties are not a divergence");
    }

    @Test
    public void listsAreCopiedNotAliased() {
        List<String> mutable = new ArrayList<>(List.of("red"));
        WeaviateTokenPreview preview = new WeaviateTokenPreview("WORD", mutable, mutable);
        mutable.add("maple");

        Assertions.assertEquals(List.of("red"), preview.getIndexed(),
            "the preview kept a live reference to the caller's list");
        Assertions.assertThrows(UnsupportedOperationException.class,
            () -> preview.getIndexed().add("leaf"));
    }
}
