/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.VarScope;
import org.mozilla.javascript.tools.debugger.cdp.adapter.RemoteObjectStore;
import org.mozilla.javascript.tools.debugger.cdp.adapter.RemoteObjects;
import org.mozilla.javascript.tools.debugger.cdp.pause.EvalResult;

/**
 * Single dedicated thread for {@code Runtime.evaluate} requests issued while no JS thread is
 * paused. Owns its own {@link Context} and global scope; cannot access user-code scopes because
 * those are affine to other threads.
 */
public final class ConsoleEvaluator {

    private final ContextFactory factory;
    private final RemoteObjectStore objectStore;
    private Thread thread;
    private final LinkedBlockingQueue<Task> queue = new LinkedBlockingQueue<>();
    private volatile boolean running;
    private volatile Scriptable scope;

    private static final class Task {
        final String expression;
        final CompletableFuture<EvalResult> future = new CompletableFuture<>();

        Task(String e) {
            this.expression = e;
        }
    }

    public ConsoleEvaluator(ContextFactory factory, RemoteObjectStore objectStore) {
        this.factory = factory;
        this.objectStore = objectStore;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "rhino-cdp-console");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    public CompletableFuture<EvalResult> submit(String expression) {
        start();
        Task t = new Task(expression);
        queue.offer(t);
        return t.future;
    }

    private void loop() {
        Context cx = factory.enterContext();
        try {
            scope = cx.initStandardObjects();
            while (running) {
                Task t;
                try {
                    t = queue.take();
                } catch (InterruptedException e) {
                    if (!running) return;
                    continue;
                }
                try {
                    Object v =
                            cx.evaluateString(
                                    (VarScope) scope, t.expression, "<cdp-eval>", 1, null);
                    Map<String, Object> ro = RemoteObjects.wrap(v, "console", objectStore);
                    t.future.complete(new EvalResult(ro, null));
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
                    t.future.complete(new EvalResult(ex, details));
                } catch (RuntimeException e) {
                    t.future.completeExceptionally(e);
                }
            }
        } finally {
            Context.exit();
        }
    }
}
