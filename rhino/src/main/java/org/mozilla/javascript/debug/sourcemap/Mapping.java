/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.debug.sourcemap;

/**
 * A single mapping segment produced by {@link SourceMap} lookups.
 *
 * <p>Line and column values are zero-based, matching the CDP / source-map spec convention. {@link
 * #source} is {@code null} for "1-field" segments, which mark a generated position that has no
 * original-source counterpart (e.g. compiler-injected code).
 */
public final class Mapping {
    public final int generatedLine;
    public final int generatedColumn;
    public final String source;
    public final int originalLine;
    public final int originalColumn;
    public final String name;

    public Mapping(
            int generatedLine,
            int generatedColumn,
            String source,
            int originalLine,
            int originalColumn,
            String name) {
        this.generatedLine = generatedLine;
        this.generatedColumn = generatedColumn;
        this.source = source;
        this.originalLine = originalLine;
        this.originalColumn = originalColumn;
        this.name = name;
    }

    @Override
    public String toString() {
        return "Mapping{gen="
                + generatedLine
                + ":"
                + generatedColumn
                + (source == null
                        ? ", unmapped}"
                        : (", source="
                                + source
                                + ", orig="
                                + originalLine
                                + ":"
                                + originalColumn
                                + (name == null ? "" : ", name=" + name)
                                + "}"));
    }
}
