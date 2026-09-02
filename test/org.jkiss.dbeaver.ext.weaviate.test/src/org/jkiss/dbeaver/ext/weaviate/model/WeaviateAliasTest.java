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

import java.util.List;

/**
 * The two alias rules that can be checked without a server: which collection an alias belongs to,
 * and when its target has gone.
 * <p>
 * Everything else about aliases needs a real one -- the client's argument order, its null list, its
 * filter parameter -- and lives in {@code WeaviateAliasLiveTest}.
 */
public class WeaviateAliasTest extends DBeaverUnitTest {

    @Test
    public void pointsAtIgnoresCase() {
        // Weaviate capitalises a collection name on the way in, so an alias created against
        // "products" comes back targeting "Products" while the collection list says "Products".
        // Matching exactly would file that alias under no collection at all.
        Assertions.assertTrue(WeaviateAlias.pointsAt("Products", "Products"));
        Assertions.assertTrue(WeaviateAlias.pointsAt("products", "Products"));
        Assertions.assertTrue(WeaviateAlias.pointsAt("PRODUCTS", "products"));
    }

    @Test
    public void pointsAtRejectsADifferentCollection() {
        Assertions.assertFalse(WeaviateAlias.pointsAt("Products", "ProductsV2"));
        Assertions.assertFalse(WeaviateAlias.pointsAt("Products", ""));
    }

    @Test
    public void targetPresentIsNotMissing() {
        Assertions.assertFalse(
            WeaviateAlias.targetMissing(List.of("Orders", "Products"), "Products"));
        Assertions.assertFalse(
            WeaviateAlias.targetMissing(List.of("Orders", "Products"), "products"));
    }

    @Test
    public void targetAbsentIsMissing() {
        Assertions.assertTrue(
            WeaviateAlias.targetMissing(List.of("Orders", "Products"), "ProductsV3"));
    }

    @Test
    public void anEmptyCollectionListStillReportsMissing() {
        // An empty list is an answer -- the server has no collections, so nothing can be targeted.
        // Only a null list means "not asked yet"; see the next test.
        Assertions.assertTrue(WeaviateAlias.targetMissing(List.of(), "Products"));
    }

    @Test
    public void anUnloadedCollectionListNeverReportsMissing() {
        // The navigator asks this while painting labels and must not fetch to answer, so a
        // connection whose collections have not been expanded knows nothing about targets. It
        // says so by not warning: a missing marker is a smaller failure than one invented from
        // ignorance, and the same direction the version gate guesses in.
        Assertions.assertFalse(WeaviateAlias.targetMissing(null, "Products"));
    }
}
