/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp.transport;

import java.util.Collections;
import java.util.Map;

/**
 * Plain-Java carrier for a CDP event or reply that must cross thread boundaries.
 *
 * <p>Only primitive types, {@link String}, {@link Number}, {@link Boolean}, {@link java.util.List},
 * and {@link java.util.Map} are allowed in {@link #params}. No Rhino {@code Scriptable} references
 * — the transport thread materializes Scriptables in its own {@link
 * org.mozilla.javascript.Context}.
 */
public final class QueuedEvent {
    public final String method;
    public final Map<String, Object> params;
    public final Integer replyId;

    private QueuedEvent(String method, Map<String, Object> params, Integer replyId) {
        this.method = method;
        this.params = params == null ? Collections.emptyMap() : params;
        this.replyId = replyId;
    }

    public static QueuedEvent event(String method, Map<String, Object> params) {
        return new QueuedEvent(method, params, null);
    }

    public static QueuedEvent reply(int id, Map<String, Object> result) {
        return new QueuedEvent(null, result, id);
    }

    public static QueuedEvent error(int id, int code, String message) {
        java.util.LinkedHashMap<String, Object> err = new java.util.LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        java.util.LinkedHashMap<String, Object> wrap = new java.util.LinkedHashMap<>();
        wrap.put("__cdp_error__", err);
        return new QueuedEvent(null, wrap, id);
    }

    public boolean isReply() {
        return replyId != null;
    }
}
