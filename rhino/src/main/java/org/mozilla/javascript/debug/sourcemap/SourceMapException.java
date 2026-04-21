/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.debug.sourcemap;

/**
 * Thrown when a source map fails to parse or is structurally invalid. Unchecked so that callers
 * that speculatively parse (e.g. {@code RhinoException} stack-trace rewriting) can fall through
 * without mandatory try/catch.
 */
public class SourceMapException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SourceMapException(String message) {
        super(message);
    }

    public SourceMapException(String message, Throwable cause) {
        super(message, cause);
    }
}
