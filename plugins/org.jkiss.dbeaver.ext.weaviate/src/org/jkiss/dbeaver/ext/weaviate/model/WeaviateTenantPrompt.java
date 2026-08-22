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
 * Lets the model ask the UI which tenant to read, without depending on it.
 * <p>
 * The question arises deep in the read path, but only the UI bundle can show a picker worth
 * using: the platform's generic choice dialog lays its options out as a row of buttons, which is
 * unreadable past a handful and hopeless at the thousands a real multi-tenant collection has.
 * The UI bundle registers a provider that opens a searchable dialog instead.
 */
public interface WeaviateTenantPrompt {

    /**
     * @return the chosen tenant, or null if the user dismissed the prompt
     */
    @Nullable
    String selectTenant(@NotNull String collectionName, @NotNull List<String> tenants, @Nullable String current);

    /**
     * Registered by the UI bundle. Volatile because the read path runs off the UI thread.
     */
    static void setProvider(@Nullable WeaviateTenantPrompt provider) {
        Holder.provider = provider;
    }

    @Nullable
    static WeaviateTenantPrompt getProvider() {
        return Holder.provider;
    }

    final class Holder {
        private static volatile WeaviateTenantPrompt provider;

        private Holder() {
        }
    }
}
