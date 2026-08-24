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

import io.weaviate.client6.v1.api.collections.generate.GenerativeProvider;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

/**
 * Generative providers a query can name at run time, overriding the collection's configured
 * generative module.
 * <p>
 * Mirrors the {@code GenerativeProvider} factories of the shaded client -- wrapped, as ever, so
 * the UI bundle never sees a shaded class. Only providers constructible from the common fields
 * (model, temperature, max tokens) are offered; awsBedrock, awsSagemaker, googleVertex and azure
 * need required extra arguments (region, project, deployment...) and are left out until there is
 * a UI to collect those.
 * <p>
 * A field a given provider's builder does not have is silently skipped rather than failed on:
 * the setters differ per provider (Google names the model {@code modelId}, Deepseek fixes it,
 * Ollama and Anyscale take no token cap) and the server applies its own default for anything
 * not sent.
 */
public enum WeaviateGenerativeProvider {
    OPENAI("OpenAI") {
        @Override
        GenerativeProvider build(String model, Float temperature, Integer maxTokens) {
            return GenerativeProvider.openai(b -> {
                if (model != null) b.model(model);
                if (temperature != null) b.temperature(temperature);
                if (maxTokens != null) b.maxTokens(maxTokens);
                return b;
            });
        }
    },
    ANTHROPIC("Anthropic") {
        @Override
        GenerativeProvider build(String model, Float temperature, Integer maxTokens) {
            return GenerativeProvider.anthropic(b -> {
                if (model != null) b.model(model);
                if (temperature != null) b.temperature(temperature);
                if (maxTokens != null) b.maxTokens(maxTokens);
                return b;
            });
        }
    },
    COHERE("Cohere") {
        @Override
        GenerativeProvider build(String model, Float temperature, Integer maxTokens) {
            return GenerativeProvider.cohere(b -> {
                if (model != null) b.model(model);
                if (temperature != null) b.temperature(temperature);
                if (maxTokens != null) b.maxTokens(maxTokens);
                return b;
            });
        }
    },
    GOOGLE_AI_STUDIO("Google AI Studio") {
        @Override
        GenerativeProvider build(String model, Float temperature, Integer maxTokens) {
            return GenerativeProvider.googleAiStudio(b -> {
                if (model != null) b.modelId(model);
                if (temperature != null) b.temperature(temperature);
                if (maxTokens != null) b.maxTokens(maxTokens);
                return b;
            });
        }
    },
    MISTRAL("Mistral") {
        @Override
        GenerativeProvider build(String model, Float temperature, Integer maxTokens) {
            return GenerativeProvider.mistral(b -> {
                if (model != null) b.model(model);
                if (temperature != null) b.temperature(temperature);
                if (maxTokens != null) b.maxTokens(maxTokens);
                return b;
            });
        }
    },
    OLLAMA("Ollama") {
        @Override
        GenerativeProvider build(String model, Float temperature, Integer maxTokens) {
            return GenerativeProvider.ollama(b -> {
                if (model != null) b.model(model);
                if (temperature != null) b.temperature(temperature);
                return b;
            });
        }
    },
    ANYSCALE("Anyscale") {
        @Override
        GenerativeProvider build(String model, Float temperature, Integer maxTokens) {
            return GenerativeProvider.anyscale(b -> {
                if (model != null) b.model(model);
                if (temperature != null) b.temperature(temperature);
                return b;
            });
        }
    },
    DATABRICKS("Databricks") {
        @Override
        GenerativeProvider build(String model, Float temperature, Integer maxTokens) {
            return GenerativeProvider.databricks(b -> {
                if (model != null) b.model(model);
                if (temperature != null) b.temperature(temperature);
                if (maxTokens != null) b.maxTokens(maxTokens);
                return b;
            });
        }
    },
    DEEPSEEK("Deepseek") {
        @Override
        GenerativeProvider build(String model, Float temperature, Integer maxTokens) {
            return GenerativeProvider.deepseek(b -> {
                if (temperature != null) b.temperature(temperature);
                if (maxTokens != null) b.maxTokens(maxTokens);
                return b;
            });
        }
    },
    FRIENDLIAI("FriendliAI") {
        @Override
        GenerativeProvider build(String model, Float temperature, Integer maxTokens) {
            return GenerativeProvider.friendliai(b -> {
                if (model != null) b.model(model);
                if (temperature != null) b.temperature(temperature);
                if (maxTokens != null) b.maxTokens(maxTokens);
                return b;
            });
        }
    },
    NVIDIA("NVIDIA") {
        @Override
        GenerativeProvider build(String model, Float temperature, Integer maxTokens) {
            return GenerativeProvider.nvidia(b -> {
                if (model != null) b.model(model);
                if (temperature != null) b.temperature(temperature);
                if (maxTokens != null) b.maxTokens(maxTokens);
                return b;
            });
        }
    },
    XAI("xAI") {
        @Override
        GenerativeProvider build(String model, Float temperature, Integer maxTokens) {
            return GenerativeProvider.xai(b -> {
                if (model != null) b.model(model);
                if (temperature != null) b.temperature(temperature);
                if (maxTokens != null) b.maxTokens(maxTokens);
                return b;
            });
        }
    };

    private final String label;

    WeaviateGenerativeProvider(String label) {
        this.label = label;
    }

    @NotNull
    public String getLabel() {
        return label;
    }

    /** The client provider carrying whatever of the common fields this provider supports. */
    @NotNull
    public GenerativeProvider toClientProvider(
        @Nullable String model,
        @Nullable Float temperature,
        @Nullable Integer maxTokens
    ) {
        return build(model, temperature, maxTokens);
    }

    abstract GenerativeProvider build(String model, Float temperature, Integer maxTokens);
}
