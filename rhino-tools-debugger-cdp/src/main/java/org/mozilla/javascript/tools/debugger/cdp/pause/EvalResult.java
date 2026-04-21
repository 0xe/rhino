/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp.pause;

import java.util.Map;

/** Plain-Java result of a paused-thread evaluation, safe to hand back across threads. */
public final class EvalResult {
    /** RemoteObject map, already materialized as plain Java. */
    public final Map<String, Object> remoteObject;

    /** If non-null, carries an exceptionDetails object. */
    public final Map<String, Object> exceptionDetails;

    public EvalResult(Map<String, Object> remoteObject, Map<String, Object> exceptionDetails) {
        this.remoteObject = remoteObject;
        this.exceptionDetails = exceptionDetails;
    }
}
