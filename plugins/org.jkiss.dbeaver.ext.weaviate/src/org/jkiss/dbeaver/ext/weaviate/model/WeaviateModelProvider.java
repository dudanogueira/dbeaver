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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.List;

/**
 * Model provider API key headers that Weaviate accepts at request time.
 * <p>
 * Weaviate reads a provider's credentials from its own environment (e.g. {@code OPENAI_APIKEY}
 * on the server), but a request header of the same provider takes precedence for that request.
 * That is what lets a user point at a shared cluster and still use their own key.
 * <p>
 * Only providers whose header spelling was verified against the Weaviate docs are listed here.
 * The casing is not cosmetic and not uniform -- Google switched to a {@code X-Goog-} prefix and
 * deprecated its older {@code X-Google-} names, and JinaAI/VoyageAI/HuggingFace run words
 * together. Anything absent from this list is still reachable through the custom header table,
 * which is why guessing at an unverified name would be the worse option.
 */
public enum WeaviateModelProvider {

    OPENAI("openai", "OpenAI", "X-OpenAI-Api-Key", "OPENAI_APIKEY", "OPENAI_API_KEY"),
    COHERE("cohere", "Cohere", "X-Cohere-Api-Key", "COHERE_APIKEY", "COHERE_API_KEY"),
    ANTHROPIC("anthropic", "Anthropic", "X-Anthropic-Api-Key", "ANTHROPIC_APIKEY", "ANTHROPIC_API_KEY"),
    GOOGLE_VERTEX("googleVertex", "Google Vertex AI", "X-Goog-Vertex-Api-Key", "GOOGLE_APIKEY", "GOOGLE_API_KEY"),
    GOOGLE_STUDIO("googleStudio", "Google AI Studio", "X-Goog-Studio-Api-Key", "GOOGLE_STUDIO_APIKEY"),
    HUGGINGFACE("huggingface", "Hugging Face", "X-HuggingFace-Api-Key", "HUGGINGFACE_APIKEY", "HUGGINGFACE_API_KEY"),
    JINAAI("jinaai", "Jina AI", "X-JinaAI-Api-Key", "JINAAI_APIKEY", "JINAAI_API_KEY"),
    VOYAGEAI("voyageai", "Voyage AI", "X-VoyageAI-Api-Key", "VOYAGEAI_APIKEY", "VOYAGEAI_API_KEY");

    private final String id;
    private final String label;
    private final String header;
    private final List<String> envVars;

    WeaviateModelProvider(
        @NotNull String id,
        @NotNull String label,
        @NotNull String header,
        @NotNull String... envVars
    ) {
        this.id = id;
        this.label = label;
        this.header = header;
        this.envVars = List.of(envVars);
    }

    @NotNull
    public String getId() {
        return id;
    }

    @NotNull
    public String getLabel() {
        return label;
    }

    @NotNull
    public String getHeader() {
        return header;
    }

    /**
     * Environment variables consulted when no key is configured, in order.
     * <p>
     * Two spellings per provider on purpose: Weaviate's own server-side convention is
     * {@code OPENAI_APIKEY}, while the provider's own SDK convention is usually
     * {@code OPENAI_API_KEY}, and a developer's shell almost always has the latter.
     */
    @NotNull
    public List<String> getEnvVars() {
        return envVars;
    }

    @Nullable
    public static WeaviateModelProvider byId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (WeaviateModelProvider p : values()) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }
}
