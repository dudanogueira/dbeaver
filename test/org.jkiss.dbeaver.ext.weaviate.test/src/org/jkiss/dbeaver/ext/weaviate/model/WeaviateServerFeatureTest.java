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
import java.util.Map;

/**
 * The feature registry. Its main job is to agree with weaviate-studio, so the correspondence is
 * written down here rather than left to memory.
 */
public class WeaviateServerFeatureTest extends DBeaverUnitTest {

    /**
     * Verbatim from weaviate-add-collection's {@code src/constants/versionFeatures.js}, plus the
     * two multi-target entries from that project's other gate,
     * {@code data-explorer/webview/utils/versionCheck.ts}.
     * <p>
     * If a port drifts, this is where it shows up. {@code TOKENIZE} is deliberately absent: studio
     * has no entry for it, and ours comes from the Weaviate server source instead.
     */
    private static Map<WeaviateServerFeature, String> studioRegistry() {
        Map<WeaviateServerFeature, String> expected = new LinkedHashMap<>();
        expected.put(WeaviateServerFeature.MULTI_TENANCY, "1.20.0");
        expected.put(WeaviateServerFeature.AUTO_TENANT_CREATION, "1.25.2");
        expected.put(WeaviateServerFeature.AUTO_TENANT_ACTIVATION, "1.25.2");
        expected.put(WeaviateServerFeature.REPLICATION_ASYNC_ENABLED, "1.29.0");
        expected.put(WeaviateServerFeature.REPLICATION_DELETION_STRATEGY, "1.28.0");
        expected.put(WeaviateServerFeature.DYNAMIC_INDEX_TYPE, "1.25.0");
        expected.put(WeaviateServerFeature.HFRESH_INDEX_TYPE, "1.36.0");
        expected.put(WeaviateServerFeature.RQ_QUANTIZATION_HNSW, "1.35.0");
        expected.put(WeaviateServerFeature.RQ_QUANTIZATION_FLAT, "1.35.0");
        expected.put(WeaviateServerFeature.INDEX_RANGE_FILTERS, "1.24.0");
        expected.put(WeaviateServerFeature.OBJECT_TTL, "1.35.0");
        expected.put(WeaviateServerFeature.TOKENIZATION_GSE, "1.24.0");
        expected.put(WeaviateServerFeature.TOKENIZATION_TRIGRAM, "1.24.0");
        expected.put(WeaviateServerFeature.TOKENIZATION_KAGOME_KR, "1.25.7");
        expected.put(WeaviateServerFeature.TOKENIZATION_KAGOME_JA, "1.28.0");
        expected.put(WeaviateServerFeature.MULTI_TARGET_NEAR, "1.26.0");
        expected.put(WeaviateServerFeature.MULTI_TARGET_HYBRID, "1.27.0");
        return expected;
    }

    @Test
    public void portedVersionsMatchWeaviateStudio() {
        studioRegistry().forEach((feature, version) ->
            Assertions.assertEquals(version, feature.getMinVersion(),
                () -> feature + " drifted from weaviate-studio's registry"));
    }

    /** From the Weaviate server source, not from studio -- studio never calls the endpoint. */
    @Test
    public void tokenizeCarriesTheVersionItLandedIn() {
        Assertions.assertEquals("1.37.0", WeaviateServerFeature.TOKENIZE.getMinVersion());
        Assertions.assertFalse(WeaviateServerFeature.TOKENIZE.isSupportedBy("1.36.0"));
        Assertions.assertTrue(WeaviateServerFeature.TOKENIZE.isSupportedBy("1.37.0"));
        Assertions.assertTrue(WeaviateServerFeature.TOKENIZE.isSupportedBy("1.39.0"));
    }

    /** Every entry is a real Weaviate version, so a typo'd 0 or a negative cannot slip in. */
    @Test
    public void everyEntryHasASaneMinimum() {
        for (WeaviateServerFeature feature : WeaviateServerFeature.values()) {
            Assertions.assertTrue(feature.getMinMajor() >= 1, () -> feature + " has no major");
            Assertions.assertTrue(feature.getMinMinor() >= 0, () -> feature + " has a bad minor");
            Assertions.assertTrue(feature.getMinPatch() >= 0, () -> feature + " has a bad patch");
            Assertions.assertFalse(feature.getMinVersion().isBlank(), () -> feature + " has no label");
        }
    }

    /** The permissive default reaches the registry too, not just the parser. */
    @Test
    public void anUnknownVersionSupportsEverything() {
        for (WeaviateServerFeature feature : WeaviateServerFeature.values()) {
            Assertions.assertTrue(feature.isSupportedBy(null), () -> feature + " hidden on null");
            Assertions.assertTrue(feature.isSupportedBy("unknown"),
                () -> feature + " hidden on \"unknown\"");
        }
    }

    /**
     * The tokenizer entries are kept for parity with studio but are not a usable test: those
     * tokenizers are server build flags, and a 1.39.0 server without them still refuses. This
     * asserts the trap rather than the capability, so nobody later mistakes the entry for an
     * answer.
     */
    @Test
    public void tokenizerEntriesAnswerVersionNotAvailability() {
        Assertions.assertTrue(WeaviateServerFeature.TOKENIZATION_KAGOME_JA.isSupportedBy("1.39.0"),
            "the registry says 1.39.0 is new enough -- and the lab server still answers 422 "
                + "'unsupported tokenization strategy', because ENABLE_TOKENIZER_KAGOME_JA is off. "
                + "Ask the server, not this enum.");
    }
}
