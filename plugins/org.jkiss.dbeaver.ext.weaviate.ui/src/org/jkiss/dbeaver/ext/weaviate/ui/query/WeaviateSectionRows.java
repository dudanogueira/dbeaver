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

import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Control;
import org.jkiss.code.Nullable;

import java.util.List;

/**
 * Showing and hiding rows inside a section.
 * <p>
 * Visibility alone is not enough: an invisible control still occupies its grid cell, so a hidden
 * row would leave a blank gap the size of the controls nobody can see. Setting {@code exclude}
 * takes it out of the layout as well, which is what makes a section shrink rather than go blank.
 */
public final class WeaviateSectionRows {

    private WeaviateSectionRows() {
        // Utility class.
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
