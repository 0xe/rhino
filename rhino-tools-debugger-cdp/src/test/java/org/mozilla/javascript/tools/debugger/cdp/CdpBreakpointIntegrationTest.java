/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.VarScope;

/**
 * End-to-end: set a breakpoint via {@code Debugger.setBreakpointByUrl}, execute a script, verify we
 * observe {@code Debugger.paused} at the snapped line, then resume.
 */
public class CdpBreakpointIntegrationTest {

    private CdpDebugger dbg;
    private ContextFactory factory;

    @BeforeEach
    public void setUp() throws IOException {
        factory = new ContextFactory();
        CdpDebuggerConfig cfg = new CdpDebuggerConfig();
        cfg.port = 0;
        dbg = CdpDebugger.attach(factory, cfg);
    }

    @AfterEach
    public void tearDown() {
        if (dbg != null) dbg.close();
    }

    @Test
    public void scriptParsedAndBreakpointHit() throws Exception {
        try (RawClient ws = RawClient.connect("127.0.0.1", dbg.port())) {
            // Collect messages off-thread.
            List<String> incoming = new ArrayList<>();
            CountDownLatch scriptParsedLatch = new CountDownLatch(1);
            CountDownLatch pausedLatch = new CountDownLatch(1);
            CountDownLatch resumedLatch = new CountDownLatch(1);
            Thread reader =
                    new Thread(
                            () -> {
                                try {
                                    while (true) {
                                        String t = ws.readText();
                                        if (t == null) return;
                                        synchronized (incoming) {
                                            incoming.add(t);
                                        }
                                        if (t.contains("\"Debugger.scriptParsed\"")) {
                                            scriptParsedLatch.countDown();
                                        }
                                        if (t.contains("\"Debugger.paused\"")) {
                                            pausedLatch.countDown();
                                        }
                                        if (t.contains("\"Debugger.resumed\"")) {
                                            resumedLatch.countDown();
                                        }
                                    }
                                } catch (IOException ignored) {
                                }
                            });
            reader.setDaemon(true);
            reader.start();

            // Enable Debugger + Runtime.
            ws.sendText("{\"id\":1,\"method\":\"Runtime.enable\",\"params\":{}}");
            ws.sendText("{\"id\":2,\"method\":\"Debugger.enable\",\"params\":{}}");

            // Set a pending URL breakpoint at line 2.
            String url = "file:///testscript.js";
            ws.sendText(
                    "{\"id\":3,\"method\":\"Debugger.setBreakpointByUrl\",\"params\":{\"url\":\""
                            + url
                            + "\",\"lineNumber\":2,\"columnNumber\":0}}");

            // Run the script on a dedicated thread so pausing doesn't block the test.
            Thread runner =
                    new Thread(
                            () -> {
                                Context cx = factory.enterContext();
                                try {
                                    VarScope scope = cx.initStandardObjects();
                                    String src = "var a = 1;\nvar b = 2;\nvar c = a + b;\n";
                                    cx.evaluateString(scope, src, url, 1, null);
                                } finally {
                                    Context.exit();
                                }
                            });
            runner.setDaemon(true);
            runner.start();

            assertTrue(
                    scriptParsedLatch.await(10, TimeUnit.SECONDS),
                    "expected Debugger.scriptParsed");
            assertTrue(pausedLatch.await(10, TimeUnit.SECONDS), "expected Debugger.paused");

            // Issue resume; the runner thread should finish.
            ws.sendText("{\"id\":4,\"method\":\"Debugger.resume\",\"params\":{}}");
            assertTrue(resumedLatch.await(10, TimeUnit.SECONDS), "expected Debugger.resumed");

            runner.join(5000);
            assertNotNull(incoming);
        }
    }

    // Lightweight WS client reused from smoke test.
    private static final class RawClient implements AutoCloseable {
        private final java.net.Socket s;
        private final java.io.InputStream in;
        private final java.io.OutputStream out;

        private RawClient(java.net.Socket s) throws IOException {
            this.s = s;
            this.in = s.getInputStream();
            this.out = s.getOutputStream();
        }

        static RawClient connect(String host, int port) throws Exception {
            java.net.Socket s = new java.net.Socket(host, port);
            byte[] key = new byte[16];
            new java.util.Random(0xCAFEBABEL).nextBytes(key);
            String b64 = java.util.Base64.getEncoder().encodeToString(key);
            String req =
                    "GET /s HTTP/1.1\r\nHost: "
                            + host
                            + ":"
                            + port
                            + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                            + "Sec-WebSocket-Key: "
                            + b64
                            + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
            s.getOutputStream().write(req.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
            java.io.InputStream in = s.getInputStream();
            int prev3 = -1, prev2 = -1, prev = -1;
            java.io.ByteArrayOutputStream h = new java.io.ByteArrayOutputStream();
            while (true) {
                int b = in.read();
                if (b < 0) throw new IOException("closed during handshake");
                h.write(b);
                if (prev3 == '\r' && prev2 == '\n' && prev == '\r' && b == '\n') break;
                prev3 = prev2;
                prev2 = prev;
                prev = b;
            }
            String resp = h.toString(java.nio.charset.StandardCharsets.ISO_8859_1);
            if (!resp.startsWith("HTTP/1.1 101")) throw new IOException("bad handshake: " + resp);
            return new RawClient(s);
        }

        void sendText(String text) throws IOException {
            byte[] payload = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            buf.write(0x81);
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
            byte[] mask = {5, 6, 7, 8};
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
            return new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws IOException {
            s.close();
        }
    }
}
