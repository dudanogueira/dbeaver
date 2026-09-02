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

import io.weaviate.client6.v1.api.collections.query.Boost;
import org.jkiss.code.NotNull;

/** The shape of a decay boost's falloff away from its origin. */
public enum WeaviateBoostCurve {

    EXPONENTIAL(Boost.Curve.EXPONENTIAL, "Exponential"),
    GAUSSIAN(Boost.Curve.GAUSSIAN, "Gaussian"),
    LINEAR(Boost.Curve.LINEAR, "Linear");

    private final Boost.Curve clientType;
    private final String label;

    WeaviateBoostCurve(@NotNull Boost.Curve clientType, @NotNull String label) {
        this.clientType = clientType;
        this.label = label;
    }

    @NotNull
    public String getLabel() {
        return label;
    }

    @NotNull
    public Boost.Curve toClientType() {
        return clientType;
    }
}
