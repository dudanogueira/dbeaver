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

import io.weaviate.client6.v1.api.collections.query.Hybrid;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

// Mirrors io.weaviate.client6.v1.api.collections.query.Hybrid$FusionType.
// Wrapped here so consumers (e.g. the UI bundle) don't need direct access to the
// shaded client classes packaged inside the model bundle's lib/.
public enum WeaviateHybridFusion {
    /** Adds the inverted ranks of the two searches. Weaviate's original algorithm. */
    RANKED(Hybrid.FusionType.RANKED, "Ranked"),
    /** Scales each search's scores to 0-1 and adds them. */
    RELATIVE_SCORE(Hybrid.FusionType.RELATIVE_SCORE, "Relative score");

    /**
     * What the server does when a hybrid query names no fusion type.
     * <p>
     * Relative score since 1.24; ranked before that. The version history does not matter in
     * practice here -- the bundled client refuses to talk to anything below 1.32 -- which is what
     * makes it safe for the panel to preselect this rather than leaving the choice unsent. The
     * point of preselecting is that the panel then shows what the query will actually do, instead
     * of a blank that means "whatever this server happens to prefer".
     */
    public static final WeaviateHybridFusion SERVER_DEFAULT = RELATIVE_SCORE;

    private final Hybrid.FusionType clientType;
    private final String label;

    WeaviateHybridFusion(Hybrid.FusionType clientType, String label) {
        this.clientType = clientType;
        this.label = label;
    }

    public Hybrid.FusionType toClientType() {
        return clientType;
    }

    /** For the combo. {@code RELATIVE_SCORE} is not a thing to show anyone. */
    @NotNull
    public String getLabel() {
        return this == SERVER_DEFAULT ? label + " (server default)" : label;
    }

    /** The constant behind a label from {@link #getLabel()}, or null. */
    @Nullable
    public static WeaviateHybridFusion byLabel(@Nullable String label) {
        for (WeaviateHybridFusion fusion : values()) {
            if (fusion.getLabel().equals(label)) {
                return fusion;
            }
        }
        return null;
    }
}
