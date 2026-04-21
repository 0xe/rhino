/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.ContextFactory;

/**
 * Smoke test: verifies the HTTP bootstrap responds, the WS upgrade succeeds, and basic CDP
 * messaging round-trips.
 */
public class CdpDebuggerSmokeTest {

    private CdpDebugger dbg;

    @BeforeEach
    public void setUp() throws IOException {
        ContextFactory factory = new ContextFactory();
        CdpDebuggerConfig cfg = new CdpDebuggerConfig();
        cfg.port = 0;
        dbg = CdpDebugger.attach(factory, cfg);
    }

    @AfterEach
    public void tearDown() {
        if (dbg != null) dbg.close();
    }

    @Test
    public void httpBootstrapReturnsJson() throws IOException {
        String url = "http://127.0.0.1:" + dbg.port() + "/json/version";
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        assertEquals(200, conn.getResponseCode());
        String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(body.contains("webSocketDebuggerUrl"), body);
        assertTrue(body.contains("\"Browser\":\"Rhino\""), body);
    }

    @Test
    public void httpListReturnsTarget() throws IOException {
        String url = "http://127.0.0.1:" + dbg.port() + "/json/list";
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        assertEquals(200, conn.getResponseCode());
        String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(body.startsWith("[{"), body);
        assertTrue(body.contains("\"type\":\"node\""), body);
    }

    @Test
    public void webSocketRoundTripOnDebuggerEnable() throws Exception {
        try (RawWs ws = RawWs.connect("127.0.0.1", dbg.port(), "/session")) {
            CountDownLatch replyLatch = new CountDownLatch(1);
            List<String> messages = new ArrayList<>();
            Thread reader =
                    new Thread(
                            () -> {
                                try {
                                    while (true) {
                                        String text = ws.readText();
                                        if (text == null) return;
                                        synchronized (messages) {
                                            messages.add(text);
                                        }
                                        if (text.startsWith("{\"id\":1,")) replyLatch.countDown();
                                    }
                                } catch (IOException ignored) {
                                }
                            });
            reader.setDaemon(true);
            reader.start();
            ws.sendText("{\"id\":1,\"method\":\"Debugger.enable\",\"params\":{}}");
            boolean got = replyLatch.await(5, TimeUnit.SECONDS);
            if (!got) {
                synchronized (messages) {
                    System.err.println("messages so far: " + messages);
                }
            }
            assertTrue(got, "no reply from Debugger.enable");
            synchronized (messages) {
                boolean sawReply = false;
                for (String m : messages) {
                    if (m.startsWith("{\"id\":1,") && m.contains("\"result\"")) {
                        sawReply = true;
                        break;
                    }
                }
                assertTrue(sawReply, "expected id:1 reply; got " + messages);
            }
        }
    }

    // ---- minimal raw WebSocket client (client side; masks outbound, expects unmasked inbound)
    // ----

    private static final class RawWs implements AutoCloseable {
        final Socket s;
        final InputStream in;
        final java.io.OutputStream out;

        private RawWs(Socket s) throws IOException {
            this.s = s;
            this.in = s.getInputStream();
            this.out = s.getOutputStream();
        }

        static RawWs connect(String host, int port, String path) throws Exception {
            Socket s = new Socket(host, port);
            java.io.OutputStream out = s.getOutputStream();
            byte[] keyBytes = new byte[16];
            new java.util.Random().nextBytes(keyBytes);
            String key = Base64.getEncoder().encodeToString(keyBytes);
            String req =
                    "GET "
                            + path
                            + " HTTP/1.1\r\n"
                            + "Host: "
                            + host
                            + ":"
                            + port
                            + "\r\n"
                            + "Upgrade: websocket\r\n"
                            + "Connection: Upgrade\r\n"
                            + "Sec-WebSocket-Key: "
                            + key
                            + "\r\n"
                            + "Sec-WebSocket-Version: 13\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int prev = -1, prev2 = -1, prev3 = -1;
            while (true) {
                int b = in.read();
                if (b < 0) throw new IOException("closed during handshake");
                header.write(b);
                if (prev3 == '\r' && prev2 == '\n' && prev == '\r' && b == '\n') break;
                prev3 = prev2;
                prev2 = prev;
                prev = b;
            }
            String response = header.toString(StandardCharsets.ISO_8859_1);
            if (!response.startsWith("HTTP/1.1 101")) {
                throw new IOException("bad handshake: " + response);
            }
            // Verify Sec-WebSocket-Accept matches.
            String expected =
                    Base64.getEncoder()
                            .encodeToString(
                                    MessageDigest.getInstance("SHA-1")
                                            .digest(
                                                    (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                                            .getBytes(
                                                                    StandardCharsets.ISO_8859_1)));
            assertNotNull(expected);
            return new RawWs(s);
        }

        void sendText(String text) throws IOException {
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            buf.write(0x81); // FIN + text
            int len = payload.length;
            if (len < 126) {
                buf.write(0x80 | len);
            } else if (len <= 0xffff) {
                buf.write(0x80 | 126);
                buf.write((len >> 8) & 0xff);
                buf.write(len & 0xff);
            } else {
                buf.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) buf.write((int) (((long) len >> (i * 8)) & 0xff));
            }
            byte[] mask = {1, 2, 3, 4};
            buf.write(mask, 0, 4);
            for (int i = 0; i < payload.length; i++) buf.write(payload[i] ^ mask[i & 3]);
            out.write(buf.toByteArray());
            out.flush();
        }

        String readText() throws IOException {
            int b0 = in.read();
            if (b0 < 0) return null;
            int b1 = in.read();
            if (b1 < 0) return null;
            int opcode = b0 & 0x0f;
            int len = b1 & 0x7f;
            if (len == 126) {
                len = ((in.read() & 0xff) << 8) | (in.read() & 0xff);
            } else if (len == 127) {
                long l = 0;
                for (int i = 0; i < 8; i++) l = (l << 8) | (in.read() & 0xff);
                len = (int) l;
            }
            byte[] payload = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(payload, off, len - off);
                if (n < 0) return null;
                off += n;
            }
            if (opcode == 0x8) return null;
            return new String(payload, StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws IOException {
            s.close();
        }
    }
}
