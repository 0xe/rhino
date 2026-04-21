/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp.transport;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal JDK-only HTTP/1.1 + RFC 6455 WebSocket server for a single CDP target.
 *
 * <p>Exposes {@code /json/version} and {@code /json[/list]} for discovery and upgrades requests to
 * {@code /<sessionId>} into a WebSocket session. Intentionally single-threaded per connection; the
 * accept thread hands each client off to a dedicated reader thread.
 */
public final class DebugServer {

    private static final Logger LOG = Logger.getLogger(DebugServer.class.getName());
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

    private final String host;
    private final int requestedPort;
    private final String sessionId;
    private final WebSocketHandler handler;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile int boundPort = -1;
    private volatile WebSocketConnectionImpl activeConnection;

    public DebugServer(String host, int port, WebSocketHandler handler) {
        this.host = host;
        this.requestedPort = port;
        this.sessionId = UUID.randomUUID().toString();
        this.handler = handler;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(InetAddress.getByName(host), requestedPort));
        boundPort = serverSocket.getLocalPort();
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "rhino-cdp-accept:" + boundPort);
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public int port() {
        return boundPort;
    }

    public String sessionId() {
        return sessionId;
    }

    public String webSocketDebuggerUrl() {
        return "ws://" + host + ":" + boundPort + "/" + sessionId;
    }

    public boolean isClientConnected() {
        WebSocketConnectionImpl c = activeConnection;
        return c != null && c.isOpen();
    }

    public void stop() {
        running.set(false);
        WebSocketConnectionImpl c = activeConnection;
        if (c != null) {
            c.close(1001, "server shutting down");
        }
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                client.setTcpNoDelay(true);
                Thread t =
                        new Thread(
                                () -> handleClient(client), "rhino-cdp-conn:" + client.getPort());
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running.get()) {
                    LOG.log(Level.WARNING, "accept failed", e);
                }
            }
        }
    }

    private void handleClient(Socket client) {
        try {
            BufferedInputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            Request req = readRequest(in);
            if (req == null) {
                client.close();
                return;
            }
            if (isWebSocketUpgrade(req) && pathMatchesSession(req.path)) {
                doWebSocketUpgrade(client, in, out, req);
            } else if (req.path.equals("/json/version")) {
                writeJson(out, buildVersionJson());
                client.close();
            } else if (req.path.equals("/json") || req.path.equals("/json/list")) {
                writeJson(out, buildListJson());
                client.close();
            } else if (req.path.equals("/json/protocol")) {
                writeJson(out, "{}");
                client.close();
            } else {
                writeStatus(out, 404, "Not Found", "");
                client.close();
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "client error", e);
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private boolean pathMatchesSession(String path) {
        // Accept any single-segment path so the devtools frontend can choose its own session id.
        return path.length() > 1 && path.indexOf('/', 1) < 0;
    }

    private static boolean isWebSocketUpgrade(Request req) {
        String upgrade = req.header("upgrade");
        String key = req.header("sec-websocket-key");
        return upgrade != null
                && upgrade.toLowerCase(Locale.ROOT).contains("websocket")
                && key != null;
    }

    private String buildVersionJson() {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendKv(sb, "Browser", "Rhino");
        sb.append(',');
        appendKv(sb, "Protocol-Version", "1.3");
        sb.append(',');
        appendKv(sb, "V8-Version", "N/A");
        sb.append(',');
        appendKv(sb, "WebKit-Version", "N/A");
        sb.append(',');
        appendKv(sb, "webSocketDebuggerUrl", webSocketDebuggerUrl());
        sb.append('}');
        return sb.toString();
    }

    private String buildListJson() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("[{");
        appendKv(sb, "description", "Rhino runtime");
        sb.append(',');
        appendKv(
                sb,
                "devtoolsFrontendUrl",
                "devtools://devtools/bundled/js_app.html?ws="
                        + host
                        + ":"
                        + boundPort
                        + "/"
                        + sessionId);
        sb.append(',');
        appendKv(sb, "id", sessionId);
        sb.append(',');
        appendKv(sb, "title", "Rhino");
        sb.append(',');
        appendKv(sb, "type", "node");
        sb.append(',');
        appendKv(sb, "url", "file://");
        sb.append(',');
        appendKv(sb, "webSocketDebuggerUrl", webSocketDebuggerUrl());
        sb.append("}]");
        return sb.toString();
    }

    private static void appendKv(StringBuilder sb, String k, String v) {
        sb.append('"').append(k).append("\":\"").append(jsonEscape(v)).append('"');
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static void writeJson(OutputStream out, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 200 OK\r\n");
        h.append("Content-Type: application/json; charset=UTF-8\r\n");
        h.append("Content-Length: ").append(bytes.length).append("\r\n");
        h.append("Cache-Control: no-cache\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private static void writeStatus(OutputStream out, int code, String reason, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        h.append("Content-Length: ").append(bytes.length).append("\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    // ---- HTTP request parsing ----

    private static final class Request {
        String method;
        String path;
        Map<String, String> headers = new HashMap<>();

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private static Request readRequest(InputStream in) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) return null;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return null;
        Request r = new Request();
        r.method = parts[0];
        r.path = parts[1];
        while (true) {
            String line = readLine(in);
            if (line == null || line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                r.headers.put(name, value);
            }
        }
        return r;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int prev = -1;
        while (true) {
            int b = in.read();
            if (b < 0) return buf.size() == 0 ? null : buf.toString(StandardCharsets.ISO_8859_1);
            if (prev == '\r' && b == '\n') {
                byte[] bytes = buf.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
            }
            buf.write(b);
            prev = b;
        }
    }

    // ---- WebSocket upgrade + frame loop ----

    private void doWebSocketUpgrade(Socket sock, InputStream in, OutputStream out, Request req)
            throws IOException {
        String key = req.header("sec-websocket-key");
        String accept = computeAccept(key);
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 101 Switching Protocols\r\n");
        h.append("Upgrade: websocket\r\n");
        h.append("Connection: Upgrade\r\n");
        h.append("Sec-WebSocket-Accept: ").append(accept).append("\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(sock, out);
        WebSocketConnectionImpl prev = activeConnection;
        if (prev != null) {
            prev.close(1001, "new connection replacing");
        }
        activeConnection = conn;
        try {
            handler.onOpen(conn);
            readFrameLoop(in, conn);
        } finally {
            handler.onClose(conn, conn.closeCode(), conn.closeReason());
            if (activeConnection == conn) activeConnection = null;
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String computeAccept(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + WS_GUID).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    private void readFrameLoop(InputStream in, WebSocketConnectionImpl conn) throws IOException {
        ByteArrayOutputStream fragmented = null;
        int fragmentedOpcode = -1;
        while (conn.isOpen()) {
            Frame frame = readFrame(in);
            if (frame == null) {
                conn.markClosed(1006, "eof");
                return;
            }
            switch (frame.opcode) {
                case 0x0:
                    {
                        if (fragmented == null) {
                            conn.close(1002, "unexpected continuation");
                            return;
                        }
                        fragmented.write(frame.payload);
                        if (fragmented.size() > MAX_MESSAGE_BYTES) {
                            conn.close(1009, "message too big");
                            return;
                        }
                        if (frame.fin) {
                            deliverMessage(conn, fragmentedOpcode, fragmented.toByteArray());
                            fragmented = null;
                            fragmentedOpcode = -1;
                        }
                        break;
                    }
                case 0x1:
                case 0x2:
                    {
                        if (frame.fin) {
                            deliverMessage(conn, frame.opcode, frame.payload);
                        } else {
                            fragmented = new ByteArrayOutputStream();
                            fragmentedOpcode = frame.opcode;
                            fragmented.write(frame.payload);
                        }
                        break;
                    }
                case 0x8:
                    {
                        int code = 1000;
                        String reason = "";
                        if (frame.payload.length >= 2) {
                            code = ((frame.payload[0] & 0xff) << 8) | (frame.payload[1] & 0xff);
                            if (frame.payload.length > 2) {
                                reason =
                                        new String(
                                                frame.payload,
                                                2,
                                                frame.payload.length - 2,
                                                StandardCharsets.UTF_8);
                            }
                        }
                        conn.sendCloseFrame(code, reason);
                        conn.markClosed(code, reason);
                        return;
                    }
                case 0x9:
                    {
                        conn.sendPong(frame.payload);
                        break;
                    }
                case 0xA:
                    break;
                default:
                    conn.close(1002, "unknown opcode");
                    return;
            }
        }
    }

    private void deliverMessage(WebSocketConnectionImpl conn, int opcode, byte[] payload) {
        if (opcode != 0x1) {
            // CDP only uses text frames.
            conn.close(1003, "binary not supported");
            return;
        }
        String text = new String(payload, StandardCharsets.UTF_8);
        try {
            handler.onMessage(conn, text);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "handler threw", e);
        }
    }

    private static final class Frame {
        boolean fin;
        int opcode;
        byte[] payload;
    }

    private static Frame readFrame(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) return null;
        int b1 = in.read();
        if (b1 < 0) return null;
        Frame f = new Frame();
        f.fin = (b0 & 0x80) != 0;
        f.opcode = b0 & 0x0f;
        boolean masked = (b1 & 0x80) != 0;
        long length = b1 & 0x7f;
        if (length == 126) {
            length = ((long) readN(in, 1) << 8) | readN(in, 1);
        } else if (length == 127) {
            long hi =
                    ((long) readN(in, 1) << 24)
                            | ((long) readN(in, 1) << 16)
                            | ((long) readN(in, 1) << 8)
                            | readN(in, 1);
            long lo =
                    ((long) readN(in, 1) << 24)
                            | ((long) readN(in, 1) << 16)
                            | ((long) readN(in, 1) << 8)
                            | readN(in, 1);
            length = (hi << 32) | (lo & 0xffffffffL);
        }
        if (length < 0 || length > MAX_MESSAGE_BYTES) {
            throw new IOException("frame too large: " + length);
        }
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(in, mask);
        }
        byte[] payload = new byte[(int) length];
        readFully(in, payload);
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i & 3];
            }
        }
        f.payload = payload;
        return f;
    }

    private static int readN(InputStream in, int bytes) throws IOException {
        int r = 0;
        for (int i = 0; i < bytes; i++) {
            int c = in.read();
            if (c < 0) throw new IOException("unexpected eof");
            r = (r << 8) | c;
        }
        return r;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("unexpected eof");
            off += n;
        }
    }

    private static final class WebSocketConnectionImpl implements WebSocketConnection {
        private final Socket socket;
        private final OutputStream out;
        private final Object writeLock = new Object();
        private volatile boolean open = true;
        private volatile int closeCode = 1006;
        private volatile String closeReason = "";

        WebSocketConnectionImpl(Socket socket, OutputStream out) {
            this.socket = socket;
            this.out = out;
        }

        @Override
        public void sendText(String message) {
            if (!open) return;
            byte[] payload = message.getBytes(StandardCharsets.UTF_8);
            try {
                writeFrame(0x1, true, payload);
            } catch (IOException e) {
                LOG.log(Level.FINE, "send failed", e);
                markClosed(1006, "write failed");
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }

        void sendPong(byte[] payload) {
            try {
                writeFrame(0xA, true, payload);
            } catch (IOException e) {
                markClosed(1006, "pong failed");
            }
        }

        void sendCloseFrame(int code, String reason) {
            try {
                byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
                byte[] payload = new byte[2 + reasonBytes.length];
                payload[0] = (byte) ((code >> 8) & 0xff);
                payload[1] = (byte) (code & 0xff);
                System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
                writeFrame(0x8, true, payload);
            } catch (IOException ignored) {
            }
        }

        @Override
        public void close(int code, String reason) {
            if (!open) return;
            sendCloseFrame(code, reason);
            markClosed(code, reason);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        void markClosed(int code, String reason) {
            open = false;
            closeCode = code;
            closeReason = reason;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        int closeCode() {
            return closeCode;
        }

        String closeReason() {
            return closeReason;
        }

        private void writeFrame(int opcode, boolean fin, byte[] payload) throws IOException {
            synchronized (writeLock) {
                if (!open && opcode != 0x8) return;
                List<Byte> header = new ArrayList<>(10);
                header.add((byte) ((fin ? 0x80 : 0x00) | (opcode & 0x0f)));
                int len = payload.length;
                if (len < 126) {
                    header.add((byte) len);
                } else if (len <= 0xffff) {
                    header.add((byte) 126);
                    header.add((byte) ((len >> 8) & 0xff));
                    header.add((byte) (len & 0xff));
                } else {
                    header.add((byte) 127);
                    for (int i = 7; i >= 0; i--) {
                        header.add((byte) (((long) len >> (i * 8)) & 0xff));
                    }
                }
                byte[] h = new byte[header.size()];
                for (int i = 0; i < h.length; i++) h[i] = header.get(i);
                out.write(h);
                out.write(payload);
                out.flush();
            }
        }
    }
}
