/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp.adapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.mozilla.javascript.NativeWith;
import org.mozilla.javascript.Scriptable;

/**
 * Walks a {@link CdpDebugFrame}'s activation chain and produces a CDP {@code scopeChain} array of
 * plain-Java maps, registering each scope as a RemoteObject.
 */
public final class ScopeChain {

    private ScopeChain() {}

    public static List<Map<String, Object>> build(
            CdpDebugFrame frame, String group, RemoteObjectStore store) {
        List<Map<String, Object>> chain = new ArrayList<>();
        Scriptable s = frame.activation();
        Scriptable topLevel = findTopLevel(s);
        boolean first = true;
        while (s != null) {
            String type;
            if (s == topLevel) {
                type = "global";
            } else if (s instanceof NativeWith) {
                type = "with";
            } else if (first) {
                type = "local";
            } else {
                type = "closure";
            }
            first = false;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", type);
            Map<String, Object> ro = new LinkedHashMap<>();
            ro.put("type", "object");
            ro.put("className", s.getClassName());
            ro.put("description", s.getClassName());
            ro.put("objectId", store.register(s, group));
            entry.put("object", ro);
            chain.add(entry);
            if (s == topLevel) break;
            s = s.getParentScope();
        }
        return chain;
    }

    private static Scriptable findTopLevel(Scriptable s) {
        Scriptable cur = s;
        Scriptable last = s;
        while (cur != null) {
            last = cur;
            cur = cur.getParentScope();
        }
        return last;
    }
}
