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

import io.weaviate.client6.v1.api.WeaviateApiException;
import org.jkiss.code.NotNull;

/**
 * The clearest sentence available for a failed write.
 * <p>
 * Weaviate's refusals are the useful part of a failed edit -- <em>invalid text property 'title':
 * not a string, but json.Number</em> says exactly what to change, while the exception's own
 * message says only that a request failed. Shared by the write batches so all three report a
 * refusal the same way.
 */
final class WeaviateWriteErrors {

    private WeaviateWriteErrors() {
    }

    @NotNull
    static String describe(@NotNull Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof WeaviateApiException api) {
                String error = api.getError();
                if (error != null && !error.isBlank()) {
                    return error;
                }
            }
            if (cause.getCause() == null) {
                return cause.getMessage() == null
                    ? cause.getClass().getSimpleName()
                    : cause.getMessage();
            }
        }
        return e.getClass().getSimpleName();
    }
}
