/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.VarScope;
import org.mozilla.javascript.tools.debugger.cdp.adapter.CdpDebugFrame;
import org.mozilla.javascript.tools.debugger.cdp.adapter.ConsoleBinding;
import org.mozilla.javascript.tools.debugger.cdp.adapter.RemoteObjectStore;
import org.mozilla.javascript.tools.debugger.cdp.adapter.RemoteObjects;
import org.mozilla.javascript.tools.debugger.cdp.adapter.RhinoCdpAdapter;
import org.mozilla.javascript.tools.debugger.cdp.adapter.ScopeChain;
import org.mozilla.javascript.tools.debugger.cdp.pause.EvalResult;
import org.mozilla.javascript.tools.debugger.cdp.pause.PauseController;
import org.mozilla.javascript.tools.debugger.cdp.pause.PausedState;
import org.mozilla.javascript.tools.debugger.cdp.protocol.CdpDispatcher;
import org.mozilla.javascript.tools.debugger.cdp.protocol.ConsoleEvaluator;
import org.mozilla.javascript.tools.debugger.cdp.protocol.DebuggerDomain;
import org.mozilla.javascript.tools.debugger.cdp.protocol.RuntimeDomain;
import org.mozilla.javascript.tools.debugger.cdp.protocol.StubDomains;
import org.mozilla.javascript.tools.debugger.cdp.registry.BreakpointStore;
import org.mozilla.javascript.tools.debugger.cdp.registry.ScriptRecord;
import org.mozilla.javascript.tools.debugger.cdp.registry.ScriptRegistry;
import org.mozilla.javascript.tools.debugger.cdp.transport.CdpTransport;

/**
 * Public entry point. Attach a {@link CdpDebugger} to a {@link ContextFactory} and it will:
 *
 * <ul>
 *   <li>Open an HTTP + WebSocket server on {@code cfg.host:cfg.port}.
 *   <li>Register a {@link org.mozilla.javascript.debug.Debugger} on the factory.
 *   <li>Force every {@code Context} created by the factory to interpreted mode ({@code
 *       optimizationLevel = -1}), since {@code onLineChange} does not fire otherwise.
 * </ul>
 */
public final class CdpDebugger implements Closeable {

    private static final Logger LOG = Logger.getLogger(CdpDebugger.class.getName());

    private final CdpDebuggerConfig cfg;
    private final ContextFactory factory;
    private final ScriptRegistry registry;
    private final BreakpointStore breakpoints;
    private final RemoteObjectStore objectStore;
    private final RhinoCdpAdapter adapter;
    private final PauseController pause;
    private final CdpDispatcher dispatcher;
    private final CdpTransport transport;
    private final DebuggerDomain debuggerDomain;
    private final RuntimeDomain runtimeDomain;
    private final ConsoleEvaluator console;
    private final ContextFactory.Listener factoryListener;
    private final CountDownLatch attachLatch = new CountDownLatch(1);
    private final CountDownLatch runDebuggerLatch = new CountDownLatch(1);

    public static CdpDebugger attach(ContextFactory factory, CdpDebuggerConfig cfg)
            throws IOException {
        if (!cfg.enabled) return new CdpDebugger(factory, cfg, true);
        CdpDebugger d = new CdpDebugger(factory, cfg, false);
        d.start();
        return d;
    }

