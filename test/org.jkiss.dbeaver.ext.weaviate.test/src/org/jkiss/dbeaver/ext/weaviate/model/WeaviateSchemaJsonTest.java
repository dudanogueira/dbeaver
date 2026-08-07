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

import io.weaviate.client6.v1.api.collections.CollectionConfig;
import io.weaviate.client6.v1.api.collections.Property;
import org.jkiss.dbeaver.DBException;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class WeaviateSchemaJsonTest extends DBeaverUnitTest {

    /**
     * Definition in the shape emitted by https://weaviate.github.io/weaviate-add-collection/ -
     * the whole point of the JSON import path is that this parses without translation.
     */
    private static final String BUILDER_TOOL_JSON = """
        {
          "class": "MyCollection",
          "description": "This is a demo collection",
          "properties": [
            {
              "name": "property1",
              "dataType": ["text"],
              "indexFilterable": true,
              "indexSearchable": true,
              "tokenization": "word"
            }
          ],
          "vectorConfig": {
            "default": {
              "vectorizer": {
                "text2vec-openai": {
                  "properties": ["property1"],
                  "model": "text-embedding-3-small"
                }
              }
            }
          }
        }
        """;

    @Test
    public void serializesCollectionNameAsClass() {
        String json = WeaviateSchemaJson.toJson(CollectionConfig.of("Article"));
        Assertions.assertTrue(json.contains("\"class\""), () -> "expected REST 'class' field in: " + json);
        Assertions.assertTrue(json.contains("Article"), () -> "expected collection name in: " + json);
    }

    @Test
    public void roundTripPreservesDefinition() throws DBException {
        CollectionConfig original = CollectionConfig.of("Article", c -> c
            .description("demo")
            .properties(Property.text("title"), Property.integer("wordCount")));

        CollectionConfig parsed = WeaviateSchemaJson.fromJson(WeaviateSchemaJson.toJson(original));

        Assertions.assertEquals("Article", parsed.collectionName());
        Assertions.assertEquals("demo", parsed.description());
        Assertions.assertEquals(2, parsed.properties().size());
    }

    @Test
    public void parsesDefinitionFromBuilderTool() throws DBException {
        CollectionConfig parsed = WeaviateSchemaJson.fromJson(BUILDER_TOOL_JSON);

        Assertions.assertEquals("MyCollection", parsed.collectionName());
        Assertions.assertEquals("This is a demo collection", parsed.description());
        Assertions.assertEquals(1, parsed.properties().size());
        Assertions.assertEquals("property1", parsed.properties().get(0).propertyName());
        // The nested vectorizer block must survive, not just the top-level fields.
        Assertions.assertTrue(parsed.vectors().containsKey("default"));
    }

    @Test
    public void rejectsEmptyInput() {
        Assertions.assertThrows(DBException.class, () -> WeaviateSchemaJson.fromJson(null));
        Assertions.assertThrows(DBException.class, () -> WeaviateSchemaJson.fromJson("   "));
    }

    @Test
    public void rejectsMalformedJson() {
        Assertions.assertThrows(DBException.class, () -> WeaviateSchemaJson.fromJson("{ not json"));
    }

    @Test
    public void rejectsNonObjectJson() {
        Assertions.assertThrows(DBException.class, () -> WeaviateSchemaJson.fromJson("[1, 2, 3]"));
    }

    @Test
    public void rejectsDefinitionWithoutCollectionName() {
        // Would otherwise reach the server as a nameless create and fail obscurely.
        Assertions.assertThrows(DBException.class,
            () -> WeaviateSchemaJson.fromJson("{\"description\": \"no class field\"}"));
        Assertions.assertThrows(DBException.class,
            () -> WeaviateSchemaJson.fromJson("{\"class\": \"  \"}"));
    }

    @Test
    public void templateIsValidAndCarriesTheGivenName() throws DBException {
        CollectionConfig parsed = WeaviateSchemaJson.fromJson(WeaviateSchemaJson.newTemplate("Seeded"));
        Assertions.assertEquals("Seeded", parsed.collectionName());
        Assertions.assertFalse(parsed.properties().isEmpty());
    }

    @Test
    public void withCollectionNameRewritesOnlyTheName() throws DBException {
        String renamed = WeaviateSchemaJson.withCollectionName(BUILDER_TOOL_JSON, "Renamed");
        CollectionConfig parsed = WeaviateSchemaJson.fromJson(renamed);

        Assertions.assertEquals("Renamed", parsed.collectionName());
        Assertions.assertEquals("This is a demo collection", parsed.description());
        Assertions.assertEquals(1, parsed.properties().size());
    }

    @Test
    public void withCollectionNameLeavesUnparseableInputAlone() {
        String garbage = "{ not json";
        Assertions.assertEquals(garbage, WeaviateSchemaJson.withCollectionName(garbage, "X"));
    }

    // ---- Vector index handling -------------------------------------------------------------
    //
    // The bundled client (6.3.0) throws a bare NullPointerException from deep inside Gson when it
    // cannot map a vector index definition. Reaching the user, that reads as "Internal error
    // (NPE)" with nothing to act on, so these tests pin down that we always convert it into a
    // DBException carrying an explanation.

    private static String withVectorIndex(String vectorIndexJson) {
        return "{\"class\":\"A\",\"vectorConfig\":{\"default\":{\"vectorizer\":{\"none\":{}},"
            + vectorIndexJson + "}}}";
    }

    @Test
    public void validationAcceptsWhatTheClientCannotModel() {
        // The decisive property of the create path: a bare "dynamic" index is valid to Weaviate
        // but unreadable by the bundled client. Validation must let it through, because the
        // document is POSTed verbatim and only the server judges it.
        Assertions.assertDoesNotThrow(
            () -> WeaviateSchemaJson.validate(withVectorIndex("\"vectorIndexType\":\"dynamic\"")));
        Assertions.assertDoesNotThrow(() -> WeaviateSchemaJson.validate(withVectorIndex(
            "\"vectorIndexType\":\"dynamic\",\"vectorIndexConfig\":{\"threshold\":5000}")));

        // ...even though parsing it into the client's model still fails. If this ever starts
        // succeeding the client has been upgraded, and the note above can be revisited.
        Assertions.assertThrows(DBException.class,
            () -> WeaviateSchemaJson.fromJson(withVectorIndex("\"vectorIndexType\":\"dynamic\"")));
    }

    @Test
    public void validationAcceptsUnknownFields() {
        // Fields from a newer server than the bundled client knows about must not be a reason to
        // refuse: they are the user's to send, and dropping or rejecting them would lose data.
        Assertions.assertDoesNotThrow(() -> WeaviateSchemaJson.validate(
            "{\"class\":\"A\",\"someFutureSetting\":{\"nested\":true},\"properties\":[]}"));
    }

    @Test
    public void validationStillRequiresJsonObjectAndName() {
        Assertions.assertThrows(DBException.class, () -> WeaviateSchemaJson.validate("{ not json"));
        Assertions.assertThrows(DBException.class, () -> WeaviateSchemaJson.validate("[1,2,3]"));
        Assertions.assertThrows(DBException.class, () -> WeaviateSchemaJson.validate(null));
        Assertions.assertThrows(DBException.class, () -> WeaviateSchemaJson.validate("   "));
        Assertions.assertThrows(DBException.class,
            () -> WeaviateSchemaJson.validate("{\"description\":\"no class\"}"));
        Assertions.assertThrows(DBException.class,
            () -> WeaviateSchemaJson.validate("{\"class\":\"  \"}"));
    }

    @Test
    public void dynamicIndexWithCompleteConfigIsAccepted() throws DBException {
        CollectionConfig parsed = WeaviateSchemaJson.fromJson(withVectorIndex(
            "\"vectorIndexType\":\"dynamic\","
                + "\"vectorIndexConfig\":{\"hnsw\":{},\"flat\":{},\"threshold\":10000}"));
        Assertions.assertEquals("A", parsed.collectionName());
        Assertions.assertTrue(parsed.vectors().containsKey("default"));
    }

    @Test
    public void completeDynamicIndexSurvivesExportAndReimport() throws DBException {
        // Guards the export -> re-create round trip for collections that really do use a dynamic
        // index: whatever we write out has to be readable again.
        CollectionConfig parsed = WeaviateSchemaJson.fromJson(withVectorIndex(
            "\"vectorIndexType\":\"dynamic\","
                + "\"vectorIndexConfig\":{\"hnsw\":{},\"flat\":{},\"threshold\":10000}"));
        CollectionConfig again = WeaviateSchemaJson.fromJson(WeaviateSchemaJson.toJson(parsed));
        Assertions.assertEquals("A", again.collectionName());
    }

    @Test
    public void hnswIndexIsUnaffected() throws DBException {
        // The dynamic-index guard must not reject ordinary index types.
        CollectionConfig parsed = WeaviateSchemaJson.fromJson(
            withVectorIndex("\"vectorIndexType\":\"hnsw\""));
        Assertions.assertEquals("A", parsed.collectionName());
    }

    @Test
    public void vectorConfigWithoutIndexTypeIsUnaffected() throws DBException {
        Assertions.assertEquals("MyCollection",
            WeaviateSchemaJson.fromJson(BUILDER_TOOL_JSON).collectionName());
    }

    @Test
    public void hostileInputNeverEscapesAsRuntimeException() {
        // Blanket guarantee behind all of the above: whatever the client does internally, callers
        // only ever see DBException.
        String[] hostile = {
            "{\"class\":\"A\",\"properties\":\"not-an-array\"}",
            "{\"class\":\"A\",\"vectorConfig\":{\"default\":{\"vectorizer\":\"not-an-object\"}}}",
            "{\"class\":\"A\",\"vectorConfig\":{\"default\":null}}",
            "{\"class\":\"A\",\"properties\":[{\"name\":\"p\",\"dataType\":\"text\"}]}",
        };
        for (String json : hostile) {
            Assertions.assertDoesNotThrow(
                () -> {
                    try {
                        WeaviateSchemaJson.fromJson(json);
                    } catch (DBException expected) {
                        // fine - a reported problem is the desired outcome
                    }
                },
                () -> "raw runtime exception escaped for: " + json);
        }
    }

    @Test
    public void readCollectionNameExtractsOrReturnsNull() {
        Assertions.assertEquals("MyCollection", WeaviateSchemaJson.readCollectionName(BUILDER_TOOL_JSON));
        Assertions.assertNull(WeaviateSchemaJson.readCollectionName(null));
        Assertions.assertNull(WeaviateSchemaJson.readCollectionName("{ not json"));
        Assertions.assertNull(WeaviateSchemaJson.readCollectionName("{\"description\":\"x\"}"));
    }
}
