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
import org.jkiss.dbeaver.model.edit.DBEPersistAction;
import org.jkiss.dbeaver.model.exec.DBCSession;

/**
 * One change to send, and the code to show for it.
 * <p>
 * Weaviate has no DDL, so there is no script to hand a SQL session -- but DBeaver's save flow is
 * built around one, and that flow is worth keeping: it is what lists every pending change before
 * anything is sent, offers to show what will be run, and gives Revert for free. So the action
 * carries a readable snippet in {@link #getScript()} for the preview and a lambda for the work,
 * and {@link WeaviateCollectionManager#executePersistAction} runs the lambda rather than letting
 * the platform try to execute the text.
 * <p>
 * Promoted out of {@code WeaviateCollectionManager} once a second command needed it. The script it
 * carries is not always the call being made: see {@link WeaviateConfigScriptNote}.
 */
class WeaviateEditAction implements DBEPersistAction {

    @FunctionalInterface
    interface Runner {
        void run() throws DBException;
    }

    private final String title;
    private final String script;
    private final Runner runner;

    WeaviateEditAction(@NotNull String title, @NotNull String script, @NotNull Runner runner) {
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
