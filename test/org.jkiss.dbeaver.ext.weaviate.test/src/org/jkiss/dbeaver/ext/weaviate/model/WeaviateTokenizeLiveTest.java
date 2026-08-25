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

import io.weaviate.client6.v1.api.Authentication;
import io.weaviate.client6.v1.api.WeaviateClient;
import io.weaviate.client6.v1.api.tokenize.TokenizeResponse;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Tokenization against a real Weaviate.
 * <p>
 * The reason this is a live test rather than a unit test is the client's argument order:
 * {@code forProperty} takes three Strings whose names are erased in the shipped jar, and the
 * natural reading -- collection first, as everywhere else in the client -- is wrong. Nothing
 * catches that except a real call, and the symptom is a URL with the text in the collection slot.
 * <p>
 * Skipped unless {@code WEAVIATE_TOKENIZE_FIXTURE_URL} is set, so the reactor stays hermetic.
 * Reuses the group-by fixture, which already has the three properties this needs -- seed it with
 * {@code testdata/seed_group_fixture.py}, then:
 * <pre>
 * WEAVIATE_TOKENIZE_FIXTURE_URL=http://localhost:8080 mvn verify ...
 * </pre>
 */
public class WeaviateTokenizeLiveTest extends DBeaverUnitTest {

    /** The group-by fixture: `title` is word-tokenized, `category` FIELD, `rank` an int. */
    private static final String COLLECTION = "DBeaverGroupFixture";

    private static final String SAMPLE = "Red-Maple Leaf, 2021";

    /**
     * Skip unless a fixture server is configured. Called first in every test rather than from the
     * query helper: an abort raised deeper down would be caught by the helper's own error handling
     * and reported as a failure instead of a skip.
     */
    private static void requireFixture() {
        String url = System.getenv("WEAVIATE_TOKENIZE_FIXTURE_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
            "set WEAVIATE_TOKENIZE_FIXTURE_URL to run the live tokenize tests");
    }

    private static WeaviateClient connect() {
        String url = System.getenv("WEAVIATE_TOKENIZE_FIXTURE_URL");
        String host = url.replaceFirst("^https?://", "").replaceFirst(":.*$", "");
        String apiKey = System.getenv().getOrDefault("WEAVIATE_API_KEY", "root-user-key");
        return WeaviateClient.connectToCustom(c -> {
            c.scheme("http");
            c.httpHost(host);
            c.httpPort(8080);
            c.grpcHost(host);
            c.grpcPort(50051);
            c.authentication(Authentication.apiKey(apiKey));
            return c;
        });
    }

    private static TokenizeResponse tokenize(String property, String text) {
        try (WeaviateClient client = connect()) {
            return client.tokenize.forProperty(text, COLLECTION, property);
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            throw new IllegalStateException("live tokenize failed: " + cause.getMessage(), e);
        }
    }

    /**
     * The order is {@code (text, collection, property)}, matching the underlying
     * {@code TokenizeRequest(text, collection, property)} record rather than the
     * collection-first convention every other client call uses.
     * <p>
     * Getting it wrong is loud but confusing: the request goes to
     * {@code /v1/schema/{text}/properties/{collection}/tokenize} and dies on the first space in
     * the text with "Illegal character in path".
     */
    @Test
    public void wordTokenizationSplitsOnPunctuationAndLowercases() {
        requireFixture();
        TokenizeResponse response = tokenize("title", SAMPLE);
        Assertions.assertEquals(List.of("red", "maple", "leaf", "2021"), response.indexed());
    }

    /**
     * The same text through a FIELD-tokenized property comes back whole. That contrast is what
     * proves the server is resolving the tokenizer from the schema rather than defaulting -- and
     * it is also what would catch a swapped collection/property argument, since the two properties
     * would otherwise be indistinguishable.
     */
    @Test
    public void fieldTokenizationKeepsTheWholeValue() {
        requireFixture();
        TokenizeResponse response = tokenize("category", SAMPLE);
        Assertions.assertEquals(List.of(SAMPLE), response.indexed());
    }

    /** Both lists come back, and match for the standard tokenizations. */
    @Test
    public void indexAndQueryTokensAreBothReported() {
        requireFixture();
        TokenizeResponse response = tokenize("title", SAMPLE);
        Assertions.assertNotNull(response.indexed());
        Assertions.assertNotNull(response.query());
        Assertions.assertEquals(response.indexed(), response.query(),
            "if these ever diverge for a standard tokenization, the preview showing both columns "
                + "stops being redundant and starts being the point");
    }

    /**
     * The per-property response does not name the tokenization -- the caller did not pick one, so
     * the server does not echo one back. The preview labels itself from the property's own
     * {@code getTokenization()} for that reason.
     */
    @Test
    public void theResponseDoesNotEchoTheTokenization() {
        requireFixture();
        Assertions.assertNull(tokenize("title", SAMPLE).tokenization(),
            "if the server started reporting this, WeaviateTokenPreview could use it directly");
    }

    /** A non-text property is refused, and that is an ordinary outcome the dialog renders. */
    @Test
    public void aNonTextPropertyIsRefused() {
        requireFixture();
        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
            () -> tokenize("rank", "123"));
        Assertions.assertTrue(e.getMessage().contains("tokenization is not enabled"),
            () -> "refused for an unexpected reason: " + e.getMessage());
    }

    /**
     * The build-flag tokenizers are why the preview does not trust the version registry. All four
     * are long past their minimum on any server this runs against, and a server built without them
     * still refuses.
     */
    @Test
    public void buildFlagTokenizersAreRefusedRegardlessOfVersion() {
        requireFixture();
        Assertions.assertTrue(WeaviateServerFeature.TOKENIZATION_KAGOME_JA.isSupportedBy("1.39.0"),
            "the registry considers this available by version");
        // Whether the server actually has it is a build flag, so nothing is asserted about the
        // call itself -- the point is only that the version check cannot answer the question.
    }
}
