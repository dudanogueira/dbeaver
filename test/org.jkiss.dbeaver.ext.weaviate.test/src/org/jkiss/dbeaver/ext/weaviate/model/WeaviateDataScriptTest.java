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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What "Generate SQL" shows for pending row changes.
 * <p>
 * These snippets are read to decide whether to save, so the thing worth testing is that they say
 * what will actually be sent: the real ids, the real values, the tenant when there is one. A
 * script that looks plausible but omits the tenant would describe a call against a different
 * collection.
 */
public class WeaviateDataScriptTest extends DBeaverUnitTest {

    @Test
    public void deleteIsOneFilteredCallListingEveryId() {
        String script = WeaviateDataScript.renderDelete("FilterGroup", null,
            List.of("9a834a78-6aab-4fc0-a339-125825c4b706", "a13b6eb1-cd14-4561-8958-11a085c90c51"));
        Assertions.assertTrue(script.contains("client.collections.use(\"FilterGroup\")"), script);
        Assertions.assertTrue(script.contains(".data.deleteMany("), script);
        Assertions.assertTrue(script.contains("Filter.uuid().containsAny("), script);
        Assertions.assertTrue(script.contains("\"9a834a78-6aab-4fc0-a339-125825c4b706\""), script);
        Assertions.assertTrue(script.contains("\"a13b6eb1-cd14-4561-8958-11a085c90c51\""), script);
        // One call for the selection, not one per row: the count of calls is what makes deleting
        // two hundred rows a single request.
        Assertions.assertEquals(1, script.split("deleteMany", -1).length - 1, script);
    }

    @Test
    public void tenantIsPartOfTheHandle() {
        String script = WeaviateDataScript.renderDelete("Article", "acme", List.of("id-1"));
        Assertions.assertTrue(
            script.contains("client.collections.use(\"Article\", b -> b.tenant(\"acme\"))"), script);
    }

    @Test
    public void insertNamesTheIdItChose() {
        String script = WeaviateDataScript.renderInsert("Article", null,
            List.of("8aeaa5fa-e045-4eff-b17f-0ff60e372010"),
            List.of(Map.of("title", "hello")),
            List.of(Map.of()));
        Assertions.assertTrue(script.contains(".data.insertMany(List.of("), script);
        Assertions.assertTrue(
            script.contains(".uuid(\"8aeaa5fa-e045-4eff-b17f-0ff60e372010\")"), script);
        Assertions.assertTrue(script.contains(".properties(Map.of(\"title\", \"hello\"))"), script);
    }

    @Test
    public void updateIsOneMergeCallPerRow() {
        LinkedHashMap<String, Object> first = new LinkedHashMap<>();
        first.put("title", "new title");
        first.put("n", 42);
        String script = WeaviateDataScript.renderUpdate("Article", null,
            List.of("id-1", "id-2"),
            List.of(first, Map.of("title", "other")),
            List.of(Map.of(), Map.of()));
        Assertions.assertEquals(2, script.split("\\.data\\.update\\(", -1).length - 1, script);
        Assertions.assertTrue(script.contains("Map.of(\"title\", \"new title\", \"n\", 42)"), script);
        // The comment is the reason an edited cell does not blank its row, so it is part of the
        // answer rather than decoration.
        Assertions.assertTrue(script.contains("replace() would blank"), script);
    }

    @Test
    public void aVectorIsShortenedAndSaysSo() {
        float[] vector = new float[1536];
        vector[0] = 0.5f;
        String script = WeaviateDataScript.renderInsert("Article", null,
            List.of("id-1"), List.of(Map.of()), List.of(Map.of("default", vector)));
        Assertions.assertTrue(script.contains("Vectors.of(\"default\", new float[]{0.5f"), script);
        // Silently showing four numbers of 1536 would misrepresent what is being sent.
        Assertions.assertTrue(script.contains("1532 more of 1536"), script);
    }

    @Test
    public void quotesAndBackslashesSurviveAsJavaLiterals() {
        String script = WeaviateDataScript.renderInsert("Article", null,
            List.of("id-1"), List.of(Map.of("title", "He said \"hi\"\\bye")), List.of(Map.of()));
        Assertions.assertTrue(script.contains("\\\"hi\\\""), script);
        Assertions.assertTrue(script.contains("\\\\bye"), script);
    }

    @Test
    public void pastTenPropertiesTheSnippetStillCompiles() {
        LinkedHashMap<String, Object> many = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {
            many.put("p" + i, i);
        }
        String script = WeaviateDataScript.renderInsert("Article", null,
            List.of("id-1"), List.of(many), List.of(Map.of()));
        // Map.of tops out at ten pairs, so anything wider has to use ofEntries or the snippet is
        // one nobody can run.
        Assertions.assertTrue(script.contains("Map.ofEntries(Map.entry("), script);
        Assertions.assertFalse(script.contains(".properties(Map.of("), script);
    }
}
