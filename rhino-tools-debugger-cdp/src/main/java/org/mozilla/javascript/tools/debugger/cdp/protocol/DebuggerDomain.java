/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.debug.sourcemap.Mapping;
import org.mozilla.javascript.debug.sourcemap.SourceMap;
import org.mozilla.javascript.tools.debugger.cdp.pause.EvalRequest;
import org.mozilla.javascript.tools.debugger.cdp.pause.EvalResult;
import org.mozilla.javascript.tools.debugger.cdp.pause.PauseController;
import org.mozilla.javascript.tools.debugger.cdp.pause.PausedState;
import org.mozilla.javascript.tools.debugger.cdp.registry.Breakpoint;
import org.mozilla.javascript.tools.debugger.cdp.registry.BreakpointStore;
import org.mozilla.javascript.tools.debugger.cdp.registry.ScriptRecord;
import org.mozilla.javascript.tools.debugger.cdp.registry.ScriptRegistry;
import org.mozilla.javascript.tools.debugger.cdp.transport.CdpTransport;
import org.mozilla.javascript.tools.debugger.cdp.transport.JsonMarshaller;

public final class DebuggerDomain implements DomainHandler {

    public interface Emitter {
        void emit(String method, Map<String, Object> params);

        void replayScriptsParsed();
    }

    private final ScriptRegistry registry;
    private final BreakpointStore breakpoints;
    private final PauseController pause;
    private final Emitter emitter;
    private volatile boolean enabled;

