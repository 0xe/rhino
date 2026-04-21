/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp.transport;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Scriptable;

/**
 * Owns the server socket, the single transport thread, and the outbound event queue. Ensures all
 * outbound marshaling happens on one dedicated thread with a dedicated {@link
 * org.mozilla.javascript.Context}.
 */
public final class CdpTransport implements WebSocketHandler {

    private static final Logger LOG = Logger.getLogger(CdpTransport.class.getName());

    public interface InboundHandler {
        void onMessage(int id, String method, Scriptable params, Replier replier);

        default void onClientConnected() {}

        default void onClientDisconnected() {}
    }

    public interface Replier {
        void ok(Map<String, Object> result);

        void err(int code, String message);
    }

    private final DebugServer server;
    private final ContextFactory factory;
    private final InboundHandler inbound;
    private final LinkedBlockingQueue<QueuedEvent> outbound = new LinkedBlockingQueue<>();
    private final JsonMarshaller marshaller;
    private final ThreadLocal<JsonMarshaller> readerMarshallerTL;
    private Thread writerThread;
    private volatile boolean running;
    private volatile WebSocketConnection connection;

    public CdpTransport(String host, int port, ContextFactory factory, InboundHandler inbound) {
        this.factory = factory;
        this.inbound = inbound;
        this.marshaller = new JsonMarshaller(factory);
        this.server = new DebugServer(host, port, this);
        this.readerMarshallerTL =
                ThreadLocal.withInitial(
                        () -> {
                            JsonMarshaller m = new JsonMarshaller(this.factory);
                            m.attachToCurrentThread();
                            return m;
                        });
    }

    public void start() throws IOException {
        running = true;
        writerThread = new Thread(this::writerLoop, "rhino-cdp-writer");
        writerThread.setDaemon(true);
        writerThread.start();
        server.start();
    }

    public int port() {
        return server.port();
    }

    public String webSocketDebuggerUrl() {
        return server.webSocketDebuggerUrl();
    }

    public boolean isClientConnected() {
        return server.isClientConnected();
    }

    public void stop() {
        running = false;
        server.stop();
        if (writerThread != null) writerThread.interrupt();
    }

    public void enqueueEvent(String method, Map<String, Object> params) {
        outbound.offer(QueuedEvent.event(method, params));
    }

    public void enqueueReply(int id, Map<String, Object> result) {
        outbound.offer(QueuedEvent.reply(id, result));
    }

    public void enqueueError(int id, int code, String message) {
        outbound.offer(QueuedEvent.error(id, code, message));
    }

    // ---- WebSocketHandler ----

    @Override
    public void onOpen(WebSocketConnection conn) {
        this.connection = conn;
        inbound.onClientConnected();
    }

    @Override
    public void onClose(WebSocketConnection conn, int code, String reason) {
        if (this.connection == conn) this.connection = null;
        inbound.onClientDisconnected();
    }

    @Override
    public void onMessage(WebSocketConnection conn, String text) {
        // The DebugServer runs one reader thread per connection. That thread has no Rhino Context,
        // so we attach a marshaller to it lazily. We do NOT reuse the writer thread's marshaller:
        // Rhino Contexts are thread-affine.
        JsonMarshaller reader = readerMarshaller();
        Scriptable msg;
        try {
            msg = reader.parse(text);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "bad JSON from client", e);
            return;
        }
        if (msg == null) return;
        Integer id = JsonMarshaller.intOrNull(msg, "id");
        String method = JsonMarshaller.str(msg, "method");
        Scriptable params = JsonMarshaller.child(msg, "params");
        if (id == null || method == null) return;
        final int idVal = id;
        inbound.onMessage(
                idVal,
                method,
                params,
                new Replier() {
                    @Override
                    public void ok(Map<String, Object> result) {
                        enqueueReply(idVal, result);
                    }

                    @Override
                    public void err(int code, String message) {
                        enqueueError(idVal, code, message);
                    }
                });
    }

    // Reader-thread local marshaller. Creating a fresh one per message would be fine but wasteful.
    private JsonMarshaller readerMarshaller() {
        return readerMarshallerTL.get();
    }

    // ---- Writer loop: drains outbound queue and sends frames ----

    private void writerLoop() {
        marshaller.attachToCurrentThread();
        try {
            while (running) {
                QueuedEvent ev;
                try {
                    ev = outbound.take();
                } catch (InterruptedException e) {
                    if (!running) return;
                    continue;
                }
                WebSocketConnection conn = connection;
                if (conn == null || !conn.isOpen()) {
                    // Drop events when no client is attached.
                    continue;
                }
                String json;
                try {
                    json = buildJson(ev);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "marshaling failed", e);
                    continue;
                }
                conn.sendText(json);
            }
        } finally {
            marshaller.detach();
        }
    }

    private String buildJson(QueuedEvent ev) {
        Map<String, Object> tree = new LinkedHashMap<>();
        if (ev.isReply()) {
            tree.put("id", ev.replyId);
            Object err = ev.params.get("__cdp_error__");
            if (err instanceof Map) {
                tree.put("error", err);
            } else {
                tree.put("result", ev.params);
            }
        } else {
            tree.put("method", ev.method);
            tree.put("params", ev.params);
        }
        return marshaller.stringify(tree);
    }
}
