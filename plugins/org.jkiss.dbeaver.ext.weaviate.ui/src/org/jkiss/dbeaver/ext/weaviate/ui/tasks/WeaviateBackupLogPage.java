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
package org.jkiss.dbeaver.ext.weaviate.ui.tasks;

import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.jkiss.dbeaver.ui.UIUtils;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Where the phases of a running backup are written.
 * <p>
 * A plain read-only text box rather than {@code NativeToolWizardPageLog}, which is the equivalent
 * in the native-tool bundle. That page exists to pump the stdout of an external process and its
 * log reader casts the wizard to {@code AbstractNativeToolWizard}; there is no process here, and
 * taking the dependency to use a fraction of the class would be the wrong trade.
 * <p>
 * The stream matters more than the widget: it is what the task handler writes each status change
 * to, so a backup that sat in TRANSFERRING for a minute leaves evidence that it was doing
 * something.
 */
public class WeaviateBackupLogPage extends WizardPage {

    private Text logText;
    private PrintStream logWriter;

    protected WeaviateBackupLogPage() {
        super("weaviate.backup.log");
        setTitle("Progress");
        setDescription("The server's progress through the operation");
    }

    @Override
    public void createControl(Composite parent) {
        Composite area = UIUtils.createComposite(parent, 1);
        area.setLayoutData(new GridData(GridData.FILL_BOTH));

        logText = new Text(area, SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        GridData gd = new GridData(GridData.FILL_BOTH);
        gd.heightHint = 260;
        gd.widthHint = 500;
        logText.setLayoutData(gd);
        logText.setFont(UIUtils.getMonospaceFont());

        setControl(area);
    }

    public void clearLog() {
        if (logText != null && !logText.isDisposed()) {
            logText.setText("");
        }
    }

    /**
     * A stream the task handler can write to from its own thread. Every write is bounced onto the
     * UI thread, since the handler runs in a progress job and SWT would refuse the call otherwise.
     */
    public PrintStream getLogWriter() {
        if (logWriter == null) {
            logWriter = new PrintStream(new OutputStream() {
                private final StringBuilder line = new StringBuilder();

                @Override
                public void write(int b) {
                    if (b == '\n') {
                        String text = line.toString();
                        line.setLength(0);
                        append(text);
                    } else if (b != '\r') {
                        line.append((char) b);
                    }
                }
            }, true, StandardCharsets.UTF_8);
        }
        return logWriter;
    }

    private void append(String text) {
        UIUtils.asyncExec(() -> {
            if (logText != null && !logText.isDisposed()) {
                logText.append(text + "\n");
            }
        });
    }
}
