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
package org.jkiss.dbeaver.ext.weaviate.edit;

import io.weaviate.client6.v1.api.WeaviateApiException;
import io.weaviate.client6.v1.api.collections.CollectionConfig;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateSchemaJson;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateSchemaRest;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.edit.DBECommand;
import org.jkiss.dbeaver.model.edit.DBECommandContext;
import org.jkiss.dbeaver.model.edit.DBEObjectConfigurator;
import org.jkiss.dbeaver.model.edit.DBEObjectMaker;
import org.jkiss.dbeaver.model.edit.DBEPersistAction;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.impl.edit.AbstractObjectManager;
import org.jkiss.dbeaver.model.impl.edit.DBECommandAbstract;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.cache.DBSObjectCache;
import org.jkiss.dbeaver.utils.GeneralUtils;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class WeaviateCollectionManager extends AbstractObjectManager<WeaviateCollection>
    implements DBEObjectMaker<WeaviateCollection, WeaviateDataSource> {

    private static final Log log = Log.getLog(WeaviateCollectionManager.class);

    /**
     * The server's own explanation of a refusal, which is the most useful thing we can show.
     * Falls back to the exception message when the body carried no error text.
     */
    @NotNull
    static String serverError(@NotNull WeaviateApiException e) {
        String error = e.getError();
        if (error != null && !error.isBlank()) {
            return error;
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return FEATURE_SAVE_IMMEDIATELY;
    }

    @Nullable
    @Override
    public DBSObjectCache<? extends DBSObject, WeaviateCollection> getObjectsCache(WeaviateCollection object) {
        return null;
    }

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return container instanceof WeaviateDataSource;
    }

    @Override
    public boolean canDeleteObject(@NotNull WeaviateCollection object) {
        return true;
    }

    /**
     * Build an unsaved collection seeded with a starter schema document.
     * <p>
     * Nothing is sent to the server here (per the {@code DBEObjectMaker} contract). The
     * configurator registered for {@link WeaviateCollection} then lets the user edit or replace
     * the JSON, and {@link CreateCommand} performs the actual create on save.
     * <p>
     * When creating from an existing collection ("duplicate"), the source definition is copied
     * verbatim apart from its name.
     */
    @Override
    public WeaviateCollection createNewObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext commandContext,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) throws DBException {
        if (!(container instanceof WeaviateDataSource dataSource)) {
            throw new DBException("Collections can only be created on a Weaviate connection");
        }
        String name = makeUniqueName(monitor, dataSource);
        WeaviateCollection collection = WeaviateCollection.createNew(dataSource, name);
        collection.setPendingSchemaJson(
            copyFrom instanceof WeaviateCollection source
                ? WeaviateSchemaJson.withCollectionName(source.toSchemaJson(), name)
                : WeaviateSchemaJson.newTemplate(name));

        // Ask the user for the definition. Unlike SQLObjectEditor, AbstractObjectManager does not
        // run the configurator for us, so it has to be invoked explicitly - otherwise the
        // collection would be created straight from the starter template with no dialog at all.
        DBEObjectConfigurator<WeaviateCollection> configurator =
            GeneralUtils.adapt(collection, DBEObjectConfigurator.class);
        if (configurator != null) {
            collection = configurator.configureObject(monitor, commandContext, container, collection, options);
            if (collection == null) {
                // Cancelled: return without queueing a command so nothing is sent to the server.
                return null;
            }
        }
        // Re-read the name: the definition the user supplied decides it, not the placeholder above.
        String finalName = WeaviateSchemaJson.readCollectionName(collection.getPendingSchemaJson());
        if (finalName != null) {
            collection.rename(finalName);
        }

        commandContext.addCommand(new CreateCommand(collection), null, true);
        return collection;
    }

    /**
     * A default name that does not collide with an existing collection, so the create does not
     * fail on the server for a reason the user has not been shown yet.
     */
    @NotNull
    private static String makeUniqueName(
        @NotNull DBRProgressMonitor monitor,
        @NotNull WeaviateDataSource dataSource
    ) {
        Set<String> taken = new HashSet<>();
        try {
            for (WeaviateCollection existing : dataSource.getCollections(monitor)) {
                taken.add(existing.getName());
            }
        } catch (DBException e) {
            // Not fatal: fall back to the base name and let the server reject a duplicate.
            log.debug("Cannot list Weaviate collections to pick a unique name", e);
        }
        String base = "NewCollection";
        if (!taken.contains(base)) {
            return base;
        }
        for (int i = 1; ; i++) {
            String candidate = base + i;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
    }

    @Override
    public void deleteObject(
        @NotNull DBECommandContext commandContext,
        @NotNull WeaviateCollection object,
        @NotNull Map<String, Object> options
    ) {
        commandContext.addCommand(new DeleteCommand(object), null, true);
    }

    @Override
    public void executePersistAction(
        DBCSession session,
        DBECommand<WeaviateCollection> command,
        DBEPersistAction action
    ) throws DBException {
        if (action instanceof WeaviateEditAction wa) {
            wa.run();
        } else {
            super.executePersistAction(session, command, action);
        }
    }

    private static class CreateCommand extends DBECommandAbstract<WeaviateCollection> {
        CreateCommand(WeaviateCollection collection) {
            super(collection, "Create Weaviate collection " + collection.getName());
        }

        @NotNull
        @Override
        public DBEPersistAction[] getPersistActions(
            @NotNull DBRProgressMonitor monitor,
            @NotNull DBCExecutionContext executionContext,
            @NotNull Map<String, Object> options
        ) {
            WeaviateCollection collection = getObject();
            String json = collection.getPendingSchemaJson();
            // Shown in the script preview. Mirrors what actually happens: the definition is POSTed
            // to /v1/schema exactly as written, not routed through the client's object model.
            String javaCode = "// POST the collection definition to Weaviate, verbatim\n"
                + "curl -X POST \"$WEAVIATE_URL/v1/schema\" \\\n"
                + "  -H 'Content-Type: application/json' \\\n"
                + "  -d '\n"
                + indent(json)
                + "'";
            return new DBEPersistAction[]{
                new WeaviateEditAction(
                    "Create collection " + collection.getName(),
                    javaCode,
                    () -> {
                        WeaviateDataSource ds = (WeaviateDataSource) collection.getDataSource();
                        String name = collection.getName();
                        // Sent byte-for-byte. Going through the client's object model would rewrite
                        // the document (dropping unknown fields, injecting defaults) and would make
                        // configurations it cannot represent impossible to create.
                        WeaviateSchemaRest.createCollection(ds, json);

                        // The collection now exists. Everything below is presentation only, so a
                        // failure here must not be reported as a failed create - that would leave
                        // the user believing nothing happened while the collection is really there.
                        CollectionConfig stored = null;
                        try {
                            stored = ds.getClient().collections.getConfig(name).orElse(null);
                        } catch (IOException | RuntimeException e) {
                            // RuntimeException is deliberate: the client's Gson adapters can throw
                            // NullPointerException on configurations they cannot map back (e.g. a
                            // dynamic vector index), and that must not sink a successful create.
                            log.warn("Collection '" + name + "' was created, but reading its stored"
                                + " definition back failed; the navigator will show a minimal entry"
                                + " until the connection is refreshed", e);
                        }
                        if (stored == null) {
                            stored = CollectionConfig.of(name);
                        }
                        // Promote this very instance instead of reloading the collection list:
                        // the navigator resolves the new object's node by identity.
                        collection.markPersisted(stored);
                        ds.addCollection(collection);
                        DBUtils.fireObjectAdd(collection, options);
                    })
            };
        }

        private static String indent(@Nullable String json) {
            if (json == null || json.isEmpty()) {
                return "";
            }
            return json.lines().map(l -> "        " + l).collect(Collectors.joining("\n", "", "\n"));
        }
    }

    private static class DeleteCommand extends DBECommandAbstract<WeaviateCollection> {
        DeleteCommand(WeaviateCollection collection) {
            super(collection, "Delete Weaviate collection " + collection.getName());
        }

        @NotNull
        @Override
        public DBEPersistAction[] getPersistActions(
            @NotNull DBRProgressMonitor monitor,
            @NotNull DBCExecutionContext executionContext,
            @NotNull Map<String, Object> options
        ) {
            WeaviateCollection collection = getObject();
            String name = collection.getName();
            String javaCode = "// Delete a Weaviate collection using the Java client v6\n" +
                "client.collections.delete(\"" + escape(name) + "\");";
            return new DBEPersistAction[]{
                new WeaviateEditAction(
                    "Delete collection " + name,
                    javaCode,
                    () -> {
                        WeaviateDataSource ds = (WeaviateDataSource) collection.getDataSource();
                        try {
                            ds.getClient().collections.delete(name);
                        } catch (WeaviateApiException e) {
                            throw new DBCException(
                                "Weaviate refused to delete '" + name + "': " + serverError(e), e);
                        } catch (IOException | RuntimeException e) {
                            throw new DBCException("Failed to delete Weaviate collection '" + name + "'", e);
                        }
                        ds.invalidateCollections();
                        // Weaviate leaves an alias behind when its target is dropped, so the
                        // Aliases folder now holds one that points at nothing. Dropping the cache
                        // is what lets it be re-read and marked as dangling rather than going on
                        // claiming a collection that has just gone.
                        ds.resetAliasCache();
                        DBUtils.fireObjectRemove(collection);
                    })
            };
        }

        private static String escape(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

}
