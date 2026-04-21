/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp.adapter;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.debug.DebugFrame;
import org.mozilla.javascript.debug.DebuggableScript;

public final class CdpDebugFrame implements DebugFrame {

    private final RhinoCdpAdapter adapter;
    final DebuggableScript script;
    Scriptable activation;
    Scriptable thisObj;
    int currentLine = -1;

    public CdpDebugFrame(RhinoCdpAdapter adapter, DebuggableScript script) {
        this.adapter = adapter;
        this.script = script;
    }

    @Override
    public void onEnter(Context cx, Scriptable activation, Scriptable thisObj, Object[] args) {
        this.activation = activation;
        this.thisObj = thisObj;
        adapter.pushFrame(this);
        adapter.pause().onEnter(cx, this);
    }

    @Override
    public void onLineChange(Context cx, int lineNumber) {
        this.currentLine = lineNumber;
        adapter.pause().onLineChange(cx, this);
    }

    @Override
    public void onExceptionThrown(Context cx, Throwable ex) {
        adapter.pause().onException(cx, this, ex);
    }

    @Override
    public void onExit(Context cx, boolean byThrow, Object resultOrException) {
        adapter.pause().onExit(cx, this, byThrow);
        adapter.popFrame(this);
    }

    @Override
    public void onDebuggerStatement(Context cx) {
        adapter.pause().onDebuggerStatement(cx, this);
    }

    public int currentLine() {
        return currentLine;
    }

    public Scriptable activation() {
        return activation;
    }

    public Scriptable thisObj() {
        return thisObj;
    }

    public DebuggableScript script() {
        return script;
    }
}
