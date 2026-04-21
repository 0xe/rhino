/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp.registry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.mozilla.javascript.debug.DebuggableScript;
import org.mozilla.javascript.debug.sourcemap.SourceMap;

/**
 * Owns the mapping from CDP {@code scriptId} (decimal string) to the compiled {@link
 * DebuggableScript} and its cached source text. Thread-safe: {@code handleCompilationDone} may fire
 * on any thread.
 */
public final class ScriptRegistry {

    private static final Logger LOG = Logger.getLogger(ScriptRegistry.class.getName());

    /**
     * Resolves a source-map URL (as extracted from {@code //# sourceMappingURL=}) against the
     * owning script's URL, fetches the raw source-map JSON, and returns it. Returning {@code null}
     * leaves the script without a parsed map. Embedders supply one via the CDP config.
     */
    @FunctionalInterface
    public interface SourceMapResolver {
        String fetch(String scriptUrl, String sourceMapUrl);
    }

    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<String, ScriptRecord> byId = new HashMap<>();
    private final Map<DebuggableScript, ScriptRecord> byScript = new HashMap<>();
    private final Map<String, List<ScriptRecord>> byUrl = new HashMap<>();
    private volatile SourceMapResolver sourceMapResolver;

    public void setSourceMapResolver(SourceMapResolver resolver) {
        this.sourceMapResolver = resolver;
    }

    public synchronized ScriptRecord register(DebuggableScript top, String source) {
        ScriptRecord existing = byScript.get(top);
        if (existing != null) return existing;
        String id = Integer.toString(nextId.getAndIncrement());
        String rawName = top.getSourceName();
        String sourceURLOverride = Directives.parseSourceURL(source);
        String mappingURL = Directives.parseSourceMappingURL(source);
        String url = synthesizeUrl(rawName, sourceURLOverride, id);
        int[] validLines = collectValidLines(top);
        int endLine = maxLine(validLines);
        String hash = sha256Hex(source == null ? "" : source);
        SourceMap sourceMap = resolveSourceMap(url, mappingURL);
        ScriptRecord rec =
                new ScriptRecord(
                        id,
                        url,
                        source == null ? "" : source,
                        hash,
                        top,
                        validLines,
                        endLine,
                        mappingURL,
                        sourceURLOverride,
                        sourceMap);
        byId.put(id, rec);
        byScript.put(top, rec);
        byUrl.computeIfAbsent(url, k -> new ArrayList<>()).add(rec);
        return rec;
    }

    private SourceMap resolveSourceMap(String scriptUrl, String mapUrl) {
        if (mapUrl == null) return null;
        try {
            // Inline data: URIs can always be decoded without an embedder-provided fetcher.
            if (mapUrl.startsWith("data:")) {
                return SourceMap.parseDataUri(mapUrl);
            }
            SourceMapResolver resolver = this.sourceMapResolver;
            if (resolver == null) return null;
            String json = resolver.fetch(scriptUrl, mapUrl);
            if (json == null) return null;
            return SourceMap.parse(json);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "failed to resolve source map for " + scriptUrl, e);
            return null;
        }
    }

    public synchronized ScriptRecord getById(String scriptId) {
        return byId.get(scriptId);
    }

    public synchronized ScriptRecord findByScript(DebuggableScript s) {
        return byScript.get(s);
    }

    /** Climb the nested-function chain to the top-level script, then look up its record. */
    public synchronized ScriptRecord findOwningRecord(DebuggableScript s) {
        DebuggableScript cur = s;
        while (cur != null && !cur.isTopLevel()) {
            cur = cur.getParent();
        }
        if (cur == null) return null;
        return byScript.get(cur);
    }

    public synchronized List<ScriptRecord> all() {
        return new ArrayList<>(byId.values());
    }

    public synchronized List<ScriptRecord> findByUrl(String url) {
        List<ScriptRecord> list = byUrl.get(url);
        if (list == null) return new ArrayList<>();
        return new ArrayList<>(list);
    }

    private static int[] collectValidLines(DebuggableScript top) {
        Set<Integer> all = new HashSet<>();
        collectValidLines(top, all);
        int[] out = new int[all.size()];
        int i = 0;
        for (int n : all) out[i++] = n;
        java.util.Arrays.sort(out);
        return out;
    }

    private static void collectValidLines(DebuggableScript s, Set<Integer> out) {
        int[] lines = s.getLineNumbers();
        if (lines != null) {
            for (int l : lines) out.add(l);
        }
        int fc = s.getFunctionCount();
        for (int i = 0; i < fc; i++) {
            collectValidLines(s.getFunction(i), out);
        }
    }

    private static int maxLine(int[] lines) {
        if (lines.length == 0) return 0;
        return lines[lines.length - 1];
    }

    private static String synthesizeUrl(String rawName, String override, String id) {
        if (override != null && !override.isEmpty()) return override;
        if (rawName == null || rawName.isEmpty() || rawName.startsWith("<")) {
            return "rhino-internal:///anonymous-" + id;
        }
        return rawName;
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    // Exposed for tests / source-map integration later.
    public synchronized Collection<ScriptRecord> snapshot() {
        return new ArrayList<>(byId.values());
    }
}
