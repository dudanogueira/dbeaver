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
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.model.DBPImage;
import org.jkiss.dbeaver.model.DBPImageProvider;
import org.jkiss.dbeaver.model.DBPToolTipObject;
import org.jkiss.dbeaver.model.meta.Property;

/**
 * The row that says why Security is empty.
 * <p>
 * Two different reasons, and they need different sentences because they have different fixes: the
 * server has RBAC switched off, or the connection cannot reach the RBAC endpoints at all because
 * it authenticates with OIDC and the token lives inside the client with no accessor.
 */
public class WeaviateRbacAdvice extends WeaviateSecurityEntry
    implements DBPImageProvider, DBPToolTipObject {

    private final String headline;
    private final String detail;

    private WeaviateRbacAdvice(
        @NotNull WeaviateDataSource dataSource, @NotNull String headline, @NotNull String detail
    ) {
        super(dataSource);
        this.headline = headline;
        this.detail = detail;
    }

    /** RBAC is not switched on, which is a setting someone can change. */
    @NotNull
    public static WeaviateRbacAdvice notEnabled(@NotNull WeaviateDataSource dataSource) {
        return new WeaviateRbacAdvice(dataSource,
            "Role-based access control is not enabled on this server",
            "Set AUTHORIZATION_RBAC_ENABLED=true and name at least one root user in "
                + "AUTHORIZATION_RBAC_ROOT_USERS, then restart.");
    }

    /**
     * The connection cannot sign a REST request.
     * <p>
     * Not a server problem and not fixable there: an OIDC connection mints its token inside the
     * client, which exposes no accessor, so the RBAC endpoints cannot be called. Falling back to
     * the client is not an option either -- its role parsing throws on any 1.38+ server.
     */
    @NotNull
    public static WeaviateRbacAdvice needsApiKey(@NotNull WeaviateDataSource dataSource) {
        return new WeaviateRbacAdvice(dataSource,
            "Security needs a connection that authenticates with an API key",
            "This connection signs in with a username and password, and the resulting token is "
                + "not reachable from here. Reconnect with an API key to manage roles and users.");
    }

    /** The server refused the read, which usually means the caller lacks read_roles. */
    @NotNull
    public static WeaviateRbacAdvice refused(
        @NotNull WeaviateDataSource dataSource, @NotNull String serverMessage
    ) {
        return new WeaviateRbacAdvice(dataSource,
            "This connection may not read the security configuration", serverMessage);
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return headline;
    }

    @Nullable
    @Override
    @Property(viewable = true, order = 2)
    public String getDescription() {
        return detail;
    }

    @Nullable
    @Override
    public String getObjectToolTip() {
        return detail;
    }

    @NotNull
    @Override
    public DBPImage getObjectImage() {
        return DBIcon.TYPE_UNKNOWN;
    }
}
