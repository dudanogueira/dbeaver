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
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The runtime generative providers the Query panel offers, against the client factories they
 * stand for.
 */
public class WeaviateGenerativeProviderTest extends DBeaverUnitTest {

    /**
     * Every constant must build, with and without the common fields: a provider whose factory
     * throws (or whose builder rejects a field) would surface only at query time, in front of
     * the user.
     */
    @Test
    public void everyProviderBuildsWithAndWithoutCommonFields() {
        for (WeaviateGenerativeProvider provider : WeaviateGenerativeProvider.values()) {
            GenerativeProvider bare = provider.toClientProvider(null, null, null);
            GenerativeProvider full = provider.toClientProvider("some-model", 0.7f, 256);
            Assertions.assertNotNull(bare, provider + " built nothing");
            Assertions.assertNotNull(full, provider + " built nothing with fields set");
        }
    }

    /**
     * The fields have to actually reach the request. Providers differ in which they support --
     * the enum skips what a builder lacks -- so the check is the coarse one that holds for all:
     * a provider built with fields differs from one built without.
     */
    @Test
    public void commonFieldsChangeTheRequest() {
        for (WeaviateGenerativeProvider provider : WeaviateGenerativeProvider.values()) {
            GenerativeProvider bare = provider.toClientProvider(null, null, null);
            GenerativeProvider full = provider.toClientProvider("some-model", 0.7f, 256);
            Assertions.assertNotEquals(bare, full,
                provider + " ignores model, temperature and max tokens alike");
        }
    }

    @Test
    public void everyProviderHasAReadableLabel() {
        for (WeaviateGenerativeProvider provider : WeaviateGenerativeProvider.values()) {
            Assertions.assertFalse(provider.getLabel().isBlank(), provider + " has no label");
            Assertions.assertFalse(provider.getLabel().contains("_"),
                provider + " shows its raw enum name");
        }
    }
}
