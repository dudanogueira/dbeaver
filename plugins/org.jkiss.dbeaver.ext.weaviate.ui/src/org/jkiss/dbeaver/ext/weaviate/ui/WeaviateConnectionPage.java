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

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.jkiss.dbeaver.ext.weaviate.WeaviateConstants;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.dialogs.connection.ConnectionPageAbstract;
import org.jkiss.utils.CommonUtils;

import java.net.URL;

import java.util.Locale;

public class WeaviateConnectionPage extends ConnectionPageAbstract {

    private static final ImageDescriptor LOGO = createLogo();

    private static ImageDescriptor createLogo() {
        try {
            return ImageDescriptor.createFromURL(
                new URL("platform:/plugin/org.jkiss.dbeaver.ext.weaviate/icons/weaviate_big.png"));
        } catch (Exception e) {
            return null;
        }
    }

    private Button cloudRadio;
    private Button customRadio;

    private Composite stackParent;
    private StackLayout stack;
    private Composite cloudPanel;
    private Composite customPanel;

    private Text cloudUrlText;
    private Text cloudApiKeyText;

    private Combo schemeCombo;
    private Text hostText;
    private Text portText;
    private Text grpcHostText;
    private Text grpcPortText;
    private Combo authTypeCombo;
    private Composite authStackParent;
    private StackLayout authStack;
    private Composite authNonePanel;
    private Composite authApiKeyPanel;
    private Composite authUserPasswordPanel;
    private Text customApiKeyText;
    private Text usernameText;
    private Text passwordText;

