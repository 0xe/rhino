/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp.pause;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.tools.debugger.cdp.adapter.CdpDebugFrame;
import org.mozilla.javascript.tools.debugger.cdp.adapter.RhinoCdpAdapter;
import org.mozilla.javascript.tools.debugger.cdp.registry.Breakpoint;
import org.mozilla.javascript.tools.debugger.cdp.registry.BreakpointStore;
import org.mozilla.javascript.tools.debugger.cdp.registry.ScriptRecord;

/**
 * Coordinates between the dispatcher thread (which issues resume/step/evaluate commands) and one or
 * more JS threads (which block here while paused).
 *
 * <p>Step state, pause-on-exception policy, and the pause-request flag live here. The owner {@link
 * RhinoCdpAdapter} supplies the list of active frames per thread and the emit callback for {@code
 * Debugger.paused} / {@code Debugger.resumed} events.
 */
public final class PauseController {

    public interface Emitter {
        void emitPaused(PausedState state);

        void emitResumed();

        /** Runs the eval on the calling (paused) JS thread and returns its result. */
        EvalResult runEval(Context cx, CdpDebugFrame frame, String expression);
    }

    public enum PauseExceptions {
        NONE,
        ALL,
        UNCAUGHT
    }

    private final RhinoCdpAdapter adapter;
    private final BreakpointStore breakpoints;
    private final Emitter emitter;

    // Per-thread pause and step state.
    private final Map<Thread, PausedState> pausedByThread = new ConcurrentHashMap<>();
    private final Map<Thread, StepMode> activeStep = new ConcurrentHashMap<>();
    private final Map<Thread, Integer> stepRefDepth = new ConcurrentHashMap<>();
    // Pending continueToLocation: scriptId + target line; when hit on the same thread, pause.
    private final Map<Thread, long[]> continueTarget = new ConcurrentHashMap<>();

    private volatile PauseExceptions pauseExceptions = PauseExceptions.NONE;
    private volatile boolean skipAllPauses;
    private volatile boolean breakpointsActive = true;
    private volatile boolean pauseOnNext;

    public PauseController(RhinoCdpAdapter adapter, BreakpointStore breakpoints, Emitter emitter) {
        this.adapter = adapter;
        this.breakpoints = breakpoints;
        this.emitter = emitter;
    }

    // ---- Configuration from dispatcher ----

    public void setPauseExceptions(PauseExceptions p) {
        this.pauseExceptions = p;
    }

    public void setSkipAllPauses(boolean v) {
        this.skipAllPauses = v;
    }

    public void setBreakpointsActive(boolean v) {
        this.breakpointsActive = v;
    }

    public void pauseOnNext() {
        this.pauseOnNext = true;
    }

    /** Wake every paused thread with the given step mode. (CDP only pauses one at a time.) */
    public void resumeAll(StepMode mode) {
        for (PausedState s : pausedByThread.values()) resume(s, mode);
    }

    private void resume(PausedState s, StepMode mode) {
        synchronized (s.lock) {
            s.resume = mode;
            s.lock.notifyAll();
        }
    }

    public void resume() {
        resumeAll(StepMode.CONTINUE);
    }

    public void stepOver() {
        resumeAll(StepMode.STEP_OVER);
    }

    public void stepInto() {
        resumeAll(StepMode.STEP_INTO);
    }

    public void stepOut() {
        resumeAll(StepMode.STEP_OUT);
    }

    public PausedState currentPaused() {
        Collection<PausedState> all = pausedByThread.values();
        if (all.isEmpty()) return null;
        return all.iterator().next();
    }

    public PausedState paused(String callFrameId) {
        // callFrameId format: "{\"ordinal\":N,\"threadId\":T}" — we can cheat: any paused state
        // will do in single-JS-thread usage, which is what the POC supports.
        return currentPaused();
    }

    public void enqueueEval(PausedState s, EvalRequest req) {
        s.evalQueue.offer(req);
        synchronized (s.lock) {
            s.lock.notifyAll();
        }
    }

    public void setContinueToLocation(Thread thread, String scriptId, int line) {
        long sid;
        try {
            sid = Long.parseLong(scriptId);
        } catch (NumberFormatException e) {
            return;
        }
        continueTarget.put(thread, new long[] {sid, line});
    }

    // ---- Callbacks from JS threads (via CdpDebugFrame) ----

    public void onEnter(Context cx, CdpDebugFrame frame) {
        Thread t = Thread.currentThread();
        StepMode step = activeStep.get(t);
        if (step == StepMode.STEP_INTO) {
            // pause on first line of entered function — let onLineChange handle that.
        }
    }

    public void onExit(Context cx, CdpDebugFrame frame, boolean byThrow) {
        Thread t = Thread.currentThread();
        StepMode step = activeStep.get(t);
        if (step == StepMode.STEP_OUT) {
            Integer refDepth = stepRefDepth.get(t);
            Deque<CdpDebugFrame> stack = adapter.stackFor(t);
            int currentDepth = stack == null ? 0 : stack.size();
            if (refDepth != null && currentDepth <= refDepth) {
                // We exited the reference frame; convert to step-over on the next line.
                activeStep.put(t, StepMode.STEP_OVER);
                stepRefDepth.put(t, currentDepth - 1);
            }
        }
    }

