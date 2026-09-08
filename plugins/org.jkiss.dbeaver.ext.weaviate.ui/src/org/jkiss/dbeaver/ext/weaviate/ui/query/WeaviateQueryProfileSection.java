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

import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateQueryProfile;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateQuerySpec;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;

/**
 * What the server did to answer the last query, per shard.
 * <p>
 * Read-only, and filled after each run rather than composed before one -- the same shape as the
 * generative section's grouped-result box, and for the same reason: a profile belongs to the whole
 * result set, so it has no row in the grid to live on.
 * <p>
 * Switched on by the checkbox at the top of this section. It used to live in Search options,
 * two sections away from the box it fills and from the explanation of what it does -- so the
 * section that says "not requested" had to name a control somewhere else for the reader to go and
 * find. Off, the box says so rather than sitting empty, because an empty box after a query that
 * ran looks like a failure.
 */
public class WeaviateQueryProfileSection {

    private static final int PROFILE_BOX_LINES = 10;

    private final WeaviateQueryPanelContext context;

    private Composite group;
    private Button enableCheck;
    private Text profileField;

    public WeaviateQueryProfileSection(@NotNull WeaviateQueryPanelContext context) {
        this.context = context;
    }

    public void createControls(@NotNull Composite parent) {
        group = context.createSection(
            parent, WeaviateUIMessages.query_profile, "queryProfile", 1, false);
        group.setToolTipText(WeaviateUIMessages.query_profile_tip);

        enableCheck = new Button(group, SWT.CHECK);
        enableCheck.setText(WeaviateUIMessages.query_profile_enable);
        enableCheck.setToolTipText(WeaviateUIMessages.query_profile_enable_tip);

        profileField = new Text(group, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL);
        profileField.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        WeaviateSectionWidgets.setVisibleLines(profileField, PROFILE_BOX_LINES);
    }

    /** Whether the next run should ask the server for a profile. */
    public boolean isEnabled() {
        return enableCheck != null && !enableCheck.isDisposed() && enableCheck.getSelection();
    }

    public void loadFrom(@NotNull WeaviateQuerySpec spec) {
        if (enableCheck == null || enableCheck.isDisposed()) {
            return;
        }
        enableCheck.setSelection(spec.isWithQueryProfile());
    }

    /** Re-read the profile the last run left on the collection. */
    public void refresh() {
        if (profileField == null || profileField.isDisposed()) {
            return;
        }
        WeaviateCollection collection = context.currentCollection();
        WeaviateQueryProfile profile = collection == null ? null : collection.getLastQueryProfile();
        profileField.setText(render(profile));
        context.setSectionCount(group, WeaviateUIMessages.query_profile,
            profile == null ? 0 : profile.shards().size());
    }

    @NotNull
    private String render(@Nullable WeaviateQueryProfile profile) {
        if (profile == null || profile.isEmpty()) {
            return WeaviateUIMessages.query_profile_none;
        }
        StringBuilder sb = new StringBuilder();
        int index = 0;
        for (WeaviateQueryProfile.Shard shard : profile.shards()) {
            index++;
            if (sb.length() > 0) {
                sb.append('\n');
            }
            // The client drops the shard name and the node, so the position is all there is to
            // name a block by. It still shows how unevenly the work was spread; it cannot say
            // which node was the slow one. See WeaviateQueryProfile.
            sb.append(shard.name() == null
                ? NLS.bind(WeaviateUIMessages.query_profile_shard_anonymous, index)
                : shard.name() + (shard.node() == null ? "" : "  on " + shard.node()));
            sb.append('\n');
            for (WeaviateQueryProfile.Search search : shard.searches()) {
                sb.append("  ").append(search.kind()).append('\n');
                for (WeaviateQueryProfile.Detail detail : search.details()) {
                    sb.append("    ")
                        .append(pad(detail.key()))
                        .append(detail.value())
                        .append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** Keys are short and of similar length, so a fixed column beats measuring them. */
    @NotNull
    private static String pad(@NotNull String key) {
        StringBuilder sb = new StringBuilder(key);
        while (sb.length() < 26) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
