/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp.registry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public final class BreakpointStore {

    private final AtomicInteger seq = new AtomicInteger(1);
    private final Map<String, Breakpoint> byId = new HashMap<>();
    private final Map<String, List<Breakpoint>> byScriptId = new HashMap<>();
    private final List<Breakpoint> urlPending = new ArrayList<>();
    private boolean active = true;

    public synchronized Breakpoint addById(
            String scriptId, int line, int col, String condition, ScriptRecord rec) {
        int snappedLine = rec == null ? line : rec.snapLine(line);
        String id = makeId(scriptId, snappedLine, col);
        Breakpoint bp = new Breakpoint(id, scriptId, snappedLine, col, condition, false, false);
        bp.resolvedScriptId = scriptId;
        bp.resolvedLine = snappedLine;
        byId.put(id, bp);
        byScriptId.computeIfAbsent(scriptId, k -> new ArrayList<>()).add(bp);
        return bp;
    }

    public synchronized Breakpoint addByUrl(
            String url,
            int line,
            int col,
            String condition,
            boolean isRegex,
            List<ScriptRecord> matches) {
        String id = makeId(url, line, col);
        Breakpoint bp = new Breakpoint(id, url, line, col, condition, true, isRegex);
        byId.put(id, bp);
        urlPending.add(bp);
        // Bind immediately to any currently-loaded matching scripts.
        Pattern compiled = null;
        if (isRegex) {
            try {
                compiled = Pattern.compile(url);
            } catch (RuntimeException ignored) {
                // leave compiled null; no match will be attempted
            }
        }
        for (ScriptRecord rec : matches) {
            if (matchUrl(bp, compiled, rec.url)) {
                bindToScript(bp, rec);
            }
        }
        return bp;
    }

    public synchronized void remove(String id) {
        Breakpoint bp = byId.remove(id);
        if (bp == null) return;
        urlPending.remove(bp);
        for (List<Breakpoint> list : byScriptId.values()) list.remove(bp);
    }

    public synchronized Breakpoint getById(String id) {
        return byId.get(id);
    }

    public synchronized List<Breakpoint> hitsAt(String scriptId, int line) {
        List<Breakpoint> list = byScriptId.get(scriptId);
        if (list == null) return new ArrayList<>();
        List<Breakpoint> out = new ArrayList<>();
        for (Breakpoint bp : list) {
            if (bp.resolvedLine == line) out.add(bp);
        }
        return out;
    }

    /** Called when a new script is registered; resolves any pending URL breakpoints that match. */
    public synchronized List<Breakpoint> onNewScript(ScriptRecord rec) {
        List<Breakpoint> resolved = new ArrayList<>();
        for (Breakpoint bp : new ArrayList<>(urlPending)) {
            Pattern compiled = null;
            if (bp.isRegex) {
                try {
                    compiled = Pattern.compile(bp.scriptIdOrUrl);
                } catch (RuntimeException ignored) {
                    continue;
                }
            }
            if (matchUrl(bp, compiled, rec.url)) {
                bindToScript(bp, rec);
                resolved.add(bp);
            }
        }
        return resolved;
    }

    public synchronized List<Breakpoint> allPendingUrl() {
        return new ArrayList<>(urlPending);
    }

    public synchronized void setActive(boolean v) {
        this.active = v;
    }

    public synchronized boolean isActive() {
        return active;
    }

    private void bindToScript(Breakpoint bp, ScriptRecord rec) {
        int snapped = rec.snapLine(bp.line);
        bp.resolvedScriptId = rec.scriptId;
        bp.resolvedLine = snapped;
        byScriptId.computeIfAbsent(rec.scriptId, k -> new ArrayList<>()).add(bp);
    }

    private static boolean matchUrl(Breakpoint bp, Pattern compiled, String candidate) {
        if (bp.isRegex) {
            return compiled != null && compiled.matcher(candidate).find();
        }
        return bp.scriptIdOrUrl.equals(candidate);
    }

    private String makeId(String urlOrScript, int line, int col) {
        return seq.getAndIncrement() + ":" + line + ":" + col + ":" + urlOrScript;
    }
}
