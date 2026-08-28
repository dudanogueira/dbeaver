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

/**
 * One tenant of a multi-tenant collection: its name and what state it is in.
 * <p>
 * Plain JDK on purpose. The client's own {@code Tenant} lives in the shaded jar, which the UI
 * bundle's classloader cannot see, so this is what crosses into a dialog.
 */
public record WeaviateTenant(@NotNull String name, @NotNull WeaviateTenantStatus status) {

    public WeaviateTenant {
        if (name.isBlank()) {
            // A nameless tenant cannot be selected, activated or deactivated, so it is not
            // something the rest of the plugin should have to guard against.
            throw new IllegalArgumentException("Tenant name is required");
        }
    }

    public boolean isActive() {
        return status == WeaviateTenantStatus.ACTIVE;
    }
}
