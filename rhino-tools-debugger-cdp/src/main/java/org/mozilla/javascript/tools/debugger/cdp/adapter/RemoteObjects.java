/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp.adapter;

import java.util.LinkedHashMap;
import java.util.Map;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

/** Wraps Java/JS values into CDP RemoteObject maps (plain-Java, thread-safe to hand off). */
public final class RemoteObjects {

    private RemoteObjects() {}

    public static Map<String, Object> wrap(Object value, String group, RemoteObjectStore store) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (value == null) {
            m.put("type", "object");
            m.put("subtype", "null");
            m.put("value", null);
            return m;
        }
        if (value == Undefined.instance) {
            m.put("type", "undefined");
            return m;
        }
        if (value instanceof Boolean) {
            m.put("type", "boolean");
            m.put("value", value);
            return m;
        }
        if (value instanceof Number) {
            m.put("type", "number");
            m.put("value", value);
            m.put("description", value.toString());
            return m;
        }
        if (value instanceof CharSequence) {
            m.put("type", "string");
            m.put("value", value.toString());
            return m;
        }
        if (value instanceof Function) {
            m.put("type", "function");
            m.put("className", "Function");
            m.put("description", describeFunction((Function) value));
            m.put("objectId", store.register(value, group));
            return m;
        }
        if (value instanceof Scriptable) {
            Scriptable s = (Scriptable) value;
            m.put("type", "object");
            m.put("className", safeClassName(s));
            m.put("description", describe(s));
            m.put("objectId", store.register(s, group));
            return m;
        }
        m.put("type", "object");
        m.put("value", value.toString());
        return m;
    }

    private static String describeFunction(Function f) {
        if (f instanceof Scriptable) {
            String name = null;
            try {
                Object n = ((Scriptable) f).get("name", (Scriptable) f);
                if (n instanceof CharSequence) name = n.toString();
            } catch (RuntimeException ignored) {
            }
            return "function "
                    + (name == null || name.isEmpty() ? "anonymous" : name)
                    + "() { [native code] }";
        }
        return "function () { [native code] }";
    }

    private static String describe(Scriptable s) {
        String cn = safeClassName(s);
        if ("Array".equals(cn)) {
            Object len = ScriptableObject.getProperty(s, "length");
            if (len instanceof Number) {
                return "Array(" + ((Number) len).intValue() + ")";
            }
        }
        return cn;
    }

    private static String safeClassName(Scriptable s) {
        // VarScope.getClassName() throws — and prints a stack trace before throwing. Detect
        // that class of scope directly so we don't drag noise into stderr on every paused
        // scope wrap.
        if (s instanceof org.mozilla.javascript.VarScope) {
            return s.getClass().getSimpleName();
        }
        return s.getClassName();
    }
}
