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

import java.util.Locale;

/**
 * What state a tenant is in.
 * <p>
 * Mirrors the client's {@code TenantStatus}, which the UI bundle cannot see: its classloader has
 * no view of the shaded jar. Declared here so a tenant's state can cross into a dialog.
 * <p>
 * {@link #UNKNOWN} exists because this enum is resolved from a string the server sent. A Weaviate
 * newer than this plugin can name a state that did not exist when it was written, and a tenant
 * whose state cannot be named is still a tenant worth listing.
 */
public enum WeaviateTenantStatus {

    /** Loaded and serving reads and writes. */
    ACTIVE("Active"),
    /** On disk but not loaded. Reads and writes are refused until it is activated again. */
    INACTIVE("Inactive"),
    /** Moved to cloud storage. Needs an offload module on the server, so most servers never see it. */
    OFFLOADED("Offloaded"),
    /** On its way to cloud storage. Transitional -- the server is working, and will finish on its own. */
    OFFLOADING("Offloading"),
    /** On its way back from cloud storage. Transitional. */
    ONLOADING("Onloading"),
    /** A state this plugin does not know about, reported by a newer server. */
    UNKNOWN("Unknown");

    private final String label;

    WeaviateTenantStatus(@NotNull String label) {
        this.label = label;
    }

    @NotNull
    public String getLabel() {
        return label;
    }

    /**
     * Whether the server is mid-transition, in which case asking for a new state is pointless:
     * the answer is to wait, and the UI has nothing useful to offer.
     */
    public boolean isTransitional() {
        return this == OFFLOADING || this == ONLOADING;
    }

    /**
     * Whether this state can be changed by {@link WeaviateCollection#setTenantStatus}. Only the
     * two settled states are targets; offloading needs a module and the transitional ones are the
     * server's business.
     */
    public boolean isSettable() {
        return this == ACTIVE || this == INACTIVE;
    }

    /**
     * Resolves a status name, which is what the client's enum and the REST payload both carry.
     * <p>
     * Never throws and never returns null: an unrecognised name becomes {@link #UNKNOWN} rather
     * than failing the whole listing over one tenant.
     */
    @NotNull
    public static WeaviateTenantStatus fromName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return UNKNOWN;
        }
        // Weaviate also answers with the older HOT/COLD/FROZEN spelling of the same three states,
        // which is what a server before 1.26 sends and what its own docs still call them.
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "ACTIVE", "HOT" -> ACTIVE;
            case "INACTIVE", "COLD" -> INACTIVE;
            case "OFFLOADED", "FROZEN" -> OFFLOADED;
            case "OFFLOADING" -> OFFLOADING;
            case "ONLOADING" -> ONLOADING;
            default -> UNKNOWN;
        };
    }
}
