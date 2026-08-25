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

/**
 * The group-by request object. Its whole job is to refuse a request the server would answer
 * confusingly, so most of this is about what it rejects.
 */
public class WeaviateGroupBySpecTest extends DBeaverUnitTest {

    @Test
    public void carriesItsProperty() {
        WeaviateGroupBySpec spec = new WeaviateGroupBySpec("category", 5, 3, false);
        Assertions.assertEquals("category", spec.getProperty());
        Assertions.assertEquals(5, spec.getMaxGroups());
        Assertions.assertEquals(3, spec.getMaxObjectsPerGroup());
        Assertions.assertFalse(spec.isWithGroupStats());
    }

    @Test
    public void refusesABlankProperty() {
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> new WeaviateGroupBySpec("   ", 5, 3, false));
    }

    /**
     * Zero groups is not "no cap" -- the server answers it with an empty result, which reads as
     * a broken query. Refused up front so the message names the cause.
     */
    @Test
    public void refusesNonPositiveCaps() {
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> new WeaviateGroupBySpec("category", 0, 3, false));
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> new WeaviateGroupBySpec("category", 5, 0, false));
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> new WeaviateGroupBySpec("category", -1, 3, false));
    }

    @Test
    public void equalityCoversEveryField() {
        WeaviateGroupBySpec base = new WeaviateGroupBySpec("category", 5, 3, false);
        Assertions.assertEquals(base, new WeaviateGroupBySpec("category", 5, 3, false));
        Assertions.assertEquals(base.hashCode(), new WeaviateGroupBySpec("category", 5, 3, false).hashCode());
        Assertions.assertNotEquals(base, new WeaviateGroupBySpec("labels", 5, 3, false));
        Assertions.assertNotEquals(base, new WeaviateGroupBySpec("category", 6, 3, false));
        Assertions.assertNotEquals(base, new WeaviateGroupBySpec("category", 5, 4, false));
        Assertions.assertNotEquals(base, new WeaviateGroupBySpec("category", 5, 3, true));
    }

    /** It lands in the result tab's statement text, so it has to say something legible. */
    @Test
    public void describesItselfForTheQueryText() {
        Assertions.assertEquals("category/5x3",
            new WeaviateGroupBySpec("category", 5, 3, false).toString());
    }
}
