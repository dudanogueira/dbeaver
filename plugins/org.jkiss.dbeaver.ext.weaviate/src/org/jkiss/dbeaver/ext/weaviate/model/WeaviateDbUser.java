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
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPImage;
import org.jkiss.dbeaver.model.DBPImageProvider;
import org.jkiss.dbeaver.model.DBPStatefulObject;
import org.jkiss.dbeaver.model.DBPToolTipObject;
import org.jkiss.dbeaver.model.meta.Association;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectState;

import java.util.ArrayList;
import java.util.List;

/**
 * A database user, as a navigator node.
 */
public class WeaviateDbUser implements DBSObject, DBPImageProvider, DBPToolTipObject, DBPStatefulObject {

    /** Deactivated: the account exists and its key will not authenticate. */
    private static final DBSObjectState STATE_INACTIVE =
        new DBSObjectState("Deactivated", DBIcon.OVER_ERROR);
    /** Declared in the server's environment, so the API cannot change it. */
    private static final DBSObjectState STATE_ENV =
        new DBSObjectState("From the environment", DBIcon.OVER_LOCK);

    private final WeaviateDataSource dataSource;
    private final WeaviateRbacRest.DbUserInfo user;

    public WeaviateDbUser(
        @NotNull WeaviateDataSource dataSource, @NotNull WeaviateRbacRest.DbUserInfo user
    ) {
        this.dataSource = dataSource;
        this.user = user;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return user.userId();
    }

    @Nullable
    @Property(viewable = true, order = 2)
    public String getUserType() {
        return user.dbUserType();
    }

    @Property(viewable = true, order = 3)
    public boolean isActive() {
        return user.active();
    }

    @NotNull
    @Property(viewable = true, order = 4)
    public String getRoleNames() {
        return user.roles().isEmpty() ? "none" : String.join(", ", user.roles());
    }

    /** The first few characters of the key, which is all the server will ever show again. */
    @Nullable
    @Property(viewable = true, order = 5)
    public String getApiKeyFirstLetters() {
        return user.apiKeyFirstLetters();
    }

    @Nullable
    @Property(viewable = true, order = 6)
    public String getCreatedAt() {
        return user.createdAt();
    }

    @Nullable
    @Property(viewable = true, order = 7)
    public String getLastUsedAt() {
        return user.lastUsedAt();
    }

    @NotNull
    public WeaviateRbacRest.DbUserInfo getUserInfo() {
        return user;
    }

    /**
     * Whether this user came from {@code AUTHENTICATION_APIKEY_USERS} rather than the API.
     * <p>
     * Such a user cannot be edited, rotated or deleted through Weaviate, so every action that
     * would try is withheld. The bundled client cannot even tell the two apart -- its
     * {@code UserType} puts {@code @SerializedName("db")} on two constants, so one overwrites the
     * other -- which is another reason this plugin reads the field as text.
     */
    public boolean isEnvUser() {
        return user.isEnvUser();
    }

    @NotNull
    @Association
    public List<WeaviateRoleRef> getRoleRefs(@NotNull DBRProgressMonitor monitor) {
        List<WeaviateRoleRef> result = new ArrayList<>(user.roles().size());
        for (String role : user.roles()) {
            result.add(new WeaviateRoleRef(this, role));
        }
        result.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return result;
    }

    @Nullable
    @Override
    public String getDescription() {
        StringBuilder sb = new StringBuilder(user.active() ? "active" : "deactivated");
        if (isEnvUser()) {
            sb.append(", from the environment");
        }
        if (!user.roles().isEmpty()) {
            sb.append(", ").append(String.join(", ", user.roles()));
        }
        return sb.toString();
    }

    @Nullable
    @Override
    public String getObjectToolTip() {
        return isEnvUser()
            ? user.userId() + " is declared in the server's environment and cannot be changed here"
            : getDescription();
    }

    @NotNull
    @Override
    public DBPImage getObjectImage() {
        return DBIcon.TREE_USER;
    }

    @NotNull
    @Override
    public DBSObjectState getObjectState() {
        if (!user.active()) {
            return STATE_INACTIVE;
        }
        return isEnvUser() ? STATE_ENV : DBSObjectState.ACTIVE;
    }

    @Override
    public void refreshObjectState(@NotNull DBRProgressMonitor monitor) {
        // Carried by the snapshot this node was built from; the folder re-reads on refresh.
    }

    @NotNull
    @Override
    public DBSObject getParentObject() {
        return dataSource;
    }

    @NotNull
    @Override
    public DBPDataSource getDataSource() {
        return dataSource;
    }

    @Override
    public boolean isPersisted() {
        return true;
    }
}
