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
package org.jkiss.dbeaver.ext.weaviate.ui.query;

import org.eclipse.swt.widgets.Composite;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateQueryMode;

import java.util.List;

/**
 * What a piece of the Query panel is allowed to ask of the panel around it.
 * <p>
 * The narrowness is the point. Each section owns its own widgets and nothing else, and everything
 * it needs from outside comes through here -- so sections cannot reach into one another, and the
 * panel stays the only thing that knows the whole layout.
 * <p>
 * Note what is deliberately absent: there is no way to lay anything out. Height changes have to go
 * through the panel's own {@code reflow()}, which re-measures the scrolled content at its current
 * width; a section laying out only the composite it changed leaves every ancestor on a stale
 * preferred size, which is how a target row once drew over the Filters section. Keeping layout off
 * this interface makes that rule structural rather than a comment somebody has to remember.
 * <p>
 * Methods are added here as sections need them, not in advance.
 */
public interface WeaviateQueryPanelContext {

    /** The collection behind the current result set, or null when it is not a Weaviate one. */
    @Nullable
    WeaviateCollection currentCollection();

    /** Show a message in the panel's banner. */
    void showError(@Nullable String message);

    /** Close the banner and forget the error behind it. */
    void dismissBanner();

    /** Run the query as it currently stands, as if Run had been pressed. */
    void runQuery();

    /** The mode the panel is currently showing. */
    @NotNull
    WeaviateQueryMode currentMode();

    /** Property names of the current collection, or empty when there is none. */
    @NotNull
    List<String> currentPropertyNames();

    /**
     * Re-measure the scrolled content after something changed height.
     * <p>
     * The one way a section may affect layout beyond its own composite. It lays out the content,
     * recomputes the scroller's minimum size at the current client width, then lays out the parent.
     */
    void reflow();

    /**
     * Build a foldable section and return the client composite to fill.
     * <p>
     * Only here, never re-implemented: it seeds the stored expansion state before handing over the
     * persistence key. {@code setPersistenceKey} restores whatever is on file and an absent
     * setting reads as false, so a section created expanded would otherwise collapse itself the
     * instant it became persistent.
     */
    @NotNull
    Composite createSection(
        @NotNull Composite parent,
        @NotNull String title,
        @NotNull String persistKey,
        int columns,
        boolean expandedByDefault);

    /** Put a count in a section's title, or drop it when the count is zero. */
    void setSectionCount(@Nullable Composite client, @NotNull String title, int count);
}
