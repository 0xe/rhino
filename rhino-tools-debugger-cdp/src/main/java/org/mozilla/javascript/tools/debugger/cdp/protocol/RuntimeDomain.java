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
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.tools.debugger.cdp.adapter.RemoteObjectStore;
import org.mozilla.javascript.tools.debugger.cdp.adapter.RemoteObjects;
import org.mozilla.javascript.tools.debugger.cdp.pause.EvalRequest;
import org.mozilla.javascript.tools.debugger.cdp.pause.EvalResult;
import org.mozilla.javascript.tools.debugger.cdp.pause.PauseController;
import org.mozilla.javascript.tools.debugger.cdp.pause.PausedState;
import org.mozilla.javascript.tools.debugger.cdp.transport.CdpTransport;
import org.mozilla.javascript.tools.debugger.cdp.transport.JsonMarshaller;

public final class RuntimeDomain implements DomainHandler {

    public interface WaitForDebuggerGate {
        void release();
    }

    private final RemoteObjectStore store;
    private final PauseController pause;
    private final ConsoleEvaluator console;
    private final WaitForDebuggerGate gate;

    public RuntimeDomain(
            RemoteObjectStore store,
            PauseController pause,
            ConsoleEvaluator console,
            WaitForDebuggerGate gate) {
        this.store = store;
        this.pause = pause;
        this.console = console;
        this.gate = gate;
    }

    @Override
    public void handle(String command, Scriptable params, CdpTransport.Replier replier) {
        switch (command) {
            case "enable":
            case "disable":
            case "setCustomObjectFormatterEnabled":
            case "setMaxCallStackSizeToCapture":
            case "discardConsoleEntries":
                replier.ok(Collections.emptyMap());
                return;
            case "releaseObject":
                store.release(JsonMarshaller.str(params, "objectId"));
                replier.ok(Collections.emptyMap());
                return;
            case "releaseObjectGroup":
                store.releaseGroup(JsonMarshaller.str(params, "objectGroup"));
                replier.ok(Collections.emptyMap());
                return;
            case "runIfWaitingForDebugger":
                if (gate != null) gate.release();
                replier.ok(Collections.emptyMap());
                return;
            case "getProperties":
                handleGetProperties(params, replier);
                return;
            case "evaluate":
                handleEvaluate(params, replier);
                return;
            case "callFunctionOn":
                handleCallFunctionOn(params, replier);
                return;
            case "getIsolateId":
                replier.ok(Collections.singletonMap("id", "rhino-isolate"));
                return;
            case "getHeapUsage":
                Map<String, Object> hu = new LinkedHashMap<>();
                hu.put("usedSize", 0);
                hu.put("totalSize", 0);
                replier.ok(hu);
                return;
            case "globalLexicalScopeNames":
                replier.ok(Collections.singletonMap("names", Collections.emptyList()));
                return;
            default:
                replier.err(-32601, "Runtime." + command + " not implemented");
        }
    }

    private void handleGetProperties(Scriptable params, CdpTransport.Replier replier) {
        String objectId = JsonMarshaller.str(params, "objectId");
        boolean ownProperties = JsonMarshaller.boolOr(params, "ownProperties", false);
        Object value = store.resolve(objectId);
        if (!(value instanceof Scriptable)) {
            replier.ok(Collections.singletonMap("result", Collections.emptyList()));
            return;
        }
        Scriptable s = (Scriptable) value;
        List<Map<String, Object>> props = new ArrayList<>();
        Object[] ids;
        try {
            ids = s.getIds();
        } catch (RuntimeException e) {
            ids = new Object[0];
        }
        for (Object id : ids) {
            Map<String, Object> desc = new LinkedHashMap<>();
            String name;
            Object v;
            try {
                if (id instanceof Integer) {
                    name = id.toString();
                    v = ScriptableObject.getProperty(s, (Integer) id);
                } else {
                    name = id.toString();
                    v = ScriptableObject.getProperty(s, name);
                }
            } catch (RuntimeException e) {
                continue;
            }
            desc.put("name", name);
            desc.put("value", RemoteObjects.wrap(v, "props", store));
            desc.put("writable", true);
            desc.put("configurable", true);
            desc.put("enumerable", true);
            desc.put("isOwn", true);
            props.add(desc);
        }
        if (!ownProperties) {
            Scriptable proto = s.getPrototype();
            if (proto != null) {
                Map<String, Object> protoDesc = new LinkedHashMap<>();
                protoDesc.put("name", "__proto__");
                protoDesc.put("value", RemoteObjects.wrap(proto, "props", store));
                protoDesc.put("writable", true);
                protoDesc.put("configurable", true);
                protoDesc.put("enumerable", false);
                protoDesc.put("isOwn", true);
                props.add(protoDesc);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", props);
        replier.ok(out);
    }

    private void handleEvaluate(Scriptable params, CdpTransport.Replier replier) {
        String expr = JsonMarshaller.str(params, "expression");
        PausedState s = pause.currentPaused();
        if (s != null) {
            // Route through the paused JS thread using the current top frame.
            EvalRequest req = new EvalRequest(null, expr);
            pause.enqueueEval(s, req);
            replyFromFuture(req, replier);
        } else {
            java.util.concurrent.CompletableFuture<EvalResult> f = console.submit(expr);
            try {
                EvalResult res = f.get(30, TimeUnit.SECONDS);
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
    }

    private void handleCallFunctionOn(Scriptable params, CdpTransport.Replier replier) {
        // Minimal POC stub: evaluate functionDeclaration as an expression in the paused context.
        // A full implementation would invoke with explicit `this` and args; deferred.
        String fd = JsonMarshaller.str(params, "functionDeclaration");
        if (fd == null) {
            replier.err(-32602, "functionDeclaration required");
            return;
        }
        String expr = "(" + fd + ").call(this)";
        PausedState s = pause.currentPaused();
        if (s != null) {
            EvalRequest req = new EvalRequest(null, expr);
            pause.enqueueEval(s, req);
            replyFromFuture(req, replier);
        } else {
            replier.err(-32000, "callFunctionOn requires a paused target");
        }
    }

    private void replyFromFuture(EvalRequest req, CdpTransport.Replier replier) {
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
}
