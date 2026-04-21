/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp.protocol;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.tools.debugger.cdp.transport.CdpTransport;

/**
 * Routes {@code Domain.command} messages to registered {@link DomainHandler}s. Unknown methods
 * return JSON-RPC error code {@code -32601} and do not close the connection.
 */
public final class CdpDispatcher implements CdpTransport.InboundHandler {

    private static final Logger LOG = Logger.getLogger(CdpDispatcher.class.getName());

    private final Map<String, DomainHandler> domains = new HashMap<>();
    private SessionListener listener;

    public interface SessionListener {
        void onConnected();

        void onDisconnected();
    }

    public void register(String domain, DomainHandler handler) {
        domains.put(domain, handler);
    }

    public void setSessionListener(SessionListener listener) {
        this.listener = listener;
    }

    @Override
    public void onMessage(int id, String method, Scriptable params, CdpTransport.Replier replier) {
        int dot = method.indexOf('.');
        if (dot < 0) {
            replier.err(-32601, "Method not found: " + method);
            return;
        }
        String domain = method.substring(0, dot);
        String command = method.substring(dot + 1);
        DomainHandler h = domains.get(domain);
        if (h == null) {
            replier.err(-32601, "Method not found: " + method);
            return;
        }
        try {
            h.handle(command, params, replier);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "handler for " + method + " threw", e);
            replier.err(-32000, "Internal error: " + e.getMessage());
        }
    }

    @Override
    public void onClientConnected() {
        if (listener != null) listener.onConnected();
    }

    @Override
    public void onClientDisconnected() {
        if (listener != null) listener.onDisconnected();
    }
}
