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

/**
 * Which way a boost rewards a document.
 * <p>
 * The client offers five; three are here. {@code Boost.filter} needs a whole {@code Filter} built
 * inside the boost, and {@code Boost.blend} needs a list of boosts to combine -- both are nested
 * composites rather than another field, so they want a different control than a row of inputs and
 * are left for their own change.
 */
public enum WeaviateBoostKind {

    /** Rank by a numeric property's own value, optionally flattened by a modifier. */
    PROPERTY_VALUE("By property value", false),
    /** Reward documents whose number sits near {@code origin}, falling off over {@code scale}. */
    NUMERIC_DECAY("Near a number", true),
    /**
     * The same, over time. Needs a {@code date} property: the server refuses any other type with
     * <em>boost condition[0] time_decay: property ... </em>, naming the offender.
     */
    TIME_DECAY("Near a date", true);

    private final String label;
    private final boolean decay;

    WeaviateBoostKind(@NotNull String label, boolean decay) {
        this.label = label;
        this.decay = decay;
    }

    @NotNull
    public String getLabel() {
        return label;
    }

    /** Whether this kind reads origin, scale, offset, curve and decay. */
    public boolean isDecay() {
        return decay;
    }

    @Nullable
    public static WeaviateBoostKind fromName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (WeaviateBoostKind kind : values()) {
            if (kind.name().equalsIgnoreCase(name)) {
                return kind;
            }
        }
        return null;
    }
}
