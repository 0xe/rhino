/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp;

import java.util.function.Function;

public final class CdpDebuggerConfig {
    public int port = 9222;
    public String host = "127.0.0.1";

    /**
     * When true, {@link CdpDebugger#waitForClient()} blocks until a CDP client connects and sends
     * {@code Runtime.runIfWaitingForDebugger}. Use this to avoid racing with startup.
     */
    public boolean waitForAttach = false;

    /** Reserved for source-map phase. Fetches a source-map URL to its raw JSON content. */
    public Function<String, String> sourceMapFetcher = null;

    /** Reserved: inline maps as {@code data:} URIs in {@code scriptParsed} events. */
    public boolean inlineSourceMapsInEvents = false;

    /** Kill-switch — when false, {@link CdpDebugger#attach} returns a no-op handle. */
    public boolean enabled = true;
}
