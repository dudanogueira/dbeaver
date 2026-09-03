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

import org.jkiss.dbeaver.DBException;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * The document a configuration edit is carried in.
 * <p>
 * The behaviour worth pinning is what it does <em>not</em> do: it must not lose anything it was
 * not asked to change, because that is the entire reason the plugin does not use the client's
 * {@code config.update} for this.
 */
public class WeaviateConfigDocumentTest extends DBeaverUnitTest {

    private static final String DEFINITION = """
        {
          "class": "Products",
          "description": "before",
          "invertedIndexConfig": {
            "bm25": { "b": 0.75, "k1": 1.2 },
            "cleanupIntervalSeconds": 60,
            "stopwords": { "preset": "en", "additions": ["foo"] },
            "usingBlockMaxWAND": true
          },
          "moduleConfig": { "reranker-cohere": { "model": "rerank-english-v3.0" } },
          "somethingThisBuildHasNeverHeardOf": { "nested": [1, 2, 3] },
          "vectorConfig": {
            "default": { "vectorIndexConfig": { "ef": -1, "skip": false } }
          }
        }""";

    private static WeaviateConfigDocument parse() throws DBException {
        return WeaviateConfigDocument.of(DEFINITION);
    }

    @Test
    public void readsThroughDottedPaths() throws DBException {
        WeaviateConfigDocument document = parse();
        Assertions.assertEquals("Products", document.getCollectionName());
        Assertions.assertEquals("before", document.getString("description"));
        Assertions.assertEquals(0.75, document.getNumber("invertedIndexConfig.bm25.b"), 1e-9);
        Assertions.assertEquals(Boolean.TRUE, document.getBoolean("invertedIndexConfig.usingBlockMaxWAND"));
        Assertions.assertEquals(List.of("foo"), document.getStrings("invertedIndexConfig.stopwords.additions"));
        Assertions.assertEquals(-1.0, document.getNumber("vectorConfig.default.vectorIndexConfig.ef"), 1e-9);
    }

    @Test
    public void anAbsentPathIsNullRatherThanADefault() throws DBException {
        WeaviateConfigDocument document = parse();
        // "the server did not report this" and "the server reported 0" are different answers, and
        // a form that conflates them invites someone to change a setting that is not there. The
        // three index* flags are exactly this case: absent from the definition while false.
        Assertions.assertNull(document.getNumber("invertedIndexConfig.nothingHere"));
        Assertions.assertNull(document.getBoolean("invertedIndexConfig.indexTimestamps"));
        Assertions.assertNull(document.getString("nowhere.at.all"));
        Assertions.assertFalse(document.has("invertedIndexConfig.indexTimestamps"));
    }

    @Test
    public void aWrongTypeReadsAsAbsentRatherThanThrowing() throws DBException {
        WeaviateConfigDocument document = parse();
        // A server that changes a field's type should not take the editor down with it.
        Assertions.assertNull(document.getNumber("description"));
        Assertions.assertNull(document.getBoolean("invertedIndexConfig.bm25.b"));
        Assertions.assertTrue(document.getStrings("description").isEmpty());
    }

    @Test
    public void editingOnePathLeavesEverythingElseAlone() throws DBException {
        WeaviateConfigDocument document = parse();
        document.setNumber("invertedIndexConfig.bm25.k1", 1.5);

        // The siblings the client's nested lambda setters would have nulled.
        Assertions.assertEquals(0.75, document.getNumber("invertedIndexConfig.bm25.b"), 1e-9);
        Assertions.assertEquals(60.0, document.getNumber("invertedIndexConfig.cleanupIntervalSeconds"), 1e-9);
        Assertions.assertEquals("en", document.getString("invertedIndexConfig.stopwords.preset"));
        Assertions.assertEquals(Boolean.TRUE, document.getBoolean("invertedIndexConfig.usingBlockMaxWAND"));
        Assertions.assertEquals(1.5, document.getNumber("invertedIndexConfig.bm25.k1"), 1e-9);
    }

    @Test
    public void whatThisBuildCannotModelSurvivesAnEdit() throws DBException {
        WeaviateConfigDocument document = parse();
        document.setString("description", "after");
        String json = document.toJson();

        // The whole argument for editing the raw document: a module or a section from a newer
        // server is carried through untouched, because nothing ever parsed it.
        Assertions.assertTrue(json.contains("somethingThisBuildHasNeverHeardOf"), json);
        Assertions.assertTrue(json.contains("reranker-cohere"), json);
        Assertions.assertTrue(json.contains("\"after\""), json);
    }

    @Test
    public void settingAPathCreatesTheObjectsOnTheWay() throws DBException {
        WeaviateConfigDocument document = parse();
        // objectTtlConfig is absent from a definition entirely until something enables it, so an
        // editor that could only overwrite existing paths could never turn one on.
        Assertions.assertFalse(document.has("objectTtlConfig.enabled"));
        document.setBoolean("objectTtlConfig.enabled", true);
        Assertions.assertEquals(Boolean.TRUE, document.getBoolean("objectTtlConfig.enabled"));
    }

    @Test
    public void removingAPathWhoseParentIsAbsentIsANoOp() throws DBException {
        WeaviateConfigDocument document = parse();
        document.remove("objectTtlConfig.enabled");
        Assertions.assertFalse(document.has("objectTtlConfig"));
    }

    @Test
    public void changesAreReportedOnlyForThePathsAsked() throws DBException {
        WeaviateConfigDocument original = parse();
        WeaviateConfigDocument edited = original.copy();
        edited.setNumber("invertedIndexConfig.bm25.b", 0.5);
        edited.setString("description", "after");

        List<String> watched = List.of(
            "description", "invertedIndexConfig.bm25.b", "invertedIndexConfig.bm25.k1");
        Assertions.assertEquals(
            List.of("description", "invertedIndexConfig.bm25.b"),
            edited.changedAmong(original, watched));
    }

    @Test
    public void aCopyDoesNotShareStateWithItsOriginal() throws DBException {
        WeaviateConfigDocument original = parse();
        WeaviateConfigDocument edited = original.copy();
        edited.setString("description", "after");
        // Revert has to be able to throw the edit away, which means it was never applied here.
        Assertions.assertEquals("before", original.getString("description"));
    }

    @Test
    public void objectValuesRoundTripAsPlainStrings() throws DBException {
        WeaviateConfigDocument document = parse();
        Assertions.assertEquals(
            Map.of("model", "rerank-english-v3.0"),
            document.getObject("moduleConfig.reranker-cohere"));

        document.setObject("moduleConfig.reranker-jinaai", Map.of("model", "jina-reranker-v2"));
        Assertions.assertEquals(
            List.of("reranker-cohere", "reranker-jinaai"), document.getKeys("moduleConfig"));

        document.remove("moduleConfig.reranker-cohere");
        Assertions.assertEquals(List.of("reranker-jinaai"), document.getKeys("moduleConfig"));
    }

    @Test
    public void aBodyThatIsNotACollectionIsRefused() {
        Assertions.assertThrows(DBException.class, () -> WeaviateConfigDocument.of("[1, 2]"));
        Assertions.assertThrows(DBException.class, () -> WeaviateConfigDocument.of("not json"));
    }
}
