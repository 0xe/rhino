/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp.pause;

import java.util.concurrent.CompletableFuture;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

/** An evaluation request submitted by the dispatcher thread for execution on a paused JS thread. */
public final class EvalRequest {
    public final String callFrameId;
    public final String expression;
    public final CompletableFuture<EvalResult> future;

    public EvalRequest(String callFrameId, String expression) {
        this.callFrameId = callFrameId;
        this.expression = expression;
        this.future = new CompletableFuture<>();
    }

    /** Executed on the paused JS thread; uses that thread's {@link Context}. */
    public interface Runner {
        EvalResult run(Context cx, Scriptable frameScope);
    }
}
