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
package org.jkiss.dbeaver.ext.weaviate.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateModelHeaders;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateModelProvider;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.controls.CustomTableEditor;
import org.jkiss.dbeaver.ui.dialogs.connection.ConnectionPageAbstract;
import org.jkiss.utils.CommonUtils;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Connection tab for the model provider API keys sent as request headers.
 * <p>
 * A separate tab rather than another group on the main page: the main page already stacks a
 * connection-type selector, address fields and an auth block, and these keys are not needed to
 * establish a connection at all -- only for vectorizer, reranker and generative calls.
 */
public class WeaviateModelKeysPage extends ConnectionPageAbstract {

    private final Map<WeaviateModelProvider, Text> keyFields = new EnumMap<>(WeaviateModelProvider.class);
    private final Map<WeaviateModelProvider, Label> envHints = new EnumMap<>(WeaviateModelProvider.class);
    private final Map<WeaviateModelProvider, Label> providerLabels = new EnumMap<>(WeaviateModelProvider.class);
    private Table customTable;

    public WeaviateModelKeysPage() {
        setTitle(WeaviateUIMessages.model_keys_title);
        setDescription(WeaviateUIMessages.model_keys_description);
    }

    @Override
    public void createControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        root.setLayoutData(new GridData(GridData.FILL_BOTH));

        Composite providers = UIUtils.createTitledComposite(
            root, WeaviateUIMessages.model_keys_providers_group, 3, GridData.FILL_HORIZONTAL, 0);

