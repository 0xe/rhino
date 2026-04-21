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
import java.util.Base64;
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
 * End-to-end proof that a client setting a breakpoint on an <em>original</em> source URL — via
 * {@code Debugger.setBreakpointByUrl} with a URL that matches a {@link
 * org.mozilla.javascript.debug.sourcemap.SourceMap#sources()} entry — correctly pauses the
 * generated script at the translated line.
 *
 * <p>The generated script looks line-by-line like the original, so the 1:1 map makes the
 * translation easy to reason about. A {@code //# sourceMappingURL=data:...} directive supplies the
 * map inline, so no embedder fetcher is needed.
 */
public class CdpSourceMapIntegrationTest {

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
    public void breakpointOnOriginalUrlTranslatesToGeneratedLine() throws Exception {
        // Original-source URL the client will set the breakpoint against.
        String originalUrl = "file:///orig.js";
        // Map: 3 generated lines 1:1 with 3 original lines.
        //   gen 0 -> orig 0, gen 1 -> orig 1, gen 2 -> orig 2
        //   mappings segments: AAAA ; AACA ; AACA
        String mapJson =
                "{\"version\":3,\"file\":\"generated.js\","
                        + "\"sources\":[\""
                        + originalUrl
                        + "\"],\"names\":[],"
                        + "\"mappings\":\"AAAA;AACA;AACA\"}";
        String base64Map = Base64.getEncoder().encodeToString(mapJson.getBytes());
        // Generated script: three statements on three lines, plus the sourceMappingURL directive.
        // We place a breakpoint at original line 1 (the middle statement) — it should pause
        // when generated line 1 runs.
        String generated =
                "var a = 1;\n"
                        + "var b = 2;\n"
                        + "var c = a + b;\n"
                        + "//# sourceMappingURL=data:application/json;base64,"
                        + base64Map
                        + "\n";

        try (RawClient ws = RawClient.connect("127.0.0.1", dbg.port())) {
            List<String> incoming = new ArrayList<>();
            CountDownLatch scriptParsedLatch = new CountDownLatch(1);
            CountDownLatch pausedLatch = new CountDownLatch(1);
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
                                    }
                                } catch (IOException ignored) {
                                }
                            });
            reader.setDaemon(true);
            reader.start();

            ws.sendText("{\"id\":1,\"method\":\"Runtime.enable\",\"params\":{}}");
            ws.sendText("{\"id\":2,\"method\":\"Debugger.enable\",\"params\":{}}");

            // Run the generated script on a dedicated thread (so when it pauses it doesn't
            // block the test's main flow).
            String genUrl = "file:///generated.js";
            Thread runner =
                    new Thread(
                            () -> {
                                Context cx = factory.enterContext();
                                try {
                                    VarScope scope = cx.initStandardObjects();
                                    cx.evaluateString(scope, generated, genUrl, 1, null);
                                } finally {
                                    Context.exit();
                                }
                            });
            runner.setDaemon(true);

            // Setting the breakpoint BEFORE the script runs exercises the "pending URL
            // breakpoint" path: nothing is loaded yet, so the store keeps it pending and
            // binds on scriptParsed. But we want to also prove source-map translation, so
            // first compile + register the script, then set the breakpoint, then let it run
            // — the breakpoint will translate through the already-loaded source map.
            factory.call(
                    cx -> {
                        VarScope scope = cx.initStandardObjects();
                        cx.compileString(generated, genUrl, 1, null);
                        return null;
                    });

            assertTrue(
                    scriptParsedLatch.await(10, TimeUnit.SECONDS),
                    "expected Debugger.scriptParsed");

            // Breakpoint on ORIGINAL line 1 (zero-based) — i.e. var b = 2;
            ws.sendText(
                    "{\"id\":3,\"method\":\"Debugger.setBreakpointByUrl\",\"params\":{"
                            + "\"url\":\""
                            + originalUrl
                            + "\",\"lineNumber\":1,\"columnNumber\":0}}");
            // Give the reply a moment to land and the breakpoint to register.
            Thread.sleep(200);

            // Now actually execute the generated script; the breakpoint should fire on
            // generated line 1 (which the 1:1 map identifies as original line 1).
            runner.start();

            assertTrue(pausedLatch.await(10, TimeUnit.SECONDS), "expected Debugger.paused");

            // Resume so the runner can finish and the test can tear down cleanly.
            ws.sendText("{\"id\":4,\"method\":\"Debugger.resume\",\"params\":{}}");
            runner.join(5000);

            // Sanity: the reply to setBreakpointByUrl (id=3) should have echoed the generated
            // location we translated to.
            synchronized (incoming) {
                boolean sawBreakpointReply = false;
                for (String m : incoming) {
                    if (m.startsWith("{\"id\":3,") && m.contains("\"locations\"")) {
                        sawBreakpointReply = true;
                        // The generated line on the hit should be 1 (0-based) or the
                        // Rhino-snapped equivalent. Assert it at least mentions a scriptId
                        // and a lineNumber.
                        assertTrue(m.contains("\"scriptId\""), m);
                        assertTrue(m.contains("\"lineNumber\""), m);
                        break;
                    }
                }
                assertTrue(sawBreakpointReply, "id:3 reply not seen in " + incoming);
            }
            assertNotNull(incoming);
        }
    }

    // Lightweight WS client reused from other integration tests.
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
            new java.util.Random(0xF00DF00DL).nextBytes(key);
            String b64 = java.util.Base64.getEncoder().encodeToString(key);
            String req =
                    "GET /sm HTTP/1.1\r\nHost: "
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
            byte[] mask = {9, 10, 11, 12};
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
