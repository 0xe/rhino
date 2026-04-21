/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.debug.sourcemap;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed Source Map v3 document. Immutable after construction.
 *
 * <p>Supports both the "regular" form (with a {@code mappings} string) and the "indexed" form (with
 * {@code sections}); indexed maps are flattened into a single segment list at parse time.
 *
 * <p>Public API surface:
 *
 * <ul>
 *   <li>{@link #parse(String)} — accepts either form.
 *   <li>{@link #parseIndexed(String)} — explicit indexed-map parse; fails if the input isn't a
 *       {@code sections}-style map.
 *   <li>{@link #parseDataUri(String)} — decodes {@code data:application/json[;base64],...}.
 *   <li>{@link #originalFor(int, int)} — forward lookup (generated → original).
 *   <li>{@link #generatedFor(String, int)} — reverse lookup (original → generated); the reverse
 *       index is built lazily on first call.
 * </ul>
 *
 * <p>All line/column numbers are zero-based. Unmapped positions are represented as {@link Mapping}
 * instances with {@link Mapping#source} set to {@code null}.
 */
public final class SourceMap {

    private final String file;
    private final List<String> sources;
    private final List<String> names;
    private final String[] sourcesContent; // may be null; entries may be null
    // Flat packed segments per generated line: 5 ints per segment.
    //   [genCol, srcIdx, origLine, origCol, nameIdx]
    // srcIdx == -1 means "unmapped" (1-field segment).
    private final int[][] linesByGen;

    // Lazily populated reverse index: source name -> sorted int[] of
    //   [origLine, origCol, genLine, genCol] tuples (length % 4 == 0).
    private volatile Map<String, int[]> reverseIndex;

    private SourceMap(
            String file,
            List<String> sources,
            List<String> names,
            String[] sourcesContent,
            int[][] linesByGen) {
        this.file = file;
        this.sources = Collections.unmodifiableList(sources);
        this.names = Collections.unmodifiableList(names);
        this.sourcesContent = sourcesContent;
        this.linesByGen = linesByGen;
    }

    // ---- Parsing -------------------------------------------------------

    public static SourceMap parse(String json) {
        Object root = MiniJson.parse(json);
        if (!(root instanceof Map)) {
            throw new SourceMapException("source map root must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> obj = (Map<String, Object>) root;
        if (obj.containsKey("sections")) {
            return parseSectioned(obj);
        }
        return parseRegular(obj);
    }

    public static SourceMap parseIndexed(String json) {
        Object root = MiniJson.parse(json);
        if (!(root instanceof Map) || !((Map<?, ?>) root).containsKey("sections")) {
            throw new SourceMapException("not an indexed (sections) source map");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> obj = (Map<String, Object>) root;
        return parseSectioned(obj);
    }

    public static SourceMap parseDataUri(String dataUri) {
        if (dataUri == null || !dataUri.startsWith("data:")) {
            throw new SourceMapException("not a data: URI");
        }
        int comma = dataUri.indexOf(',');
        if (comma < 0) {
            throw new SourceMapException("malformed data: URI (no comma)");
        }
        String meta = dataUri.substring(5, comma); // after "data:"
        String payload = dataUri.substring(comma + 1);
        boolean base64 = meta.endsWith(";base64");
        String json;
        if (base64) {
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(payload);
            } catch (IllegalArgumentException e) {
                throw new SourceMapException("invalid base64 in data URI", e);
            }
            json = new String(raw, StandardCharsets.UTF_8);
        } else {
            json = java.net.URLDecoder.decode(payload, StandardCharsets.UTF_8);
        }
        return parse(json);
    }

    // ---- Regular parse -------------------------------------------------

    private static SourceMap parseRegular(Map<String, Object> obj) {
        checkVersion(obj);
        String file = stringOrNull(obj.get("file"));
        String sourceRoot = stringOrEmpty(obj.get("sourceRoot"));
        List<String> sources = resolveSources(asStringList(obj.get("sources")), sourceRoot);
        List<String> names = asStringList(obj.get("names"));
        String[] sourcesContent = asNullableStringArray(obj.get("sourcesContent"));
        Object m = obj.get("mappings");
        String mappings = m == null ? "" : stringOrThrow(m, "mappings");
        int[][] lines = parseMappings(mappings, sources.size(), names.size());
        return new SourceMap(file, sources, names, sourcesContent, lines);
    }

    // ---- Indexed (sections) parse --------------------------------------

    private static SourceMap parseSectioned(Map<String, Object> obj) {
        checkVersion(obj);
        String file = stringOrNull(obj.get("file"));
        List<String> mergedSources = new ArrayList<>();
        Map<String, Integer> srcIndex = new HashMap<>();
        List<String> mergedNames = new ArrayList<>();
        Map<String, Integer> nameIndex = new HashMap<>();
        List<String> mergedContent = new ArrayList<>();
        Map<Integer, List<int[]>> accum = new HashMap<>();

        Object sections = obj.get("sections");
        if (!(sections instanceof List)) {
            throw new SourceMapException("'sections' must be an array");
        }
        for (Object section : (List<?>) sections) {
            if (!(section instanceof Map)) {
                throw new SourceMapException("section must be an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> sec = (Map<String, Object>) section;
            int offLine = 0;
            int offCol = 0;
            Object off = sec.get("offset");
            if (off instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> offMap = (Map<String, Object>) off;
                offLine = toInt(offMap.get("line"));
                offCol = toInt(offMap.get("column"));
            }
            Object inner = sec.get("map");
            if (!(inner instanceof Map)) {
                throw new SourceMapException("section.map must be an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> innerMap = (Map<String, Object>) inner;
            SourceMap innerParsed = parseRegular(innerMap);

            // Remap inner source/name indices to merged lists.
            int[] srcRemap = new int[innerParsed.sources.size()];
            for (int i = 0; i < srcRemap.length; i++) {
                String s = innerParsed.sources.get(i);
                Integer existing = srcIndex.get(s);
                if (existing == null) {
                    existing = mergedSources.size();
                    mergedSources.add(s);
                    srcIndex.put(s, existing);
                    // Merge corresponding content (or null).
                    String content = null;
                    if (innerParsed.sourcesContent != null
                            && i < innerParsed.sourcesContent.length) {
                        content = innerParsed.sourcesContent[i];
                    }
                    mergedContent.add(content);
                }
                srcRemap[i] = existing;
            }
            int[] nameRemap = new int[innerParsed.names.size()];
            for (int i = 0; i < nameRemap.length; i++) {
                String n = innerParsed.names.get(i);
                Integer existing = nameIndex.get(n);
                if (existing == null) {
                    existing = mergedNames.size();
                    mergedNames.add(n);
                    nameIndex.put(n, existing);
                }
                nameRemap[i] = existing;
            }

            // Offset and accumulate.
            for (int g = 0; g < innerParsed.linesByGen.length; g++) {
                int[] packed = innerParsed.linesByGen[g];
                if (packed == null || packed.length == 0) continue;
                int newLine = g + offLine;
                for (int i = 0; i < packed.length; i += 5) {
                    int genCol = packed[i];
                    int srcIdx = packed[i + 1];
                    int origLine = packed[i + 2];
                    int origCol = packed[i + 3];
                    int nameIdxInner = packed[i + 4];
                    int[] remapped = new int[5];
                    remapped[0] = genCol + (g == 0 ? offCol : 0);
                    remapped[1] = srcIdx < 0 ? -1 : srcRemap[srcIdx];
                    remapped[2] = origLine;
                    remapped[3] = origCol;
                    remapped[4] = nameIdxInner < 0 ? -1 : nameRemap[nameIdxInner];
                    accum.computeIfAbsent(newLine, k -> new ArrayList<>()).add(remapped);
                }
            }
        }

        int maxLine = -1;
        for (Integer k : accum.keySet()) if (k > maxLine) maxLine = k;
        int[][] linesByGen = new int[maxLine + 1][];
        for (Map.Entry<Integer, List<int[]>> e : accum.entrySet()) {
            List<int[]> list = e.getValue();
            list.sort((a, b) -> Integer.compare(a[0], b[0]));
            int[] packed = new int[list.size() * 5];
            for (int i = 0; i < list.size(); i++) {
                int[] seg = list.get(i);
                System.arraycopy(seg, 0, packed, i * 5, 5);
            }
            linesByGen[e.getKey()] = packed;
        }
        String[] contentArr = mergedContent.toArray(new String[0]);
        return new SourceMap(file, mergedSources, mergedNames, contentArr, linesByGen);
    }

    // ---- Mappings string parser ---------------------------------------

    private static int[][] parseMappings(String mappings, int numSources, int numNames) {
        // State persists across the ENTIRE mappings string except for genCol,
        // which resets at each ';'.
        int genLine = 0;
        int genCol = 0;
        int srcIdx = 0;
        int origLine = 0;
        int origCol = 0;
        int nameIdx = 0;

        List<List<int[]>> perLine = new ArrayList<>();
        perLine.add(new ArrayList<>());

        int[] cursor = new int[1];
        int len = mappings.length();
        while (cursor[0] < len) {
            char c = mappings.charAt(cursor[0]);
            if (c == ';') {
                cursor[0]++;
                genLine++;
                genCol = 0;
                perLine.add(new ArrayList<>());
                continue;
            }
            if (c == ',') {
                cursor[0]++;
                continue;
            }
            // Read first VLQ (genCol delta).
            int start = cursor[0];
            int fields1 = Vlq.read(mappings, cursor);
            genCol += fields1;
            // Peek: is this a 1-field segment?
            if (atSegmentEnd(mappings, cursor[0])) {
                perLine.get(genLine).add(new int[] {genCol, -1, 0, 0, -1});
                continue;
            }
            int f2 = Vlq.read(mappings, cursor);
            // Source-map spec requires at least 4 fields if there's more than 1.
            if (atSegmentEnd(mappings, cursor[0])) {
                throw new SourceMapException(
                        "invalid 2-field segment at " + start + " in mappings");
            }
            int f3 = Vlq.read(mappings, cursor);
            if (atSegmentEnd(mappings, cursor[0])) {
                throw new SourceMapException(
                        "invalid 3-field segment at " + start + " in mappings");
            }
            int f4 = Vlq.read(mappings, cursor);
            srcIdx += f2;
            origLine += f3;
            origCol += f4;
            if (numSources > 0 && (srcIdx < 0 || srcIdx >= numSources)) {
                throw new SourceMapException(
                        "source index " + srcIdx + " out of range [0," + numSources + ")");
            }
            int nameIdxThisSeg = -1;
            if (!atSegmentEnd(mappings, cursor[0])) {
                int f5 = Vlq.read(mappings, cursor);
                nameIdx += f5;
                if (numNames > 0 && (nameIdx < 0 || nameIdx >= numNames)) {
                    throw new SourceMapException(
                            "name index " + nameIdx + " out of range [0," + numNames + ")");
                }
                nameIdxThisSeg = nameIdx;
            }
            perLine.get(genLine).add(new int[] {genCol, srcIdx, origLine, origCol, nameIdxThisSeg});
        }

        int[][] out = new int[perLine.size()][];
        for (int i = 0; i < perLine.size(); i++) {
            List<int[]> list = perLine.get(i);
            if (list.isEmpty()) {
                out[i] = new int[0];
                continue;
            }
            int[] packed = new int[list.size() * 5];
            for (int j = 0; j < list.size(); j++) {
                int[] seg = list.get(j);
                System.arraycopy(seg, 0, packed, j * 5, 5);
            }
            out[i] = packed;
        }
        return out;
    }

    private static boolean atSegmentEnd(String s, int pos) {
        if (pos >= s.length()) return true;
        char c = s.charAt(pos);
        return c == ',' || c == ';';
    }

    // ---- Lookups -------------------------------------------------------

    /**
     * Return the mapping for the given generated position, or {@code null} if no segment exists at
     * or before that position on that line. A returned mapping with {@code source == null} is an
     * explicit "unmapped" marker (1-field segment).
     */
    public Mapping originalFor(int genLine, int genCol) {
        if (genLine < 0 || genLine >= linesByGen.length) return null;
        int[] packed = linesByGen[genLine];
        if (packed == null || packed.length == 0) return null;
        int segCount = packed.length / 5;
        // Binary search for the largest genCol <= query.
        int lo = 0;
        int hi = segCount - 1;
        int pick = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int midCol = packed[mid * 5];
            if (midCol <= genCol) {
                pick = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (pick < 0) return null;
        int off = pick * 5;
        int srcIdx = packed[off + 1];
        if (srcIdx < 0) {
            return new Mapping(genLine, packed[off], null, 0, 0, null);
        }
        int nameIdx = packed[off + 4];
        String name = nameIdx < 0 ? null : names.get(nameIdx);
        return new Mapping(
                genLine, packed[off], sources.get(srcIdx), packed[off + 2], packed[off + 3], name);
    }

    /**
     * All mappings that reference the given original source at the given line. Useful for {@code
     * Debugger.setBreakpointByUrl} to translate an original-source location to generated locations.
     */
    public List<Mapping> generatedFor(String source, int originalLine) {
        Map<String, int[]> idx = ensureReverseIndex();
        int[] tuples = idx.get(source);
        if (tuples == null) return Collections.emptyList();
        // tuples is sorted by (origLine, origCol). Binary search for origLine.
        int lo = 0;
        int hi = (tuples.length / 4) - 1;
        int firstMatch = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int midLine = tuples[mid * 4];
            if (midLine < originalLine) {
                lo = mid + 1;
            } else if (midLine > originalLine) {
                hi = mid - 1;
            } else {
                firstMatch = mid;
                hi = mid - 1;
            }
        }
        if (firstMatch < 0) return Collections.emptyList();
        List<Mapping> out = new ArrayList<>();
        for (int i = firstMatch; i < tuples.length / 4; i++) {
            int off = i * 4;
            if (tuples[off] != originalLine) break;
            out.add(
                    new Mapping(
                            tuples[off + 2],
                            tuples[off + 3],
                            source,
                            tuples[off],
                            tuples[off + 1],
                            null));
        }
        return out;
    }

    public List<String> sources() {
        return sources;
    }

    public List<String> names() {
        return names;
    }

    public String sourceContent(String source) {
        if (sourcesContent == null) return null;
        int idx = sources.indexOf(source);
        if (idx < 0 || idx >= sourcesContent.length) return null;
        return sourcesContent[idx];
    }

    public String file() {
        return file;
    }

    // ---- Reverse index -------------------------------------------------

    private Map<String, int[]> ensureReverseIndex() {
        Map<String, int[]> idx = reverseIndex;
        if (idx != null) return idx;
        synchronized (this) {
            if (reverseIndex != null) return reverseIndex;
            Map<String, List<int[]>> tmp = new HashMap<>();
            for (int g = 0; g < linesByGen.length; g++) {
                int[] packed = linesByGen[g];
                if (packed == null) continue;
                for (int i = 0; i < packed.length; i += 5) {
                    int srcIdx = packed[i + 1];
                    if (srcIdx < 0) continue;
                    String src = sources.get(srcIdx);
                    tmp.computeIfAbsent(src, k -> new ArrayList<>())
                            .add(new int[] {packed[i + 2], packed[i + 3], g, packed[i]});
                }
            }
            Map<String, int[]> out = new HashMap<>();
            for (Map.Entry<String, List<int[]>> e : tmp.entrySet()) {
                List<int[]> list = e.getValue();
                list.sort(
                        (a, b) -> {
                            int c = Integer.compare(a[0], b[0]);
                            if (c != 0) return c;
                            return Integer.compare(a[1], b[1]);
                        });
                int[] flat = new int[list.size() * 4];
                for (int i = 0; i < list.size(); i++) {
                    System.arraycopy(list.get(i), 0, flat, i * 4, 4);
                }
                out.put(e.getKey(), flat);
            }
            reverseIndex = out;
            return out;
        }
    }

    // ---- Helpers -------------------------------------------------------

    private static void checkVersion(Map<String, Object> obj) {
        Object v = obj.get("version");
        if (v == null) return; // be permissive; many generators omit
        int ver = toInt(v);
        if (ver != 3) {
            throw new SourceMapException("unsupported source map version: " + ver);
        }
    }

    private static List<String> resolveSources(List<String> raw, String sourceRoot) {
        if (sourceRoot == null || sourceRoot.isEmpty()) return raw;
        List<String> out = new ArrayList<>(raw.size());
        boolean rootEndsWithSlash = sourceRoot.endsWith("/");
        for (String s : raw) {
            if (s == null) {
                out.add(null);
                continue;
            }
            if (hasScheme(s) || s.startsWith("/")) {
                out.add(s);
                continue;
            }
            if (rootEndsWithSlash || s.isEmpty()) {
                out.add(sourceRoot + s);
            } else {
                out.add(sourceRoot + "/" + s);
            }
        }
        return out;
    }

    private static boolean hasScheme(String s) {
        int colon = s.indexOf(':');
        if (colon <= 0) return false;
        for (int i = 0; i < colon; i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.')) return false;
        }
        return true;
    }

    private static List<String> asStringList(Object o) {
        if (o == null) return new ArrayList<>();
        if (!(o instanceof List)) throw new SourceMapException("expected array");
        List<String> out = new ArrayList<>();
        for (Object v : (List<?>) o) {
            if (v == null) {
                out.add(null);
            } else if (v instanceof String) {
                out.add((String) v);
            } else {
                throw new SourceMapException("expected string in array, got " + v.getClass());
            }
        }
        return out;
    }

    private static String[] asNullableStringArray(Object o) {
        if (o == null) return null;
        if (!(o instanceof List)) throw new SourceMapException("expected array for sourcesContent");
        List<?> list = (List<?>) o;
        String[] out = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Object v = list.get(i);
            if (v == null || v instanceof String) {
                out[i] = (String) v;
            } else {
                throw new SourceMapException(
                        "sourcesContent entry must be string or null, got " + v.getClass());
            }
        }
        return out;
    }

    private static int toInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        throw new SourceMapException("expected number, got " + (o == null ? "null" : o.getClass()));
    }

    private static String stringOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof String) return (String) o;
        throw new SourceMapException("expected string");
    }

    private static String stringOrEmpty(Object o) {
        if (o == null) return "";
        if (o instanceof String) return (String) o;
        throw new SourceMapException("expected string");
    }

    private static String stringOrThrow(Object o, String field) {
        if (o instanceof String) return (String) o;
        throw new SourceMapException("'" + field + "' must be a string");
    }

    /** Debug toString: not part of the public API. */
    @Override
    public String toString() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("file", file);
        info.put("sources", sources);
        info.put("names.size", names.size());
        int segs = 0;
        for (int[] line : linesByGen) if (line != null) segs += line.length / 5;
        info.put("lines", linesByGen.length);
        info.put("segments", segs);
        return "SourceMap" + info;
    }
}