    private CdpDebugger(ContextFactory factory, CdpDebuggerConfig cfg, boolean disabled)
            throws IOException {
        this.factory = factory;
        this.cfg = cfg;

        this.registry = new ScriptRegistry();
        this.breakpoints = new BreakpointStore();
        this.objectStore = new RemoteObjectStore();

        this.adapter = new RhinoCdpAdapter(registry, this::onScriptParsedFromJsThread);

        // Dispatcher first so emitter can reference it via transport indirectly.
        this.dispatcher = new CdpDispatcher();
        this.transport = new CdpTransport(cfg.host, cfg.port, factory, dispatcher);
        this.console = new ConsoleEvaluator(factory, objectStore);

        // PauseController needs an emitter that owns the transport.
        PauseController.Emitter pauseEmitter =
                new PauseController.Emitter() {
                    @Override
                    public void emitPaused(PausedState state) {
                        transport.enqueueEvent("Debugger.paused", buildPausedParams(state));
                    }

                    @Override
                    public void emitResumed() {
                        transport.enqueueEvent("Debugger.resumed", new LinkedHashMap<>());
                    }

                    @Override
                    public EvalResult runEval(Context cx, CdpDebugFrame frame, String expression) {
                        return CdpDebugger.this.runEvalOnCurrentThread(cx, frame, expression);
                    }
                };
        this.pause = new PauseController(adapter, breakpoints, pauseEmitter);
        this.adapter.setPauseController(pause);

        DebuggerDomain.Emitter domEmitter =
                new DebuggerDomain.Emitter() {
                    @Override
                    public void emit(String method, Map<String, Object> params) {
                        transport.enqueueEvent(method, params);
                    }

                    @Override
                    public void replayScriptsParsed() {
                        for (ScriptRecord rec : registry.all()) {
                            transport.enqueueEvent(
                                    "Debugger.scriptParsed", buildScriptParsedParams(rec));
                        }
                    }
                };
        this.debuggerDomain = new DebuggerDomain(registry, breakpoints, pause, domEmitter);
        this.runtimeDomain =
                new RuntimeDomain(objectStore, pause, console, runDebuggerLatch::countDown);

        dispatcher.register("Debugger", debuggerDomain);
        dispatcher.register("Runtime", runtimeDomain);
        StubDomains.registerAll(dispatcher);
        dispatcher.setSessionListener(
                new CdpDispatcher.SessionListener() {
                    @Override
                    public void onConnected() {
                        attachLatch.countDown();
                        Map<String, Object> ctx = new LinkedHashMap<>();
                        Map<String, Object> ectx = new LinkedHashMap<>();
                        ectx.put("id", 1);
                        ectx.put("origin", "rhino://");
                        ectx.put("name", "Rhino");
                        ctx.put("context", ectx);
                        transport.enqueueEvent("Runtime.executionContextCreated", ctx);
                    }

                    @Override
                    public void onDisconnected() {
                        Map<String, Object> p = new LinkedHashMap<>();
                        p.put("executionContextId", 1);
                        transport.enqueueEvent("Runtime.executionContextDestroyed", p);
                    }
                });

        this.factoryListener =
                new ContextFactory.Listener() {
                    @Override
                    public void contextCreated(Context cx) {
                        cx.setInterpretedMode(true);
                        cx.setGeneratingDebug(true);
                        cx.setDebugger(adapter, null);
                    }

                    @Override
                    public void contextReleased(Context cx) {}
                };

        if (!disabled) {
            // Retrofit any Contexts that were created before we attached.
            factory.addListener(factoryListener);
        }
    }

    private void start() throws IOException {
        transport.start();
    }

    public int port() {
        return transport.port();
    }

    public String webSocketDebuggerUrl() {
        return transport.webSocketDebuggerUrl();
    }

    public boolean isClientConnected() {
        return transport.isClientConnected();
    }

    /**
     * Install a {@code console} global on the supplied scope, forwarding calls as {@code
     * Runtime.consoleAPICalled} events to attached clients. Must be called from a thread that owns
     * a Rhino {@link Context} for the scope (typically the JS execution thread before any script
     * runs).
     */
    public void installConsole(Context cx, Scriptable global) {
        ConsoleBinding.install(
                cx,
                global,
                objectStore,
                (level, args) ->
                        transport.enqueueEvent(
                                "Runtime.consoleAPICalled",
                                ConsoleBinding.buildConsoleApiCalledParams(level, args)));
    }

    /**
     * Blocks the calling thread until a client has connected AND issued {@code
     * Runtime.runIfWaitingForDebugger}. Intended for {@code cfg.waitForAttach == true} scenarios.
     */
    public void waitForClient() throws InterruptedException {
        if (!cfg.waitForAttach) return;
        attachLatch.await();
        runDebuggerLatch.await();
    }

    @Override
    public void close() {
        try {
            factory.removeListener(factoryListener);
        } catch (RuntimeException ignored) {
        }
        console.stop();
        transport.stop();
    }

    // ---- Event builders ----

