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
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.model.DBIconComposite;
import org.jkiss.dbeaver.model.DBPImage;
import org.jkiss.dbeaver.model.DBPImageProvider;
import org.jkiss.dbeaver.model.DBPToolTipObject;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.util.ArrayList;
import java.util.List;

/**
 * One of Weaviate's four backup backends, whether or not this server has it switched on.
 * <p>
 * All four are always listed. A backend that is missing is a module someone can enable, so
 * showing the four and marking which are available says more than showing only what happens to
 * work today -- and on a server with none at all, an empty folder would be indistinguishable from
 * a broken one.
 */
public class WeaviateBackupBackend extends WeaviateBackupEntry implements DBPImageProvider, DBPToolTipObject {

    /**
     * The backends Weaviate ships, shortest name first.
     * <p>
     * Each has a module name ({@code backup-filesystem}) and a short name ({@code filesystem}).
     * The server accepts either as the {@code {backend}} path segment -- verified against 1.39.0,
     * where {@code GET /v1/backups/filesystem} and {@code /v1/backups/backup-filesystem} both
     * answer 200 -- and the short one is what its own error messages use, so that is what travels.
     */
    public static final List<String> KNOWN = List.of("filesystem", "s3", "gcs", "azure");

    private final String id;
    private final boolean available;
    private final String destination;

    private volatile List<WeaviateBackupNode> backups;

    public WeaviateBackupBackend(
        @NotNull WeaviateDataSource dataSource,
        @NotNull String id,
        boolean available,
        @Nullable String destination
    ) {
        super(dataSource);
        this.id = id;
        this.available = available;
        this.destination = destination;
    }

    /** The module that provides this backend, e.g. {@code backup-filesystem}. */
    @NotNull
    public static String moduleNameFor(@NotNull String backendId) {
        return "backup-" + backendId;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return id;
    }

    /** Whether the module backing this backend is enabled on the server. */
    @Property(viewable = true, order = 2)
    public boolean isAvailable() {
        return available;
    }

    /**
     * Where this backend writes, as the server reports it -- the filesystem path or the bucket.
     * Null when the module is off, or when it reports nothing useful about itself.
     */
    @Nullable
    @Property(viewable = true, order = 3)
    public String getDestination() {
        return destination;
    }

    /**
     * Only the filesystem backend is node-local; the rest are external stores. Weaviate refuses a
     * filesystem backup on a multi-node cluster for exactly this reason
     * ("local filesystem backend is not viable for backing up a node cluster").
     */
    public boolean isLocal() {
        return "filesystem".equals(id);
    }

    @NotNull
    @Override
    public List<WeaviateBackupNode> getBackups(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (!available) {
            // Asking anyway answers 422 "did you enable the right module?", which is a round trip
            // to learn what the row already says.
            return List.of();
        }
        if (backups == null) {
            List<WeaviateBackup> listed = dataSource.listBackups(monitor, id);
            List<WeaviateBackupNode> nodes = new ArrayList<>(listed.size());
            for (WeaviateBackup backup : listed) {
                nodes.add(new WeaviateBackupNode(this, backup));
            }
            backups = nodes;
        }
        return backups;
    }

    /** Forgets the cached listing, so a refresh shows what the server now holds. */
    public void resetBackupCache() {
        backups = null;
    }

    /** The id the REST API expects in the {@code {backend}} path segment. */
    @NotNull
    public String getBackendId() {
        return id;
    }

    @Nullable
    @Override
    public String getObjectToolTip() {
        return available ? destination : "module not enabled";
    }

    /**
     * A greyed folder with a lock when the module is off -- the navigator's existing language for
     * something present but unavailable, and the same treatment an inactive tenant gets.
     */
    @NotNull
    @Override
    public DBPImage getObjectImage() {
        return available
            ? DBIcon.TREE_FOLDER
            : new DBIconComposite(DBIcon.TREE_FOLDER, true, null, null, null, DBIcon.OVER_LOCK);
    }
}
