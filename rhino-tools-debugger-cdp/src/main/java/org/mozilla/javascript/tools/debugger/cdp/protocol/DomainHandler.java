/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp.protocol;

import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.tools.debugger.cdp.transport.CdpTransport;

public interface DomainHandler {
    /**
     * Handle one CDP command within a domain.
     *
     * @param command method name minus the domain prefix (e.g. {@code "setBreakpoint"})
     * @param params JSON params object, may be {@code null}
     * @param replier callback to send reply
     */
    void handle(String command, Scriptable params, CdpTransport.Replier replier);
}
