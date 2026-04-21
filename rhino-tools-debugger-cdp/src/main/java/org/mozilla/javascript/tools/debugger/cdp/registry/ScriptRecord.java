/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp.registry;

import java.util.Arrays;
import org.mozilla.javascript.debug.DebuggableScript;
import org.mozilla.javascript.debug.sourcemap.SourceMap;

public final class ScriptRecord {
    public final String scriptId;
    public final String url;
    public final String source;
    public final String hash;
    public final DebuggableScript top;
    public final int[] validLines;
    public final int endLine;
    public final String sourceMappingURL;
    public final String sourceURLOverride;

    /** Parsed map, if the registry was able to fetch + parse it. May be {@code null}. */
    public final SourceMap sourceMap;

    public ScriptRecord(
            String scriptId,
            String url,
            String source,
            String hash,
            DebuggableScript top,
            int[] validLines,
            int endLine,
            String sourceMappingURL,
            String sourceURLOverride,
            SourceMap sourceMap) {
        this.scriptId = scriptId;
        this.url = url;
        this.source = source;
        this.hash = hash;
        this.top = top;
        this.validLines = validLines;
        this.endLine = endLine;
        this.sourceMappingURL = sourceMappingURL;
        this.sourceURLOverride = sourceURLOverride;
        this.sourceMap = sourceMap;
    }

    /** Snap the requested line to the nearest valid line at or after it. */
    public int snapLine(int requested) {
        if (validLines.length == 0) return requested;
        int idx = Arrays.binarySearch(validLines, requested);
        if (idx >= 0) return validLines[idx];
        int insert = -idx - 1;
        if (insert >= validLines.length) return validLines[validLines.length - 1];
        return validLines[insert];
    }

    public boolean hasValidLine(int line) {
        return Arrays.binarySearch(validLines, line) >= 0;
    }
}