    @Override
    public void createControl(Composite composite) {
        if (LOGO != null) {
            setImageDescriptor(LOGO);
        }
        Composite control = new Composite(composite, SWT.NONE);
        control.setLayout(new GridLayout(1, false));
        control.setLayoutData(new GridData(GridData.FILL_BOTH));

        SelectionListener buttonsUpdater = new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                site.updateButtons();
            }
        };

        // Connection type radio
        Composite typeGroup = UIUtils.createControlGroup(
            control, "Connection Type", 2, GridData.FILL_HORIZONTAL, 0);
        cloudRadio = new Button(typeGroup, SWT.RADIO);
        cloudRadio.setText("Weaviate Cloud");
        cloudRadio.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                if (cloudRadio.getSelection()) showCloudPanel();
                site.updateButtons();
            }
        });
        customRadio = new Button(typeGroup, SWT.RADIO);
        customRadio.setText("Custom (self-hosted / local)");
        customRadio.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                if (customRadio.getSelection()) showCustomPanel();
                site.updateButtons();
            }
        });

        // Stack panel for cloud vs custom fields
        stackParent = new Composite(control, SWT.NONE);
        stack = new StackLayout();
        stackParent.setLayout(stack);
        stackParent.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        cloudPanel = createCloudPanel(stackParent, buttonsUpdater);
        customPanel = createCustomPanel(stackParent, buttonsUpdater);

        // Default selection — Custom
        customRadio.setSelection(true);
        showCustomPanel();

        createDriverPanel(control);
        setControl(control);
    }

    private Composite createCloudPanel(Composite parent, SelectionListener updater) {
        Composite group = UIUtils.createControlGroup(
            parent, "Weaviate Cloud", 2, GridData.FILL_HORIZONTAL, 0);

        cloudUrlText = UIUtils.createLabelText(group, "Cluster URL", "");
        cloudUrlText.setMessage("https://my-cluster.weaviate.cloud");
        cloudUrlText.addModifyListener(e -> site.updateButtons());

        cloudApiKeyText = UIUtils.createLabelText(group, "API Key", "", SWT.BORDER | SWT.PASSWORD);
        cloudApiKeyText.addModifyListener(e -> site.updateButtons());

        return group;
    }

    private Composite createCustomPanel(Composite parent, SelectionListener updater) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        root.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Composite addrGroup = UIUtils.createControlGroup(
            root, "HTTP", 4, GridData.FILL_HORIZONTAL, 0);

        UIUtils.createControlLabel(addrGroup, "Scheme");
        schemeCombo = new Combo(addrGroup, SWT.DROP_DOWN | SWT.READ_ONLY);
        schemeCombo.add("http");
        schemeCombo.add("https");
        schemeCombo.select(0);
        schemeCombo.addSelectionListener(updater);
        GridData schemeGd = new GridData();
        schemeGd.widthHint = UIUtils.getFontHeight(schemeCombo) * 7;
        schemeCombo.setLayoutData(schemeGd);

        UIUtils.createControlLabel(addrGroup, "Host");
        hostText = new Text(addrGroup, SWT.BORDER);
        hostText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        hostText.addModifyListener(e -> site.updateButtons());

        UIUtils.createControlLabel(addrGroup, "Port");
        portText = new Text(addrGroup, SWT.BORDER);
        GridData portGd = new GridData();
        portGd.widthHint = UIUtils.getFontHeight(portText) * 7;
        portText.setLayoutData(portGd);
        portText.addVerifyListener(UIUtils.getIntegerVerifyListener(Locale.getDefault()));
        portText.addModifyListener(e -> site.updateButtons());

        Label spacer = new Label(addrGroup, SWT.NONE);
        spacer.setLayoutData(new GridData());

        Composite grpcGroup = UIUtils.createControlGroup(
            root, "gRPC", 4, GridData.FILL_HORIZONTAL, 0);

        UIUtils.createControlLabel(grpcGroup, "gRPC Host");
        grpcHostText = new Text(grpcGroup, SWT.BORDER);
        GridData grpcHostGd = new GridData(GridData.FILL_HORIZONTAL);
        grpcHostGd.horizontalSpan = 3;
        grpcHostText.setLayoutData(grpcHostGd);
        grpcHostText.addModifyListener(e -> site.updateButtons());

        UIUtils.createControlLabel(grpcGroup, "gRPC Port");
        grpcPortText = new Text(grpcGroup, SWT.BORDER);
        GridData grpcPortGd = new GridData();
        grpcPortGd.widthHint = UIUtils.getFontHeight(grpcPortText) * 7;
        grpcPortText.setLayoutData(grpcPortGd);
        grpcPortText.addVerifyListener(UIUtils.getIntegerVerifyListener(Locale.getDefault()));
        grpcPortText.addModifyListener(e -> site.updateButtons());

        new Label(grpcGroup, SWT.NONE);
        new Label(grpcGroup, SWT.NONE);

        Composite authGroup = UIUtils.createControlGroup(
            root, "Authentication", 2, GridData.FILL_HORIZONTAL, 0);
        UIUtils.createControlLabel(authGroup, "Auth Type");
        authTypeCombo = new Combo(authGroup, SWT.DROP_DOWN | SWT.READ_ONLY);
        authTypeCombo.add("None");
        authTypeCombo.add("API Key");
        authTypeCombo.add("Username / Password");
        authTypeCombo.select(0);
        authTypeCombo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        authTypeCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                showAuthPanel(authTypeCombo.getSelectionIndex());
                site.updateButtons();
            }
        });

        authStackParent = new Composite(authGroup, SWT.NONE);
        authStack = new StackLayout();
        authStackParent.setLayout(authStack);
        GridData authStackGd = new GridData(GridData.FILL_HORIZONTAL);
        authStackGd.horizontalSpan = 2;
        authStackParent.setLayoutData(authStackGd);

        authNonePanel = new Composite(authStackParent, SWT.NONE);
        authNonePanel.setLayout(new GridLayout(1, false));

        authApiKeyPanel = UIUtils.createControlGroup(
            authStackParent, "", 2, GridData.FILL_HORIZONTAL, 0);
        customApiKeyText = UIUtils.createLabelText(authApiKeyPanel, "API Key", "", SWT.BORDER | SWT.PASSWORD);
        customApiKeyText.addModifyListener(e -> site.updateButtons());

        authUserPasswordPanel = UIUtils.createControlGroup(
            authStackParent, "", 2, GridData.FILL_HORIZONTAL, 0);
        usernameText = UIUtils.createLabelText(authUserPasswordPanel, "Username", "");
        usernameText.addModifyListener(e -> site.updateButtons());
        passwordText = UIUtils.createLabelText(authUserPasswordPanel, "Password", "", SWT.BORDER | SWT.PASSWORD);
        passwordText.addModifyListener(e -> site.updateButtons());

        showAuthPanel(0);

        return root;
    }

    private void showCloudPanel() {
        stack.topControl = cloudPanel;
        stackParent.layout();
    }

    private void showCustomPanel() {
        stack.topControl = customPanel;
        stackParent.layout();
    }

    private void showAuthPanel(int index) {
        switch (index) {
            case 1: authStack.topControl = authApiKeyPanel; break;
            case 2: authStack.topControl = authUserPasswordPanel; break;
            default: authStack.topControl = authNonePanel; break;
        }
        authStackParent.layout();
    }

    @Override
    public boolean isComplete() {
        if (cloudRadio != null && cloudRadio.getSelection()) {
            return cloudUrlText != null && !CommonUtils.isEmpty(cloudUrlText.getText())
                && cloudApiKeyText != null && !CommonUtils.isEmpty(cloudApiKeyText.getText());
        }
        return hostText != null && !CommonUtils.isEmpty(hostText.getText())
            && portText != null && !CommonUtils.isEmpty(portText.getText());
    }

    @Override
    public void loadSettings() {
        super.loadSettings();
        DBPConnectionConfiguration cfg = site.getActiveDataSource().getConnectionConfiguration();

        boolean isCloud = WeaviateConstants.CONN_TYPE_CLOUD.equals(
            cfg.getProviderProperty(WeaviateConstants.PROP_CONNECTION_TYPE));

        cloudRadio.setSelection(isCloud);
        customRadio.setSelection(!isCloud);

        cloudUrlText.setText(CommonUtils.notEmpty(cfg.getProviderProperty(WeaviateConstants.PROP_CLOUD_URL)));

        // Custom fields
        String scheme = cfg.getProviderProperty(WeaviateConstants.PROP_SCHEME);
        if (CommonUtils.isEmpty(scheme)) scheme = WeaviateConstants.DEFAULT_SCHEME;
        schemeCombo.setText(scheme);

        hostText.setText(CommonUtils.isEmpty(cfg.getHostName())
            ? WeaviateConstants.DEFAULT_HTTP_HOST : cfg.getHostName());
        portText.setText(CommonUtils.isEmpty(cfg.getHostPort())
            ? String.valueOf(WeaviateConstants.DEFAULT_HTTP_PORT) : cfg.getHostPort());

        String grpcHost = cfg.getProviderProperty(WeaviateConstants.PROP_GRPC_HOST);
        grpcHostText.setText(CommonUtils.isEmpty(grpcHost)
            ? WeaviateConstants.DEFAULT_GRPC_HOST : grpcHost);
        String grpcPort = cfg.getProviderProperty(WeaviateConstants.PROP_GRPC_PORT);
        grpcPortText.setText(CommonUtils.isEmpty(grpcPort)
            ? String.valueOf(WeaviateConstants.DEFAULT_GRPC_PORT) : grpcPort);

        String authType = cfg.getProviderProperty(WeaviateConstants.PROP_AUTH_TYPE);
        int authIndex = 0;
        if (WeaviateConstants.AUTH_API_KEY.equals(authType)) authIndex = 1;
        else if (WeaviateConstants.AUTH_USER_PASSWORD.equals(authType)) authIndex = 2;
        authTypeCombo.select(authIndex);
        showAuthPanel(authIndex);

        String apiKey = CommonUtils.notEmpty(cfg.getProviderProperty(WeaviateConstants.PROP_API_KEY));
        cloudApiKeyText.setText(isCloud ? apiKey : "");
        customApiKeyText.setText(isCloud ? "" : apiKey);

        usernameText.setText(CommonUtils.notEmpty(cfg.getUserName()));
        passwordText.setText(CommonUtils.notEmpty(cfg.getUserPassword()));

        if (isCloud) showCloudPanel(); else showCustomPanel();
    }

    @Override
    public void saveSettings(DBPDataSourceContainer dataSource) {
        super.saveSettings(dataSource);
        DBPConnectionConfiguration cfg = dataSource.getConnectionConfiguration();

        boolean isCloud = cloudRadio != null && cloudRadio.getSelection();
        cfg.setProviderProperty(WeaviateConstants.PROP_CONNECTION_TYPE,
            isCloud ? WeaviateConstants.CONN_TYPE_CLOUD : WeaviateConstants.CONN_TYPE_CUSTOM);

        if (isCloud) {
            cfg.setProviderProperty(WeaviateConstants.PROP_CLOUD_URL, cloudUrlText.getText().trim());
            cfg.setProviderProperty(WeaviateConstants.PROP_API_KEY, cloudApiKeyText.getText());
            cfg.setProviderProperty(WeaviateConstants.PROP_AUTH_TYPE, WeaviateConstants.AUTH_API_KEY);
        } else {
            cfg.setProviderProperty(WeaviateConstants.PROP_CLOUD_URL, "");
            cfg.setProviderProperty(WeaviateConstants.PROP_SCHEME, schemeCombo.getText());
            cfg.setHostName(hostText.getText().trim());
            cfg.setHostPort(portText.getText().trim());
            cfg.setProviderProperty(WeaviateConstants.PROP_GRPC_HOST, grpcHostText.getText().trim());
            cfg.setProviderProperty(WeaviateConstants.PROP_GRPC_PORT, grpcPortText.getText().trim());

            int authIndex = authTypeCombo.getSelectionIndex();
            String authType;
            switch (authIndex) {
                case 1: authType = WeaviateConstants.AUTH_API_KEY; break;
                case 2: authType = WeaviateConstants.AUTH_USER_PASSWORD; break;
                default: authType = WeaviateConstants.AUTH_NONE; break;
            }
            cfg.setProviderProperty(WeaviateConstants.PROP_AUTH_TYPE, authType);

            if (authIndex == 1) {
                cfg.setProviderProperty(WeaviateConstants.PROP_API_KEY, customApiKeyText.getText());
                cfg.setUserName("");
                cfg.setUserPassword("");
            } else if (authIndex == 2) {
                cfg.setProviderProperty(WeaviateConstants.PROP_API_KEY, "");
                cfg.setUserName(usernameText.getText().trim());
                cfg.setUserPassword(passwordText.getText());
            } else {
                cfg.setProviderProperty(WeaviateConstants.PROP_API_KEY, "");
                cfg.setUserName("");
                cfg.setUserPassword("");
            }
        }
    }
}
