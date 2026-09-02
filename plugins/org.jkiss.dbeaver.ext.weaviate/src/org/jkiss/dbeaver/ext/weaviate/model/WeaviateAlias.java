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
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPImage;
import org.jkiss.dbeaver.model.DBPImageProvider;
import org.jkiss.dbeaver.model.DBPStatefulObject;
import org.jkiss.dbeaver.model.DBPToolTipObject;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.model.DBIconComposite;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectState;

import java.util.ArrayList;
import java.util.List;

/**
 * An alternate name for a collection, as a navigator node.
 * <p>
 * An alias exists so that a reindex can be made atomic from the reader's side: build
 * {@code ProductsV4}, repoint {@code products} at it, drop {@code ProductsV3}, and nothing
 * querying by the alias notices. The server keeps them in a flat, connection-wide list; this node
 * appears twice in the tree, once under that list and once under the collection it points at.
 * <p>
 * <b>The label is the alias name alone.</b> The target is a property, not part of the name, and
 * that is deliberate: {@code DBNDatabaseNode} reuses a tree node only when class and unique name
 * both match, so a label carrying the target would replace the row on every repoint and collapse
 * whatever was expanded. Aliases cannot be renamed -- the server offers no such endpoint -- so a
 * name-only label is stable for the whole life of the object.
 */
public class WeaviateAlias implements DBSObject, DBPImageProvider, DBPToolTipObject, DBPStatefulObject {

    private final WeaviateDataSource dataSource;
    private final String name;
    private final String targetCollection;

    public WeaviateAlias(
        @NotNull WeaviateDataSource dataSource,
        @NotNull String name,
        @NotNull String targetCollection
    ) {
        this.dataSource = dataSource;
        this.name = name;
        this.targetCollection = targetCollection;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return name;
    }

    /** The collection this name resolves to. */
    @NotNull
    @Property(viewable = true, order = 2)
    public String getTargetCollection() {
        return targetCollection;
    }

    /**
     * Whether the target no longer exists.
     * <p>
     * Weaviate leaves an alias behind when its collection is dropped, and querying through it then
     * fails with a message about a class nobody in the tree can see. So the tree says it instead.
     * <p>
     * Answered from the collection list already in memory and never by fetching one: this is read
     * while the navigator paints labels. A list that has not been loaded yet means "cannot tell",
     * which is reported as <em>not</em> dangling -- the same direction the version gate guesses in,
     * for the same reason. A missing warning is a smaller failure than one invented from ignorance.
     */
    public boolean isDangling() {
        List<WeaviateCollection> known = dataSource.getLoadedCollections();
        if (known == null) {
            return false;
        }
        List<String> names = new ArrayList<>(known.size());
        for (WeaviateCollection collection : known) {
            names.add(collection.getName());
        }
        return targetMissing(names, targetCollection);
    }

    /**
     * The dangling rule itself, over names -- the part worth testing without a server.
     *
     * @param knownCollections every collection the connection has loaded, or null when it has
     *                         loaded none yet, which is answered as "not missing"
     */
    static boolean targetMissing(@Nullable List<String> knownCollections, @NotNull String target) {
        if (knownCollections == null) {
            return false;
        }
        for (String name : knownCollections) {
            if (pointsAt(target, name)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether an alias with this target belongs to {@code collection}.
     * <p>
     * Compared without case. Weaviate capitalises a collection name on the way in -- create
     * {@code products} and the schema comes back saying {@code Products} -- so an alias created
     * against the name someone typed can carry a spelling the collection list never uses.
     */
    static boolean pointsAt(@NotNull String target, @NotNull String collection) {
        return target.equalsIgnoreCase(collection);
    }

    /**
     * A synonym, which is what an alias is, greyed out when its target is gone.
     * <p>
     * The error marker is not added here -- {@link #getObjectState()} supplies it and the platform
     * composes the two. Adding both would stack two overlays in the same corner.
     */
    @NotNull
    @Override
    public DBPImage getObjectImage() {
        return isDangling()
            ? new DBIconComposite(DBIcon.TREE_SYNONYM, true, null, null, null, null)
            : DBIcon.TREE_SYNONYM;
    }

    /**
     * Only the broken case is marked.
     * <p>
     * Tenants mark both of their states because the pair reads faster than an absence on a mixed
     * list, where roughly half are inactive. Aliases are not like that: a dangling one is rare and
     * always a mistake, so a green tick on every healthy alias would be noise around the one row
     * worth noticing.
     */
    @NotNull
    @Override
    public DBSObjectState getObjectState() {
        return isDangling() ? DBSObjectState.INVALID : DBSObjectState.NORMAL;
    }

    @Override
    public void refreshObjectState(@NotNull DBRProgressMonitor monitor) {
        // Both the alias list and the collection list are rebuilt by a navigator refresh, and
        // this node is discarded with them, so there is nothing to re-read in place.
    }

    @Nullable
    @Override
    public String getObjectToolTip() {
        return isDangling()
            ? targetCollection + " (no such collection)"
            : "→ " + targetCollection;
    }

    @Nullable
    @Override
    @Property(viewable = true, order = 3)
    public String getDescription() {
        return isDangling() ? "Target collection " + targetCollection + " does not exist" : null;
    }

    /**
     * The connection, from both folders.
     * <p>
     * An alias belongs to the server, not to the collection it currently points at -- repointing
     * it must not look like moving it to a different parent. Where the node is shown is the tree
     * meta's business; this answers what it is.
     */
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
