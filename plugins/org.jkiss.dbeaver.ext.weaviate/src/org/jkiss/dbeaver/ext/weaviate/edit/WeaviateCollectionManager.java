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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.edit.DBECommand;
import org.jkiss.dbeaver.model.edit.DBECommandContext;
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

import java.io.IOException;
import java.util.Map;

public class WeaviateCollectionManager extends AbstractObjectManager<WeaviateCollection>
    implements DBEObjectMaker<WeaviateCollection, WeaviateDataSource> {

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
        return false;
    }

    @Override
    public boolean canDeleteObject(@NotNull WeaviateCollection object) {
        return true;
    }

    @Override
    public WeaviateCollection createNewObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext commandContext,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) throws DBException {
        throw new DBException("Collection creation is not yet supported");
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
        if (action instanceof WeaviateAction wa) {
            wa.run();
        } else {
            super.executePersistAction(session, command, action);
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
                new WeaviateAction(
                    "Delete collection " + name,
                    javaCode,
                    () -> {
                        WeaviateDataSource ds = (WeaviateDataSource) collection.getDataSource();
                        try {
                            ds.getClient().collections.delete(name);
                        } catch (IOException e) {
                            throw new DBCException("Failed to delete Weaviate collection '" + name + "'", e);
                        }
                        ds.invalidateCollections();
                        DBUtils.fireObjectRemove(collection);
                    })
            };
        }

        private static String escape(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    /**
     * Persist action that carries Java client code in {@link #getScript()} for the SQL/script preview
     * and runs the actual operation through {@link WeaviateCollectionManager#executePersistAction}
     * so DBeaver does not try to feed the snippet to a SQL session.
     */
    private static class WeaviateAction implements DBEPersistAction {
        @FunctionalInterface
        interface Runner {
            void run() throws DBException;
        }

        private final String title;
        private final String script;
        private final Runner runner;

        WeaviateAction(@NotNull String title, @NotNull String script, @NotNull Runner runner) {
            this.title = title;
            this.script = script;
            this.runner = runner;
        }

        void run() throws DBException {
            runner.run();
        }

        @NotNull
        @Override
        public String getTitle() {
            return title;
        }

        @NotNull
        @Override
        public String getScript() {
            return script;
        }

        @Override
        public void beforeExecute(@NotNull DBCSession session) {
        }

        @Override
        public void afterExecute(@NotNull DBCSession session, @Nullable Throwable error) {
        }

        @NotNull
        @Override
        public ActionType getType() {
            return ActionType.NORMAL;
        }

        @Override
        public boolean isComplex() {
            return false;
        }
    }
}
