/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp.pause;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import org.mozilla.javascript.tools.debugger.cdp.adapter.CdpDebugFrame;

/** One per paused JS thread. Owns the wait-lock, step mode, and eval queue. */
public final class PausedState {
    public final Thread thread;
    public final List<CdpDebugFrame> frames;
    public final String reason;
    public final Object auxData;
    public final Object lock = new Object();
    public volatile StepMode resume = StepMode.NONE;
    public final LinkedBlockingQueue<EvalRequest> evalQueue = new LinkedBlockingQueue<>();

    public PausedState(Thread thread, List<CdpDebugFrame> frames, String reason, Object auxData) {
        this.thread = thread;
        this.frames = frames;
        this.reason = reason;
        this.auxData = auxData;
    }
}
