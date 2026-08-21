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

import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public class WeaviateModelHeadersTest extends DBeaverUnitTest {

    private static final Function<String, String> NO_ENV = name -> null;

    @Test
    public void nothingConfiguredSendsNoHeaders() {
        Assertions.assertTrue(WeaviateModelHeaders.resolve(new DBPConnectionConfiguration(), NO_ENV).isEmpty());
    }

    @Test
    public void configuredKeyBecomesProviderHeader() {
        DBPConnectionConfiguration cfg = new DBPConnectionConfiguration();
        cfg.setAuthProperty(WeaviateModelHeaders.keyProperty(WeaviateModelProvider.OPENAI), "sk-test");
        Map<String, String> headers = WeaviateModelHeaders.resolve(cfg, NO_ENV);
        Assertions.assertEquals("sk-test", headers.get("X-OpenAI-Api-Key"));
        Assertions.assertEquals(1, headers.size());
    }

    @Test
    public void environmentIsUsedWhenNoKeyConfigured() {
        Map<String, String> headers = WeaviateModelHeaders.resolve(
            new DBPConnectionConfiguration(),
            name -> "OPENAI_APIKEY".equals(name) ? "from-env" : null);
        Assertions.assertEquals("from-env", headers.get("X-OpenAI-Api-Key"));
    }

    @Test
    public void configuredKeyOverridesEnvironment() {
        DBPConnectionConfiguration cfg = new DBPConnectionConfiguration();
        cfg.setAuthProperty(WeaviateModelHeaders.keyProperty(WeaviateModelProvider.OPENAI), "explicit");
        Map<String, String> headers = WeaviateModelHeaders.resolve(cfg, name -> "from-env");
        Assertions.assertEquals("explicit", headers.get("X-OpenAI-Api-Key"));
    }

    @Test
    public void bothEnvSpellingsAreAcceptedWithWeaviateOneFirst() {
        Map<String, String> sdkSpelling = WeaviateModelHeaders.resolve(
            new DBPConnectionConfiguration(),
            name -> "OPENAI_API_KEY".equals(name) ? "sdk" : null);
        Assertions.assertEquals("sdk", sdkSpelling.get("X-OpenAI-Api-Key"));

        Map<String, String> both = WeaviateModelHeaders.resolve(
            new DBPConnectionConfiguration(),
            name -> switch (name) {
                case "OPENAI_APIKEY" -> "weaviate-style";
                case "OPENAI_API_KEY" -> "sdk-style";
                default -> null;
            });
        Assertions.assertEquals("weaviate-style", both.get("X-OpenAI-Api-Key"),
            "the Weaviate-convention variable is declared first and should win");
    }

    @Test
    public void blankValuesAreTreatedAsUnset() {
        DBPConnectionConfiguration cfg = new DBPConnectionConfiguration();
        cfg.setAuthProperty(WeaviateModelHeaders.keyProperty(WeaviateModelProvider.OPENAI), "   ");
        Assertions.assertTrue(WeaviateModelHeaders.resolve(cfg, name -> "  ").isEmpty());
    }

    @Test
    public void customHeaderIsSent() {
        DBPConnectionConfiguration cfg = new DBPConnectionConfiguration();
        cfg.setAuthProperty(WeaviateModelHeaders.headerProperty("X-Mistral-Api-Key"), "m-key");
        Assertions.assertEquals("m-key",
            WeaviateModelHeaders.resolve(cfg, NO_ENV).get("X-Mistral-Api-Key"));
    }

    /**
     * A provider not modelled here must still be reachable, and an explicit header must be able
     * to override a provider row -- otherwise the custom table is useless for the exact cases
     * it exists to cover.
     */
    @Test
    public void customHeaderOverridesProviderRowForSameHeader() {
        DBPConnectionConfiguration cfg = new DBPConnectionConfiguration();
        cfg.setAuthProperty(WeaviateModelHeaders.keyProperty(WeaviateModelProvider.OPENAI), "from-row");
        cfg.setAuthProperty(WeaviateModelHeaders.headerProperty("X-OpenAI-Api-Key"), "from-custom");
        Assertions.assertEquals("from-custom",
            WeaviateModelHeaders.resolve(cfg, NO_ENV).get("X-OpenAI-Api-Key"));
    }

    @Test
    public void customHeaderOverridesEnvironmentToo() {
        DBPConnectionConfiguration cfg = new DBPConnectionConfiguration();
        cfg.setAuthProperty(WeaviateModelHeaders.headerProperty("X-OpenAI-Api-Key"), "from-custom");
        Assertions.assertEquals("from-custom",
            WeaviateModelHeaders.resolve(cfg, name -> "from-env").get("X-OpenAI-Api-Key"));
    }

    @Test
    public void providerHeaderNamesAreUniqueAndWellFormed() {
        Set<String> seen = new HashSet<>();
        for (WeaviateModelProvider p : WeaviateModelProvider.values()) {
            Assertions.assertTrue(p.getHeader().startsWith("X-"), p + " -> " + p.getHeader());
            Assertions.assertTrue(seen.add(p.getHeader()), "duplicate header: " + p.getHeader());
            Assertions.assertFalse(p.getEnvVars().isEmpty(), p + " has no env var");
            Assertions.assertEquals(p, WeaviateModelProvider.byId(p.getId()));
        }
        Assertions.assertNull(WeaviateModelProvider.byId("nope"));
    }

    /**
     * Google renamed these headers; the old X-Google-* spellings are deprecated server-side.
     */
    @Test
    public void googleUsesTheCurrentGoogPrefix() {
        Assertions.assertEquals("X-Goog-Vertex-Api-Key", WeaviateModelProvider.GOOGLE_VERTEX.getHeader());
        Assertions.assertEquals("X-Goog-Studio-Api-Key", WeaviateModelProvider.GOOGLE_STUDIO.getHeader());
    }
}
