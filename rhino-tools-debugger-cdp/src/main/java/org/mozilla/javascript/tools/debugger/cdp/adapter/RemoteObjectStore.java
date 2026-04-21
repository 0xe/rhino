/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp.adapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks live references to Scriptables handed out over the wire so that {@code
 * Runtime.getProperties} can resolve them back. Object IDs use V8's JSON-in-JSON cosmetic
 * convention: {@code "{\"injectedScriptId\":1,\"id\":42}"}.
 */
public final class RemoteObjectStore {

    private final AtomicInteger next = new AtomicInteger(1);
    private final Map<Integer, Entry> byId = new HashMap<>();
    private final Map<String, List<Integer>> byGroup = new HashMap<>();

    private static final class Entry {
        final Object value;
        final String group;

        Entry(Object value, String group) {
            this.value = value;
            this.group = group;
        }
    }

    public synchronized String register(Object value, String group) {
        int id = next.getAndIncrement();
        byId.put(id, new Entry(value, group));
        if (group != null) byGroup.computeIfAbsent(group, k -> new ArrayList<>()).add(id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("injectedScriptId", 1);
        payload.put("id", id);
        return toJson(payload);
    }

    public synchronized Object resolve(String objectId) {
        Integer id = parseId(objectId);
        if (id == null) return null;
        Entry e = byId.get(id);
        return e == null ? null : e.value;
    }

    public synchronized void release(String objectId) {
        Integer id = parseId(objectId);
        if (id == null) return;
        Entry e = byId.remove(id);
        if (e != null && e.group != null) {
            List<Integer> list = byGroup.get(e.group);
            if (list != null) list.remove(id);
        }
    }

    public synchronized void releaseGroup(String group) {
        List<Integer> ids = byGroup.remove(group);
        if (ids == null) return;
        for (int id : ids) byId.remove(id);
    }

    public synchronized void releaseAll() {
        byId.clear();
        byGroup.clear();
    }

    private static Integer parseId(String json) {
        if (json == null) return null;
        int idx = json.indexOf("\"id\":");
        if (idx < 0) return null;
        int s = idx + 5;
        int e = s;
        while (e < json.length() && (Character.isDigit(json.charAt(e)) || json.charAt(e) == '-')) {
            e++;
        }
        if (s == e) return null;
        try {
            return Integer.parseInt(json.substring(s, e));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // Tiny JSON serializer for the fixed-shape object-id wrapper. We don't want to roundtrip
    // through Rhino's NativeJSON for this because the store may be invoked from any thread.
    private static String toJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder(32);
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append('"').append(':');
            Object v = e.getValue();
            if (v instanceof Number) sb.append(v.toString());
            else sb.append('"').append(v).append('"');
        }
        sb.append('}');
        return sb.toString();
    }
}
