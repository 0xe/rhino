/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp.adapter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.debug.DebugFrame;
import org.mozilla.javascript.debug.DebuggableScript;
import org.mozilla.javascript.debug.Debugger;
import org.mozilla.javascript.tools.debugger.cdp.pause.PauseController;
import org.mozilla.javascript.tools.debugger.cdp.registry.ScriptRecord;
import org.mozilla.javascript.tools.debugger.cdp.registry.ScriptRegistry;

/**
 * The single {@link Debugger} registered with the hosting {@link
 * org.mozilla.javascript.ContextFactory}. Bridges Rhino's debug callbacks into the CDP subsystems.
 */
public final class RhinoCdpAdapter implements Debugger {

    public interface ScriptParsedListener {
        void onScriptParsed(ScriptRecord rec);
    }

    private final ScriptRegistry registry;
    private final ScriptParsedListener scriptParsedListener;
    private PauseController pause;

    private final Map<Thread, Deque<CdpDebugFrame>> stacks = new ConcurrentHashMap<>();

    public RhinoCdpAdapter(ScriptRegistry registry, ScriptParsedListener listener) {
        this.registry = registry;
        this.scriptParsedListener = listener;
    }

    public void setPauseController(PauseController pause) {
        this.pause = pause;
    }

    public PauseController pause() {
        return pause;
    }

    public ScriptRegistry registry() {
        return registry;
    }

    public ScriptRecord recordFor(DebuggableScript script) {
        ScriptRecord rec = registry.findByScript(script);
        if (rec != null) return rec;
        return registry.findOwningRecord(script);
    }

    public Deque<CdpDebugFrame> stackFor(Thread t) {
        return stacks.get(t);
    }

    void pushFrame(CdpDebugFrame f) {
        Thread t = Thread.currentThread();
        Deque<CdpDebugFrame> stack = stacks.computeIfAbsent(t, k -> new ArrayDeque<>());
        stack.push(f);
    }

    void popFrame(CdpDebugFrame f) {
        Thread t = Thread.currentThread();
        Deque<CdpDebugFrame> stack = stacks.get(t);
        if (stack != null) {
            stack.remove(f);
            if (stack.isEmpty()) stacks.remove(t);
        }
    }

    // ---- Debugger interface ----

    @Override
    public void handleCompilationDone(Context cx, DebuggableScript fnOrScript, String source) {
        if (!fnOrScript.isTopLevel()) return;
        ScriptRecord rec = registry.register(fnOrScript, source);
        if (scriptParsedListener != null) scriptParsedListener.onScriptParsed(rec);
    }

    @Override
    public DebugFrame getFrame(Context cx, DebuggableScript fnOrScript) {
        return new CdpDebugFrame(this, fnOrScript);
    }
}
