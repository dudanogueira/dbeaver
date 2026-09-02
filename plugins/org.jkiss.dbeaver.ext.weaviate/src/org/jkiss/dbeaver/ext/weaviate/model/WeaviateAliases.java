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
import io.weaviate.client6.v1.api.alias.Alias;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Everything the connection does about aliases: listing them, caching their navigator nodes, and
 * creating, repointing and dropping them.
 * <p>
 * A collaborator rather than a utility class, for the reason {@link WeaviateTenancy} is one: it
 * owns state. Holding the cache and the operations that invalidate it in one object is the point
 * -- and it keeps {@link WeaviateDataSource}, which is already long and has taken a cache triple
 * from each of the last four features, from taking a fifth.
 * <p>
 * <b>This is the first area since tenants that stays on the bundled client.</b> Backups, RBAC and
 * replication each had to leave it. The alias namespace is worth the check: {@code Alias} is a
 * two-field record carrying exactly what the REST API sends, and there is no enum anywhere in it,
 * so the closed-vocabulary failure behind most of the other defects cannot happen here. Staying on
 * the client also means alias management works on OIDC connections, which no REST branch does --
 * the token is minted inside the client and has no public accessor.
 */
final class WeaviateAliases {

    private final WeaviateDataSource dataSource;

    /** Navigator nodes for the Aliases folders; dropped whenever an alias changes. */
    private volatile List<WeaviateAlias> aliases;

    WeaviateAliases(@NotNull WeaviateDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Every alias on the server, alphabetically. */
    @NotNull
    List<WeaviateAlias> list(@NotNull DBRProgressMonitor monitor) throws DBException {
        List<WeaviateAlias> known = aliases;
        if (known == null) {
            synchronized (this) {
                known = aliases;
                if (known == null) {
                    monitor.subTask("Read Weaviate aliases");
                    List<WeaviateAlias> loaded = new ArrayList<>();
                    for (Alias alias : fetch()) {
                        loaded.add(new WeaviateAlias(dataSource, alias.alias(), alias.collection()));
                    }
                    loaded.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                    aliases = loaded;
                    known = loaded;
                }
            }
        }
        return known;
    }

    /**
     * The aliases pointing at one collection, filtered here rather than by the server.
     * <p>
     * The client does offer a filter, but its builder method is called {@code collection(...)}
     * while the parameter it puts on the wire is {@code class}. A query parameter the server does
     * not recognise is ignored rather than refused, so if that name ever diverges the filter stops
     * filtering and says nothing -- a full list would arrive under a collection's own folder,
     * looking entirely plausible. Filtering the one cached list cannot fail that way, and it also
     * means the two folders can never disagree about what exists.
     * <p>
     * The client's filter is not left unexamined: {@code WeaviateAliasLiveTest} calls it, which is
     * where a change in it should surface.
     */
    @NotNull
    List<WeaviateAlias> forCollection(
        @NotNull DBRProgressMonitor monitor,
        @NotNull String collection
    ) throws DBException {
        List<WeaviateAlias> result = new ArrayList<>();
        for (WeaviateAlias alias : list(monitor)) {
            if (WeaviateAlias.pointsAt(alias.getTargetCollection(), collection)) {
                result.add(alias);
            }
        }
        return result;
    }

    /**
     * Point a new name at a collection.
     * <p>
     * The arguments are alias-first here, matching {@code get}, {@code update} and {@code delete}
     * -- and matching how the rest of this plugin names things. The client's own {@code create} is
     * the odd one out: it takes <b>the collection first</b>. Its parameter names are erased in the
     * shipped jar, so both read as {@code arg0} and the natural reading is wrong; the same trap as
     * {@code tokenize.forProperty}, and pinned the same way, by a live test. The transposition
     * happens once, on the next line.
     */
    void create(
        @NotNull DBRProgressMonitor monitor,
        @NotNull String alias,
        @NotNull String targetCollection
    ) throws DBException {
        monitor.subTask("Create alias " + alias);
        try {
            dataSource.getClient().alias.create(targetCollection, alias);
        } catch (IOException e) {
            throw new DBException("Cannot create alias " + alias + ": " + e.getMessage(), e);
        } catch (WeaviateApiException e) {
            // The server refuses a duplicate name, and a name already taken by a collection, with
            // its own explanation. The client discards the created alias it returns, so its
            // refusal is the only thing there is to show.
            throw new DBException("Cannot create alias " + alias + ": " + serverError(e), e);
        }
        reset();
    }

    /** Point an existing alias at a different collection. */
    void retarget(
        @NotNull DBRProgressMonitor monitor,
        @NotNull String alias,
        @NotNull String newTargetCollection
    ) throws DBException {
        monitor.subTask("Repoint alias " + alias);
        try {
            dataSource.getClient().alias.update(alias, newTargetCollection);
        } catch (IOException e) {
            throw new DBException("Cannot repoint alias " + alias + ": " + e.getMessage(), e);
        } catch (WeaviateApiException e) {
            // Unlike delete, this throws when the alias is gone: the client special-cases 404 for
            // the boolean endpoints only. Someone repointing an alias another session has just
            // dropped sees the server's 404 rather than a silent no-op, which is the better of
            // the two.
            throw new DBException("Cannot repoint alias " + alias + ": " + serverError(e), e);
        }
        reset();
    }

    /**
     * Drop an alias.
     *
     * @return false if the server had no such alias -- the client turns that 404 into a value
     *         rather than an exception, so it is reported rather than raised
     */
    boolean delete(@NotNull DBRProgressMonitor monitor, @NotNull String alias) throws DBException {
        monitor.subTask("Delete alias " + alias);
        try {
            return dataSource.getClient().alias.delete(alias);
        } catch (IOException e) {
            throw new DBException("Cannot delete alias " + alias + ": " + e.getMessage(), e);
        } catch (WeaviateApiException e) {
            throw new DBException("Cannot delete alias " + alias + ": " + serverError(e), e);
        } finally {
            reset();
        }
    }

    /** Forgets the alias list, so the next expansion asks the server again. */
    void reset() {
        synchronized (this) {
            aliases = null;
        }
    }

    /** Aliases already in memory, or null. For callers that must not fetch. */
    @Nullable
    List<WeaviateAlias> loaded() {
        return aliases;
    }

    /**
     * The raw list, never null.
     * <p>
     * The client deserializes the response and calls {@code aliases()} on it with no guard, so a
     * server answering {@code {}} or {@code {"aliases": null}} hands back a null List rather than
     * an empty one -- and the caller finds out at the first iteration, far from here.
     */
    @NotNull
    private List<Alias> fetch() throws DBException {
        try {
            return Objects.requireNonNullElse(dataSource.getClient().alias.list(), List.of());
        } catch (IOException e) {
            throw new DBException("Cannot list Weaviate aliases: " + e.getMessage(), e);
        } catch (WeaviateApiException e) {
            throw new DBException("Cannot list Weaviate aliases: " + serverError(e), e);
        }
    }

    /**
     * The server's own explanation of a refusal, which is the most useful thing to show.
     * <p>
     * The same six lines as {@code WeaviateCollectionManager.serverError}, repeated rather than
     * shared: that one is package-private in the {@code edit} package, and widening a helper's
     * visibility to reach across packages is a worse trade than a duplicate this small.
     */
    @NotNull
    private static String serverError(@NotNull WeaviateApiException e) {
        String error = e.getError();
        if (error != null && !error.isBlank()) {
            return error;
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
