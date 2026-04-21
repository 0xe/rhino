/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp.registry;

public final class Breakpoint {
    public final String id;

    /** Either a scriptId (e.g. {@code "7"}) or a URL, depending on {@link #isUrl}. */
    public final String scriptIdOrUrl;

    public final int line;
    public final int column;
    public final String condition;
    public final boolean isUrl;
    public final boolean isRegex;

    /** For id-based: the exact script. For url-based: may be null until resolved. */
    public volatile String resolvedScriptId;

    public volatile int resolvedLine;

    public Breakpoint(
            String id,
            String scriptIdOrUrl,
            int line,
            int column,
            String condition,
            boolean isUrl,
            boolean isRegex) {
        this.id = id;
        this.scriptIdOrUrl = scriptIdOrUrl;
        this.line = line;
        this.column = column;
        this.condition = condition;
        this.isUrl = isUrl;
        this.isRegex = isRegex;
        this.resolvedScriptId = null;
        this.resolvedLine = line;
    }
}
