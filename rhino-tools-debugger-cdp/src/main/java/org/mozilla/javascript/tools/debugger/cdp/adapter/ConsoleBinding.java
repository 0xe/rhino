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
import java.util.function.BiConsumer;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.TopLevel;
import org.mozilla.javascript.VarScope;

/**
 * Installs a minimal {@code console} object on a global scope. Each call ({@code log}, {@code
 * warn}, {@code error}, {@code info}, {@code debug}) is forwarded to a user-supplied consumer that
 * typically translates into a CDP {@code Runtime.consoleAPICalled} event.
 *
 * <p>Values are passed through as plain Java (strings already toString()-converted) — the consumer
 * itself is responsible for Rhino-thread-safe marshaling of further detail if it wants.
 */
public final class ConsoleBinding {

    private ConsoleBinding() {}

    /**
     * @param emit called with (levelString, list-of-RemoteObject-maps). The caller is responsible
     *     for hopping to the transport thread if needed.
     */
    public static void install(
            Context cx,
            Scriptable global,
            RemoteObjectStore store,
            BiConsumer<String, List<Map<String, Object>>> emit) {
        NativeObject console = new NativeObject();
        ScriptRuntime.setBuiltinProtoAndParent(console, global, TopLevel.Builtins.Object);
        String[] levels = {"log", "warn", "error", "info", "debug", "trace"};
        for (String level : levels) {
            ScriptableObject.putProperty(console, level, new ConsoleFn(level, store, emit));
        }
        ScriptableObject.putProperty(global, "console", console);
    }

    private static final class ConsoleFn extends BaseFunction {
        private final String level;
        private final RemoteObjectStore store;
        private final BiConsumer<String, List<Map<String, Object>>> emit;

        ConsoleFn(
                String level,
                RemoteObjectStore store,
                BiConsumer<String, List<Map<String, Object>>> emit) {
            this.level = level;
            this.store = store;
            this.emit = emit;
        }

        @Override
        public Object call(Context cx, VarScope scope, Scriptable thisObj, Object[] args) {
            List<Map<String, Object>> argObjs = new ArrayList<>(args.length);
            for (Object a : args) {
                argObjs.add(RemoteObjects.wrap(a, "console", store));
            }
            try {
                emit.accept(level, argObjs);
            } catch (RuntimeException ignored) {
            }
            return org.mozilla.javascript.Undefined.instance;
        }

        @Override
        public String getFunctionName() {
            return level;
        }
    }

    /** Build a {@code Runtime.consoleAPICalled} params object around the forwarded args. */
    public static Map<String, Object> buildConsoleApiCalledParams(
            String level, List<Map<String, Object>> args) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", level);
        params.put("args", args);
        params.put("executionContextId", 1);
        params.put("timestamp", System.currentTimeMillis());
        return params;
    }
}
