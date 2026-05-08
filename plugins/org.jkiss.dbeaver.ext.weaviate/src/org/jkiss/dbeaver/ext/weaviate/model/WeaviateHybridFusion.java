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

// Mirrors io.weaviate.client6.v1.api.collections.query.Hybrid$FusionType.
// Wrapped here so consumers (e.g. the UI bundle) don't need direct access to the
// shaded client classes packaged inside the model bundle's lib/.
public enum WeaviateHybridFusion {
    RANKED(Hybrid.FusionType.RANKED),
    RELATIVE_SCORE(Hybrid.FusionType.RELATIVE_SCORE);

    private final Hybrid.FusionType clientType;

    WeaviateHybridFusion(Hybrid.FusionType clientType) {
        this.clientType = clientType;
    }

    public Hybrid.FusionType toClientType() {
        return clientType;
    }
}
