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

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * What to generate alongside a query: a per-object single prompt, a grouped task over the whole
 * result set, or both, plus an optional runtime provider override.
 * <p>
 * Plain JDK types only -- this crosses into the UI bundle. Translated to the client's
 * {@code GenerativeTask} at dispatch inside {@code WeaviateCollection}. With no provider set the
 * server falls back to the collection's configured generative module, which is the ordinary case.
 */
public final class WeaviateGenerativeTask {

    private final String singlePrompt;
    private final String groupedTask;
    private final List<String> groupedProperties;
    private final WeaviateGenerativeProvider provider;
    private final String model;
    private final Float temperature;
    private final Integer maxTokens;
    private final boolean returnMetadata;

    private WeaviateGenerativeTask(Builder b) {
        boolean single = b.singlePrompt != null && !b.singlePrompt.isBlank();
        boolean grouped = b.groupedTask != null && !b.groupedTask.isBlank();
        if (!single && !grouped) {
            throw new IllegalArgumentException(
                "A generative task needs a single prompt, a grouped task, or both");
        }
        this.singlePrompt = single ? b.singlePrompt : null;
        this.groupedTask = grouped ? b.groupedTask : null;
        this.groupedProperties = b.groupedProperties == null
            ? Collections.emptyList() : List.copyOf(b.groupedProperties);
        this.provider = b.provider;
        this.model = blankToNull(b.model);
        this.temperature = b.temperature;
        this.maxTokens = b.maxTokens;
        this.returnMetadata = b.returnMetadata;
    }

    @Nullable
    private static String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }

    @NotNull
    public static Builder builder() {
        return new Builder();
    }

    /** Prompt run once per object, or null. May reference properties as {@code {property}}. */
    @Nullable
    public String getSinglePrompt() {
        return singlePrompt;
    }

    /** Prompt run once over the whole result set, or null. */
    @Nullable
    public String getGroupedTask() {
        return groupedTask;
    }

    /**
     * Properties handed to the grouped task; empty means all of them. Meaningless without a
     * grouped task -- the single prompt names its properties inline.
     */
    @NotNull
    public List<String> getGroupedProperties() {
        return groupedProperties;
    }

    /** Runtime provider override, or null to use the collection's generative module. */
    @Nullable
    public WeaviateGenerativeProvider getProvider() {
        return provider;
    }

    @Nullable
    public String getModel() {
        return model;
    }

    @Nullable
    public Float getTemperature() {
        return temperature;
    }

    @Nullable
    public Integer getMaxTokens() {
        return maxTokens;
    }

    /** Whether to ask the provider for usage metadata (token counts) alongside the text. */
    public boolean isReturnMetadata() {
        return returnMetadata;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WeaviateGenerativeTask other
            && Objects.equals(singlePrompt, other.singlePrompt)
            && Objects.equals(groupedTask, other.groupedTask)
            && groupedProperties.equals(other.groupedProperties)
            && provider == other.provider
            && Objects.equals(model, other.model)
            && Objects.equals(temperature, other.temperature)
            && Objects.equals(maxTokens, other.maxTokens)
            && returnMetadata == other.returnMetadata;
    }

    @Override
    public int hashCode() {
        return Objects.hash(singlePrompt, groupedTask, groupedProperties, provider,
            model, temperature, maxTokens, returnMetadata);
    }

    public static final class Builder {
        private String singlePrompt;
        private String groupedTask;
        private List<String> groupedProperties;
        private WeaviateGenerativeProvider provider;
        private String model;
        private Float temperature;
        private Integer maxTokens;
        private boolean returnMetadata;

        private Builder() {
        }

        public Builder singlePrompt(@Nullable String singlePrompt) {
            this.singlePrompt = singlePrompt;
            return this;
        }

        public Builder groupedTask(@Nullable String groupedTask) {
            this.groupedTask = groupedTask;
            return this;
        }

        public Builder groupedProperties(@Nullable List<String> groupedProperties) {
            this.groupedProperties = groupedProperties;
            return this;
        }

        public Builder provider(@Nullable WeaviateGenerativeProvider provider) {
            this.provider = provider;
            return this;
        }

        public Builder model(@Nullable String model) {
            this.model = model;
            return this;
        }

        public Builder temperature(@Nullable Float temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(@Nullable Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder returnMetadata(boolean returnMetadata) {
            this.returnMetadata = returnMetadata;
            return this;
        }

        @NotNull
        public WeaviateGenerativeTask build() {
            return new WeaviateGenerativeTask(this);
        }
    }
}