    public DebuggerDomain(
            ScriptRegistry registry,
            BreakpointStore breakpoints,
            PauseController pause,
            Emitter emitter) {
        this.registry = registry;
        this.breakpoints = breakpoints;
        this.pause = pause;
        this.emitter = emitter;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void handle(String command, Scriptable params, CdpTransport.Replier replier) {
        switch (command) {
            case "enable":
                enabled = true;
                Map<String, Object> enRes = new LinkedHashMap<>();
                enRes.put("debuggerId", "rhino");
                replier.ok(enRes);
                emitter.replayScriptsParsed();
                return;
            case "disable":
                enabled = false;
                replier.ok(Collections.emptyMap());
                return;
            case "setAsyncCallStackDepth":
            case "setBlackboxPatterns":
            case "setBlackboxedRanges":
                replier.ok(Collections.emptyMap());
                return;
            case "setBreakpointsActive":
                breakpoints.setActive(JsonMarshaller.boolOr(params, "active", true));
                replier.ok(Collections.emptyMap());
                return;
            case "setSkipAllPauses":
                pause.setSkipAllPauses(JsonMarshaller.boolOr(params, "skip", false));
                replier.ok(Collections.emptyMap());
                return;
            case "setPauseOnExceptions":
                {
                    String state = JsonMarshaller.str(params, "state");
                    if ("all".equals(state)) {
                        pause.setPauseExceptions(PauseController.PauseExceptions.ALL);
                    } else if ("uncaught".equals(state)) {
                        pause.setPauseExceptions(PauseController.PauseExceptions.UNCAUGHT);
                    } else {
                        pause.setPauseExceptions(PauseController.PauseExceptions.NONE);
                    }
                    replier.ok(Collections.emptyMap());
                    return;
                }
            case "resume":
                pause.resume();
                replier.ok(Collections.emptyMap());
                return;
            case "stepOver":
                pause.stepOver();
                replier.ok(Collections.emptyMap());
                return;
            case "stepInto":
                pause.stepInto();
                replier.ok(Collections.emptyMap());
                return;
            case "stepOut":
                pause.stepOut();
                replier.ok(Collections.emptyMap());
                return;
            case "pause":
                pause.pauseOnNext();
                replier.ok(Collections.emptyMap());
                return;
            case "setBreakpoint":
                handleSetBreakpoint(params, replier);
                return;
            case "setBreakpointByUrl":
                handleSetBreakpointByUrl(params, replier);
                return;
            case "removeBreakpoint":
                handleRemoveBreakpoint(params, replier);
                return;
            case "continueToLocation":
                handleContinueToLocation(params, replier);
                return;
            case "getScriptSource":
                handleGetScriptSource(params, replier);
                return;
            case "evaluateOnCallFrame":
                handleEvaluateOnCallFrame(params, replier);
                return;
            case "getPossibleBreakpoints":
                handleGetPossibleBreakpoints(params, replier);
                return;
            case "getStackTrace":
                replier.ok(Collections.emptyMap());
                return;
            default:
                replier.err(-32601, "Debugger." + command + " not implemented");
        }
    }

    private void handleSetBreakpoint(Scriptable params, CdpTransport.Replier replier) {
        Scriptable loc = JsonMarshaller.child(params, "location");
        if (loc == null) {
            replier.err(-32602, "location required");
            return;
        }
        String scriptId = JsonMarshaller.str(loc, "scriptId");
        int line = JsonMarshaller.intOr(loc, "lineNumber", 0);
        int col = JsonMarshaller.intOr(loc, "columnNumber", 0);
        String cond = JsonMarshaller.str(params, "condition");
        ScriptRecord rec = registry.getById(scriptId);
        Breakpoint bp = breakpoints.addById(scriptId, line, col, cond, rec);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("breakpointId", bp.id);
        out.put("actualLocation", makeLocation(scriptId, bp.resolvedLine, col));
        replier.ok(out);
    }

    private void handleSetBreakpointByUrl(Scriptable params, CdpTransport.Replier replier) {
        String url = JsonMarshaller.str(params, "url");
        String urlRegex = JsonMarshaller.str(params, "urlRegex");
        int line = JsonMarshaller.intOr(params, "lineNumber", 0);
        int col = JsonMarshaller.intOr(params, "columnNumber", 0);
        String cond = JsonMarshaller.str(params, "condition");
        boolean isRegex = urlRegex != null;
        String pattern = isRegex ? urlRegex : url;
        if (pattern == null) {
            replier.err(-32602, "url or urlRegex required");
            return;
        }

        // Source-map translation: if a literal `url` matches an original source referenced by any
        // loaded script's source map, translate (origLine) into one or more generated locations on
        // the underlying compiled script and bind the breakpoint explicitly to that script.
        List<Map<String, Object>> locations = new ArrayList<>();
        List<ScriptRecord> urlMatches = new ArrayList<>();
        List<SourceMapBinding> sourceMapBindings = new ArrayList<>();
        if (!isRegex && url != null) {
            for (ScriptRecord r : registry.all()) {
                SourceMap sm = r.sourceMap;
                if (sm == null) continue;
                List<Mapping> gen = sm.generatedFor(url, line);
                if (gen.isEmpty()) continue;
                for (Mapping m : gen) {
                    int snapped = r.snapLine(m.generatedLine);
                    locations.add(makeLocation(r.scriptId, snapped, m.generatedColumn));
                    sourceMapBindings.add(new SourceMapBinding(r, snapped));
                }
            }
        }

        // Fall-through: plain URL match (no source map involvement).
        if (sourceMapBindings.isEmpty()) {
            if (isRegex) {
                for (ScriptRecord r : registry.all()) {
                    try {
                        if (java.util.regex.Pattern.compile(urlRegex).matcher(r.url).find()) {
                            urlMatches.add(r);
                        }
                    } catch (RuntimeException ignored) {
                    }
                }
            } else {
                urlMatches.addAll(registry.findByUrl(url));
            }
            for (ScriptRecord r : urlMatches) {
                int snapped = r.snapLine(line);
                locations.add(makeLocation(r.scriptId, snapped, col));
            }
        }

        Breakpoint bp = breakpoints.addByUrl(pattern, line, col, cond, isRegex, urlMatches);
        for (SourceMapBinding b : sourceMapBindings) {
            breakpoints.bindAt(bp, b.rec, b.generatedLine);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("breakpointId", bp.id);
        out.put("locations", locations);
        replier.ok(out);
    }

    private static final class SourceMapBinding {
        final ScriptRecord rec;
        final int generatedLine;

        SourceMapBinding(ScriptRecord rec, int generatedLine) {
            this.rec = rec;
            this.generatedLine = generatedLine;
        }
    }

    private void handleRemoveBreakpoint(Scriptable params, CdpTransport.Replier replier) {
        String id = JsonMarshaller.str(params, "breakpointId");
        if (id != null) breakpoints.remove(id);
        replier.ok(Collections.emptyMap());
    }

    private void handleContinueToLocation(Scriptable params, CdpTransport.Replier replier) {
        Scriptable loc = JsonMarshaller.child(params, "location");
        if (loc == null) {
            replier.err(-32602, "location required");
            return;
        }
        String scriptId = JsonMarshaller.str(loc, "scriptId");
        int line = JsonMarshaller.intOr(loc, "lineNumber", 0);
        PausedState s = pause.currentPaused();
        if (s != null) {
            pause.setContinueToLocation(s.thread, scriptId, line);
        }
        pause.resume();
        replier.ok(Collections.emptyMap());
    }

    private void handleGetScriptSource(Scriptable params, CdpTransport.Replier replier) {
        String scriptId = JsonMarshaller.str(params, "scriptId");
        ScriptRecord rec = scriptId == null ? null : registry.getById(scriptId);
        if (rec == null) {
            replier.err(-32000, "Unknown scriptId: " + scriptId);
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scriptSource", rec.source);
        replier.ok(out);
    }

    private void handleEvaluateOnCallFrame(Scriptable params, CdpTransport.Replier replier) {
        String frameId = JsonMarshaller.str(params, "callFrameId");
        String expr = JsonMarshaller.str(params, "expression");
        PausedState s = pause.paused(frameId);
        if (s == null) {
            replier.err(-32000, "No paused frame");
            return;
        }
        EvalRequest req = new EvalRequest(frameId, expr);
        pause.enqueueEval(s, req);
        try {
            EvalResult res = req.future.get(30, TimeUnit.SECONDS);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("result", res.remoteObject);
            if (res.exceptionDetails != null) out.put("exceptionDetails", res.exceptionDetails);
            replier.ok(out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            replier.err(-32000, "interrupted");
        } catch (ExecutionException e) {
            replier.err(-32000, String.valueOf(e.getCause()));
        } catch (TimeoutException e) {
            replier.err(-32000, "evaluate timed out");
        }
    }

    private void handleGetPossibleBreakpoints(Scriptable params, CdpTransport.Replier replier) {
        Scriptable start = JsonMarshaller.child(params, "start");
        if (start == null) {
            replier.ok(Collections.singletonMap("locations", Collections.emptyList()));
            return;
        }
        String scriptId = JsonMarshaller.str(start, "scriptId");
        int startLine = JsonMarshaller.intOr(start, "lineNumber", 0);
        Scriptable end = JsonMarshaller.child(params, "end");
        int endLine = end == null ? Integer.MAX_VALUE : JsonMarshaller.intOr(end, "lineNumber", 0);
        ScriptRecord rec = scriptId == null ? null : registry.getById(scriptId);
        List<Map<String, Object>> locations = new ArrayList<>();
        if (rec != null) {
            for (int line : rec.validLines) {
                if (line >= startLine && line <= endLine) {
                    locations.add(makeLocation(scriptId, line, 0));
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("locations", locations);
        replier.ok(out);
    }

    public static Map<String, Object> makeLocation(String scriptId, int line, int col) {
        Map<String, Object> loc = new LinkedHashMap<>();
        loc.put("scriptId", scriptId);
        loc.put("lineNumber", line);
        loc.put("columnNumber", col);
        return loc;
    }
}