    public void onLineChange(Context cx, CdpDebugFrame frame) {
        Thread t = Thread.currentThread();
        if (skipAllPauses) return;

        // Breakpoint check
        if (breakpointsActive) {
            ScriptRecord rec = adapter.recordFor(frame.script());
            if (rec != null) {
                List<Breakpoint> hits = breakpoints.hitsAt(rec.scriptId, frame.currentLine());
                if (!hits.isEmpty()) {
                    List<Map<String, Object>> auxHits = new ArrayList<>();
                    for (Breakpoint bp : hits) {
                        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("id", bp.id);
                        auxHits.add(m);
                    }
                    pause(cx, t, "other", auxHits);
                    return;
                }
            }
        }

        // continueToLocation check
        long[] target = continueTarget.get(t);
        if (target != null) {
            ScriptRecord rec = adapter.recordFor(frame.script());
            if (rec != null
                    && Long.parseLong(rec.scriptId) == target[0]
                    && frame.currentLine() == (int) target[1]) {
                continueTarget.remove(t);
                pause(cx, t, "other", null);
                return;
            }
        }

        // Step check
        StepMode step = activeStep.get(t);
        if (step != null && step != StepMode.NONE) {
            Deque<CdpDebugFrame> stack = adapter.stackFor(t);
            int depth = stack == null ? 0 : stack.size();
            Integer refDepth = stepRefDepth.get(t);
            boolean doPause = false;
            switch (step) {
                case STEP_INTO:
                    doPause = true;
                    break;
                case STEP_OVER:
                    doPause = refDepth == null || depth <= refDepth;
                    break;
                case STEP_OUT:
                    doPause = refDepth != null && depth < refDepth;
                    break;
                default:
                    break;
            }
            if (doPause) {
                activeStep.remove(t);
                stepRefDepth.remove(t);
                pause(cx, t, "other", null);
                return;
            }
        }

        if (pauseOnNext) {
            pauseOnNext = false;
            pause(cx, t, "other", null);
        }
    }

    public void onDebuggerStatement(Context cx, CdpDebugFrame frame) {
        pause(cx, Thread.currentThread(), "debuggerStatement", null);
    }

    public void onException(Context cx, CdpDebugFrame frame, Throwable ex) {
        if (pauseExceptions == PauseExceptions.NONE) return;
        // For POC we treat ALL and UNCAUGHT identically (see pitfall #6).
        Map<String, Object> aux = new java.util.LinkedHashMap<>();
        aux.put("exception", buildExceptionRemoteObject(ex instanceof RhinoException ? ex : ex));
        pause(cx, Thread.currentThread(), "exception", aux);
    }

    private Map<String, Object> buildExceptionRemoteObject(Throwable t) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("type", "object");
        m.put("subtype", "error");
        m.put("className", t.getClass().getSimpleName());
        m.put("description", String.valueOf(t.getMessage()));
        return m;
    }

    // ---- Core pause mechanic ----

    private void pause(Context cx, Thread t, String reason, Object aux) {
        Deque<CdpDebugFrame> stack = adapter.stackFor(t);
        List<CdpDebugFrame> snapshot = stack == null ? new ArrayList<>() : new ArrayList<>(stack);
        PausedState state = new PausedState(t, snapshot, reason, aux);
        pausedByThread.put(t, state);
        try {
            emitter.emitPaused(state);

            // Block until dispatcher resumes or steps us.
            synchronized (state.lock) {
                while (state.resume == StepMode.NONE) {
                    // Service eval requests first so latency is low.
                    EvalRequest req = state.evalQueue.poll();
                    if (req != null) {
                        CdpDebugFrame targetFrame = resolveFrameById(state, req.callFrameId);
                        if (targetFrame == null && !state.frames.isEmpty()) {
                            targetFrame = state.frames.get(0);
                        }
                        try {
                            EvalResult res = emitter.runEval(cx, targetFrame, req.expression);
                            req.future.complete(res);
                        } catch (RuntimeException e) {
                            req.future.completeExceptionally(e);
                        }
                        continue;
                    }
                    try {
                        state.lock.wait(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }

            // Configure step state based on the resume mode.
            StepMode mode = state.resume;
            int depth = snapshot.size();
            switch (mode) {
                case STEP_OVER:
                case STEP_OUT:
                case STEP_INTO:
                    activeStep.put(t, mode);
                    stepRefDepth.put(t, depth);
                    break;
                default:
                    activeStep.remove(t);
                    stepRefDepth.remove(t);
                    break;
            }
        } finally {
            pausedByThread.remove(t);
            emitter.emitResumed();
        }
    }

    private static CdpDebugFrame resolveFrameById(PausedState state, String id) {
        if (id == null) return null;
        int idx = id.indexOf("\"ordinal\":");
        if (idx < 0) return null;
        int start = idx + "\"ordinal\":".length();
        int end = start;
        while (end < id.length() && Character.isDigit(id.charAt(end))) end++;
        try {
            int ord = Integer.parseInt(id.substring(start, end));
            if (ord >= 0 && ord < state.frames.size()) return state.frames.get(ord);
        } catch (NumberFormatException ignored) {
        }
        return null;
    }
}
