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
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.Nullable;

import org.jkiss.code.NotNull;

import java.util.List;

/**
 * Null- and dispose-safe access to the widgets inside a section.
 * <p>
 * Sections load themselves from a spec that may be missing any given value, and they do it after
 * a mode switch may already have disposed a composite, so every one of these would otherwise be
 * written as a two-line guard at each call site.
 * <p>
 * Visibility alone is not enough: an invisible control still occupies its grid cell, so a hidden
 * row would leave a blank gap the size of the controls nobody can see. Setting {@code exclude}
 * takes it out of the layout as well, which is what makes a section shrink rather than go blank.
 */
public final class WeaviateSectionWidgets {

    private WeaviateSectionWidgets() {
        // Utility class.
    }

    /**
     * Run something on the platform's "commit" chord rather than on Enter.
     * <p>
     * A multi-line box has to keep Enter for what Enter does everywhere else -- break the line.
     * MOD1 is Command on macOS and Ctrl elsewhere, so this reads as Cmd+Enter or Ctrl+Enter
     * without either being hard-coded.
     * <p>
     * {@code doit = false} matters: without it the newline is inserted as well as the query run,
     * leaving a stray blank line in the box every time it is used.
     */
    public static void runOnModEnter(@NotNull Text field, @NotNull Runnable run) {
        field.addListener(SWT.KeyDown, e -> {
            boolean enter = e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR;
            if (enter && (e.stateMask & SWT.MOD1) != 0) {
                e.doit = false;
                run.run();
            }
        });
    }

    /**
     * Give a multi-line field room for {@code lines} lines of its own font, rather than a pixel
     * count that is wrong on the next display.
     */
    public static void setVisibleLines(@NotNull Text field, int lines) {
        Object data = field.getLayoutData();
        GridData gd = data instanceof GridData existing
            ? existing
            : new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.heightHint = field.getLineHeight() * lines;
        field.setLayoutData(gd);
    }

    /** How the commit chord is written on this platform, for a hint or tooltip. */
    @NotNull
    public static String modEnterLabel() {
        return (SWT.MOD1 & SWT.COMMAND) != 0 ? "\u2318\u21A9" : "Ctrl+Enter";
    }

    public static void setText(@Nullable Text field, @Nullable String value) {
        if (field != null && !field.isDisposed()) {
            field.setText(value == null ? "" : value);
        }
    }

    public static void setChecked(@Nullable Button check, boolean selected) {
        if (check != null && !check.isDisposed()) {
            check.setSelection(selected);
        }
    }

    public static void setVisible(@Nullable List<Control> rows, boolean visible) {
        if (rows == null) {
            return;
        }
        for (Control control : rows) {
            if (control == null || control.isDisposed()) {
                continue;
            }
            control.setVisible(visible);
            Object data = control.getLayoutData();
            if (data instanceof GridData gd) {
                gd.exclude = !visible;
            } else {
                GridData gd = new GridData();
                gd.exclude = !visible;
                control.setLayoutData(gd);
            }
        }
    }
}
