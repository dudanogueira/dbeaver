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

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;

/**
 * The dismissable strip above the query fields that carries the last error or notice.
 * <p>
 * It takes itself out of the layout when hidden rather than merely turning invisible -- an
 * invisible-but-present banner leaves a gap the width of a message nobody can read.
 * <p>
 * The colours and the font are created here and disposed with the composite. That pairing is the
 * reason this is a class rather than a few methods: SWT resources have to be released exactly
 * once by whoever made them, and spreading their creation and disposal across a 2,900-line panel
 * is how one ends up leaked or double-disposed.
 */
public class WeaviateQueryBanner {

    private final Runnable onDismiss;

    private Composite banner;
    private Label bannerLabel;
    private Color errorBg;
    private Color infoBg;
    private Font bannerFont;

    /**
     * @param onDismiss run when the user closes the banner, for state that outlives the widget --
     *                  the panel clears the collection's remembered error so it does not come
     *                  straight back on the next refresh
     */
    public WeaviateQueryBanner(@NotNull Runnable onDismiss) {
        this.onDismiss = onDismiss;
    }

    @NotNull
    public Composite createControls(@NotNull Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        GridLayout l = new GridLayout(2, false);
        l.marginWidth = 8;
        l.marginHeight = 6;
        c.setLayout(l);
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.exclude = true;
        c.setLayoutData(gd);

        Display display = parent.getDisplay();
        errorBg = new Color(display, 255, 224, 224);
        infoBg = new Color(display, 224, 240, 255);

        bannerLabel = new Label(c, SWT.WRAP);
        bannerLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        FontData fd = bannerLabel.getFont().getFontData()[0];
        bannerFont = new Font(display, fd.getName(), Math.max(fd.getHeight(), 11), SWT.BOLD);
        bannerLabel.setFont(bannerFont);

        Button dismiss = new Button(c, SWT.PUSH | SWT.FLAT);
        dismiss.setText("✕");
        dismiss.setToolTipText(WeaviateUIMessages.query_dismiss);
        dismiss.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        dismiss.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onDismiss.run();
                hide();
            }
        });

        c.setVisible(false);
        c.addDisposeListener(new DisposeListener() {
            @Override
            public void widgetDisposed(DisposeEvent e) {
                if (errorBg != null && !errorBg.isDisposed()) errorBg.dispose();
                if (infoBg != null && !infoBg.isDisposed()) infoBg.dispose();
                if (bannerFont != null && !bannerFont.isDisposed()) bannerFont.dispose();
            }
        });
        this.banner = c;
        return c;
    }

    public void showError(@NotNull String msg) {
        show("⚠ " + msg, true);
    }

    public void showInfo(@NotNull String msg) {
        show(msg, false);
    }

    public void hide() {
        if (banner == null || banner.isDisposed()) return;
        ((GridData) banner.getLayoutData()).exclude = true;
        banner.setVisible(false);
        if (bannerLabel != null && !bannerLabel.isDisposed()) {
            bannerLabel.setText("");
            bannerLabel.setToolTipText("");
        }
        banner.getParent().layout(true, true);
    }

    private void show(@NotNull String msg, boolean error) {
        if (banner == null || banner.isDisposed()) return;
        Color bg = error ? errorBg : infoBg;
        Color fg = error
            ? Display.getCurrent().getSystemColor(SWT.COLOR_DARK_RED)
            : Display.getCurrent().getSystemColor(SWT.COLOR_DARK_BLUE);
        banner.setBackground(bg);
        bannerLabel.setBackground(bg);
        bannerLabel.setForeground(fg);
        bannerLabel.setText(msg);
        bannerLabel.setToolTipText(msg);
        ((GridData) banner.getLayoutData()).exclude = false;
        banner.setVisible(true);
        banner.getParent().layout(true, true);
    }
}