        for (WeaviateModelProvider provider : WeaviateModelProvider.values()) {
            Label nameLabel = UIUtils.createControlLabel(providers, provider.getLabel());

            Text field = new Text(providers, SWT.BORDER | SWT.PASSWORD);
            field.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
            keyFields.put(provider, field);

            // Shows which variable a blank field falls back to, so the key actually in use is
            // never invisible -- the main risk of supporting an environment fallback at all.
            Label hint = new Label(providers, SWT.NONE);
            hint.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_BEGINNING));
            envHints.put(provider, hint);
            providerLabels.put(provider, nameLabel);

            field.addModifyListener(e -> updateEnvHint(provider));

            // Set the tooltip up front. It must not depend on loadSettings() running: this is a
            // sub-page, and the field previously carried a tooltip from the moment it existed.
            updateEnvHint(provider);
        }

        Composite custom = UIUtils.createTitledComposite(
            root, WeaviateUIMessages.model_keys_custom_group, 2, GridData.FILL_BOTH, 0);

        customTable = new Table(custom, SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI);
        customTable.setHeaderVisible(true);
        customTable.setLinesVisible(true);
        GridData tableGd = new GridData(GridData.FILL_BOTH);
        tableGd.heightHint = 120;
        customTable.setLayoutData(tableGd);
        UIUtils.createTableColumn(customTable, SWT.LEFT, WeaviateUIMessages.model_keys_header_column);
        UIUtils.createTableColumn(customTable, SWT.LEFT, WeaviateUIMessages.model_keys_value_column);

        new CustomTableEditor(customTable) {
            @Override
            protected Control createEditor(Table table, int index, TableItem item) {
                Text editor = new Text(table, SWT.BORDER);
                editor.setText(CommonUtils.notEmpty(item.getText(index)));
                return editor;
            }

            @Override
            protected void saveEditorValue(Control control, int index, TableItem item) {
                item.setText(index, ((Text) control).getText().trim());
                UIUtils.packColumns(customTable, true);
            }
        };

        Composite buttons = new Composite(custom, SWT.NONE);
        buttons.setLayout(new GridLayout(1, false));
        buttons.setLayoutData(new GridData(GridData.VERTICAL_ALIGN_BEGINNING));

        Button add = UIUtils.createPushButton(buttons, WeaviateUIMessages.model_keys_add, null);
        add.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        add.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                TableItem item = new TableItem(customTable, SWT.NONE);
                item.setText(0, "X-");
                customTable.setSelection(item);
                UIUtils.packColumns(customTable, true);
            }
        });

        Button remove = UIUtils.createPushButton(buttons, WeaviateUIMessages.model_keys_remove, null);
        remove.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        remove.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                customTable.remove(customTable.getSelectionIndices());
            }
        });

        setControl(root);
    }

    private void updateEnvHint(WeaviateModelProvider provider) {
        Label hint = envHints.get(provider);
        Text field = keyFields.get(provider);
        if (hint == null || hint.isDisposed() || field == null || field.isDisposed()) {
            return;
        }
        boolean empty = CommonUtils.isEmpty(field.getText());
        String activeEnv = empty ? WeaviateModelHeaders.envSourceFor(provider) : null;

        hint.setText(activeEnv == null
            ? ""
            : MessageFormat.format(WeaviateUIMessages.model_keys_env_hint, shellName(activeEnv)));

        String tooltip = buildTooltip(provider, empty, activeEnv);
        field.setToolTipText(tooltip);
        hint.setToolTipText(tooltip);
        Label nameLabel = providerLabels.get(provider);
        if (nameLabel != null && !nameLabel.isDisposed()) {
            nameLabel.setToolTipText(tooltip);
        }
        hint.getParent().layout();
    }

    /**
     * Explain both what the field does and what happens when it is left blank.
     * <p>
     * The inline hint can only appear when a variable is actually set in this process, so
     * without this the fallback is invisible to anyone who has not exported one -- which is
     * precisely the person who needs to be told the names.
     */
    @NotNull
    private static String buildTooltip(
        @NotNull WeaviateModelProvider provider,
        boolean empty,
        @Nullable String activeEnv
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append(MessageFormat.format(
            WeaviateUIMessages.model_keys_tooltip_header, provider.getHeader()));

        String names = provider.getEnvVars().stream()
            .map(WeaviateModelKeysPage::shellName)
            .collect(Collectors.joining(", "));
        sb.append('\n').append(MessageFormat.format(WeaviateUIMessages.model_keys_tooltip_env, names));

        if (empty) {
            sb.append('\n').append(activeEnv != null
                ? MessageFormat.format(
                    WeaviateUIMessages.model_keys_tooltip_env_active, shellName(activeEnv))
                : WeaviateUIMessages.model_keys_tooltip_env_none);
        }
        return sb.toString();
    }

    @NotNull
    private static String shellName(@NotNull String envVar) {
        return "$" + envVar;
    }

    @Override
    public boolean isComplete() {
        return true;
    }

    @Override
    public void loadSettings() {
        super.loadSettings();
        DBPConnectionConfiguration cfg = site.getActiveDataSource().getConnectionConfiguration();

        for (Map.Entry<WeaviateModelProvider, Text> e : keyFields.entrySet()) {
            String stored = cfg.getAuthProperty(WeaviateModelHeaders.keyProperty(e.getKey()));
            e.getValue().setText(CommonUtils.notEmpty(stored));
            updateEnvHint(e.getKey());
        }

        customTable.removeAll();
        for (Map.Entry<String, String> e : cfg.getAuthProperties().entrySet()) {
            if (!e.getKey().startsWith(WeaviateModelHeaders.HEADER_PREFIX)) {
                continue;
            }
            TableItem item = new TableItem(customTable, SWT.NONE);
            item.setText(0, e.getKey().substring(WeaviateModelHeaders.HEADER_PREFIX.length()));
            item.setText(1, CommonUtils.notEmpty(e.getValue()));
        }
        UIUtils.packColumns(customTable, true);
    }

    @Override
    public void saveSettings(DBPDataSourceContainer dataSource) {
        super.saveSettings(dataSource);
        DBPConnectionConfiguration cfg = dataSource.getConnectionConfiguration();

        for (Map.Entry<WeaviateModelProvider, Text> e : keyFields.entrySet()) {
            String value = e.getValue().getText().trim();
            cfg.setAuthProperty(
                WeaviateModelHeaders.keyProperty(e.getKey()),
                value.isEmpty() ? null : value);
        }

        // Drop every stored custom header first: editing a row's name would otherwise leave the
        // old name behind and keep sending a header the user believes they renamed.
        List<String> stale = new ArrayList<>();
        for (String key : cfg.getAuthProperties().keySet()) {
            if (key.startsWith(WeaviateModelHeaders.HEADER_PREFIX)) {
                stale.add(key);
            }
        }
        for (String key : stale) {
            cfg.setAuthProperty(key, null);
        }

        for (TableItem item : customTable.getItems()) {
            String name = item.getText(0).trim();
            String value = item.getText(1).trim();
            if (!name.isEmpty() && !value.isEmpty()) {
                cfg.setAuthProperty(WeaviateModelHeaders.headerProperty(name), value);
            }
        }
    }
}
