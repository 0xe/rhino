/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp.transport;

import java.util.List;
import java.util.Map;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeJSON;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.TopLevel;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.json.JsonParser;

/**
 * Thread-bound JSON marshaling via Rhino's own {@link NativeJSON}. One instance per owning thread;
 * owns its {@link Context} and scope, which must never be shared with JS execution threads.
 */
public final class JsonMarshaller {

    private final ContextFactory factory;
    private Context cx;
    private Scriptable scope;

    public JsonMarshaller(ContextFactory factory) {
        this.factory = factory;
    }

    public void attachToCurrentThread() {
        if (cx != null) return;
        cx = factory.enterContext();
        scope = cx.initStandardObjects();
    }

    public void detach() {
        if (cx != null) {
            Context.exit();
            cx = null;
            scope = null;
        }
    }

    public Scriptable parse(String text) {
        ensureAttached();
        try {
            Object parsed = new JsonParser(cx, scope).parseValue(text);
            if (parsed instanceof Scriptable) return (Scriptable) parsed;
        } catch (JsonParser.ParseException e) {
            throw new IllegalArgumentException("invalid JSON", e);
        }
        return null;
    }

    public String stringify(Map<String, Object> tree) {
        ensureAttached();
        Scriptable built = toScriptable(tree);
        Object s = NativeJSON.stringify(cx, scope, built, null, "");
        return (String) s;
    }

    public Scriptable toScriptable(Object value) {
        ensureAttached();
        return (Scriptable) convert(value);
    }

    private Object convert(Object value) {
        if (value == null) return null;
        if (value instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) value;
            NativeObject o = new NativeObject();
            ScriptRuntime.setBuiltinProtoAndParent(o, scope, TopLevel.Builtins.Object);
            for (Map.Entry<?, ?> e : m.entrySet()) {
                ScriptableObject.putProperty(o, (String) e.getKey(), convertValue(e.getValue()));
            }
            return o;
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            Object[] items = new Object[list.size()];
            for (int i = 0; i < items.length; i++) items[i] = convertValue(list.get(i));
            NativeArray arr = new NativeArray(items);
            ScriptRuntime.setBuiltinProtoAndParent(arr, scope, TopLevel.Builtins.Array);
            return arr;
        }
        return convertValue(value);
    }

    private Object convertValue(Object v) {
        if (v == null) return null;
        if (v instanceof Map || v instanceof List) return convert(v);
        if (v instanceof String || v instanceof Boolean || v instanceof Number) return v;
        if (v == Undefined.instance) return v;
        // Fallback: stringify unknown types.
        return v.toString();
    }

    private void ensureAttached() {
        if (cx == null) {
            throw new IllegalStateException("JsonMarshaller not attached");
        }
    }

    // ---- Accessor helpers on inbound Scriptables ----

    public static String str(Scriptable obj, String name) {
        if (obj == null) return null;
        Object v = ScriptableObject.getProperty(obj, name);
        if (v == Scriptable.NOT_FOUND || v == Undefined.instance || v == null) return null;
        return ScriptRuntime.toString(v);
    }

    public static Integer intOrNull(Scriptable obj, String name) {
        if (obj == null) return null;
        Object v = ScriptableObject.getProperty(obj, name);
        if (v == Scriptable.NOT_FOUND || v == Undefined.instance || v == null) return null;
        return (int) ScriptRuntime.toInt32(v);
    }

    public static int intOr(Scriptable obj, String name, int dflt) {
        Integer v = intOrNull(obj, name);
        return v == null ? dflt : v;
    }

    public static boolean boolOr(Scriptable obj, String name, boolean dflt) {
        if (obj == null) return dflt;
        Object v = ScriptableObject.getProperty(obj, name);
        if (v == Scriptable.NOT_FOUND || v == Undefined.instance || v == null) return dflt;
        return ScriptRuntime.toBoolean(v);
    }

    public static Scriptable child(Scriptable obj, String name) {
        if (obj == null) return null;
        Object v = ScriptableObject.getProperty(obj, name);
        if (v instanceof Scriptable && v != Scriptable.NOT_FOUND) return (Scriptable) v;
        return null;
    }
}
