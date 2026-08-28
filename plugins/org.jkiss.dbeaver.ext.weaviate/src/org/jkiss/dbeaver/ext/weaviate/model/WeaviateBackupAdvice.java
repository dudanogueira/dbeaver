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
 * The row that appears under Backups when the server has no backup module at all.
 * <p>
 * A folder with four locked backends under it and nothing else is accurate but unhelpful: it says
 * what is not available without saying that anything can be done about it. This row names the
 * setting.
 * <p>
 * It is a node rather than a tooltip or a folder description because those are only found by
 * someone already looking for them, and the person who needs this sentence is the one who
 * expected backups to be here and found nothing.
 */
public class WeaviateBackupAdvice extends WeaviateBackupEntry implements DBPImageProvider, DBPToolTipObject {

    private static final String HEADLINE = "No backup module is enabled on this server";
    private static final String DETAIL =
        "Set ENABLE_MODULES=backup-filesystem (or backup-s3, backup-gcs, backup-azure) and restart.";

    public WeaviateBackupAdvice(@NotNull WeaviateDataSource dataSource) {
        super(dataSource);
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return HEADLINE;
    }

    @Nullable
    @Override
    @Property(viewable = true, order = 2)
    public String getDescription() {
        return DETAIL;
    }

    @Nullable
    @Override
    public String getObjectToolTip() {
        return DETAIL;
    }

    @NotNull
    @Override
    public DBPImage getObjectImage() {
        return DBIcon.TYPE_UNKNOWN;
    }
}