    private void onScriptParsedFromJsThread(ScriptRecord rec) {
        // We always enqueue; the writer thread builds the Scriptable payload.
        transport.enqueueEvent("Debugger.scriptParsed", buildScriptParsedParams(rec));
        // Resolve any pending URL breakpoints and emit breakpointResolved events.
        List<?> resolved = breakpoints.onNewScript(rec);
        for (Object o : resolved) {
            org.mozilla.javascript.tools.debugger.cdp.registry.Breakpoint bp =
                    (org.mozilla.javascript.tools.debugger.cdp.registry.Breakpoint) o;
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("breakpointId", bp.id);
            params.put("location", DebuggerDomain.makeLocation(rec.scriptId, bp.resolvedLine, 0));
            transport.enqueueEvent("Debugger.breakpointResolved", params);
        }
    }

    private static Map<String, Object> buildScriptParsedParams(ScriptRecord rec) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("scriptId", rec.scriptId);
        p.put("url", rec.url);
        p.put("startLine", 0);
        p.put("startColumn", 0);
        p.put("endLine", rec.endLine);
        p.put("endColumn", 0);
        p.put("executionContextId", 1);
        p.put("hash", rec.hash);
        p.put("isLiveEdit", false);
        if (rec.sourceMappingURL != null) p.put("sourceMapURL", rec.sourceMappingURL);
        p.put("hasSourceURL", rec.sourceURLOverride != null);
        p.put("isModule", false);
        p.put("length", rec.source.length());
        return p;
    }

    private Map<String, Object> buildPausedParams(PausedState state) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("reason", state.reason);
        if (state.auxData != null) params.put("data", state.auxData);
        List<Map<String, Object>> callFrames = new ArrayList<>();
        for (int i = 0; i < state.frames.size(); i++) {
            CdpDebugFrame f = state.frames.get(i);
            Map<String, Object> cf = new LinkedHashMap<>();
            @SuppressWarnings("deprecation")
            long tid = state.thread.getId();
            cf.put("callFrameId", "{\"ordinal\":" + i + ",\"threadId\":" + tid + "}");
            String fn = f.script().getFunctionName();
            cf.put("functionName", fn == null ? "" : fn);
            ScriptRecord rec = adapter.recordFor(f.script());
            String scriptId = rec == null ? "0" : rec.scriptId;
            String url = rec == null ? "" : rec.url;
            cf.put("location", DebuggerDomain.makeLocation(scriptId, f.currentLine(), 0));
            cf.put("url", url);
            cf.put("scopeChain", ScopeChain.build(f, "frame:" + i, objectStore));
            cf.put(
                    "this",
                    f.thisObj() == null
                            ? RemoteObjects.wrap(null, "frame:" + i, objectStore)
                            : RemoteObjects.wrap(f.thisObj(), "frame:" + i, objectStore));
            callFrames.add(cf);
        }
        params.put("callFrames", callFrames);
        params.put("hitBreakpoints", new ArrayList<>());
        return params;
    }

    // ---- Evaluation on paused JS thread ----

    private EvalResult runEvalOnCurrentThread(Context cx, CdpDebugFrame frame, String expression) {
        Scriptable scope = frame != null ? frame.activation() : null;
        if (scope == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("exceptionId", 1);
            err.put("text", "no scope for evaluation");
            return new EvalResult(
                    RemoteObjects.wrap("no scope for evaluation", "eval", objectStore), err);
        }
        try {
            Object v = cx.evaluateString((VarScope) scope, expression, "<cdp-eval>", 1, null);
            return new EvalResult(RemoteObjects.wrap(v, "eval", objectStore), null);
        } catch (RhinoException re) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("exceptionId", 1);
            details.put("text", "Uncaught");
            details.put("lineNumber", Math.max(0, re.lineNumber() - 1));
            details.put("columnNumber", Math.max(0, re.columnNumber()));
            Map<String, Object> ex = new LinkedHashMap<>();
            ex.put("type", "object");
            ex.put("subtype", "error");
            ex.put("className", "Error");
            ex.put("description", re.getMessage());
            details.put("exception", ex);
            return new EvalResult(ex, details);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "eval failed", e);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("exceptionId", 1);
            details.put("text", e.getMessage());
            return new EvalResult(
                    RemoteObjects.wrap(String.valueOf(e), "eval", objectStore), details);
        }
    }
}
