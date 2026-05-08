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

public enum WeaviateQueryMode {
    FETCH("Fetch"),
    BM25("BM25"),
    NEAR_TEXT("Near Text"),
    NEAR_VECTOR("Near Vector"),
    HYBRID("Hybrid");

    private final String label;

    WeaviateQueryMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
