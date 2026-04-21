/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.debug.sourcemap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

public class SourceMapTest {

    @Test
    public void vlqRoundTripsBasicValues() {
        // A = 0, C = 1, D = -1, E = 2, F = -2, etc.
        assertEquals(0, readOne("A"));
        assertEquals(1, readOne("C"));
        assertEquals(-1, readOne("D"));
        assertEquals(2, readOne("E"));
        assertEquals(-2, readOne("F"));
    }

    @Test
    public void vlqMultiDigitValues() {
        // 16 = 0b10000; encode magnitude 16 → value 32; base64 of 32 = 'g'.
        // But 32 needs continuation since it's 6 bits, so: 16 in sign-prefixed = 32,
        // split into two 5-bit groups: first 0b00000 (with continuation bit set = 0x20
        // = 'g'), then 0b00001 ('B'). So 16 encodes as "gB".
        assertEquals(16, readOne("gB"));
    }

    @Test
    public void vlqTruncatedInputThrows() {
        // 'g' has continuation bit set; with no next char we must error.
        int[] pos = {0};
        assertThrows(SourceMapException.class, () -> Vlq.read("g", new int[] {0}));
    }

    @Test
    public void simpleOneToOneMap() {
        // 3 generated lines, each mapped to source[0]="foo.js" at their own line, col 0.
        //   [0,0,0,0] ; [0,0,1,0] ; [0,0,1,0]
        String json =
                "{\"version\":3,\"file\":\"out.js\","
                        + "\"sources\":[\"foo.js\"],\"names\":[],"
                        + "\"mappings\":\"AAAA;AACA;AACA\"}";
        SourceMap sm = SourceMap.parse(json);
        assertEquals(List.of("foo.js"), sm.sources());
        Mapping m0 = sm.originalFor(0, 0);
        assertNotNull(m0);
        assertEquals("foo.js", m0.source);
        assertEquals(0, m0.originalLine);
        Mapping m2 = sm.originalFor(2, 0);
        assertNotNull(m2);
        assertEquals(2, m2.originalLine);
    }

    @Test
    public void binarySearchPicksLargestGenColNotExceedingQuery() {
        // Single generated line with segments at cols 0, 5, 10.
        // Each maps to source[0] at arbitrary original positions.
        //   [0, 0, 0, 0]  → col 0   genCol=0 src=0 origLine=0 origCol=0
        //   [+5, 0, 0, +2] → col 5  origCol=2
        //   [+5, 0, 0, +3] → col 10 origCol=5
        // VLQ: AAAA,KAAE,KAAG
        String json =
                "{\"version\":3,\"sources\":[\"a.js\"],\"names\":[],"
                        + "\"mappings\":\"AAAA,KAAE,KAAG\"}";
        SourceMap sm = SourceMap.parse(json);
        assertEquals(0, sm.originalFor(0, 0).originalColumn);
        assertEquals(0, sm.originalFor(0, 4).originalColumn); // sticks to col 0
        assertEquals(2, sm.originalFor(0, 5).originalColumn); // col 5 segment
        assertEquals(2, sm.originalFor(0, 9).originalColumn);
        assertEquals(5, sm.originalFor(0, 10).originalColumn); // col 10 segment
        assertEquals(5, sm.originalFor(0, 100).originalColumn); // last wins
    }

    @Test
    public void emptyLinesInMappings() {
        // ";;;" means 3 empty lines before any segment. Then on line 3, "AAAA".
        String json =
                "{\"version\":3,\"sources\":[\"x.js\"],\"names\":[]," + "\"mappings\":\";;;AAAA\"}";
        SourceMap sm = SourceMap.parse(json);
        assertNull(sm.originalFor(0, 0));
        assertNull(sm.originalFor(1, 0));
        assertNull(sm.originalFor(2, 0));
        Mapping m = sm.originalFor(3, 0);
        assertNotNull(m);
        assertEquals(0, m.originalLine);
    }

    @Test
    public void singleFieldSegmentIsUnmapped() {
        // "A" on its own line: genCol=0, no source fields → unmapped.
        String json = "{\"version\":3,\"sources\":[\"x.js\"],\"names\":[],\"mappings\":\"A\"}";
        SourceMap sm = SourceMap.parse(json);
        Mapping m = sm.originalFor(0, 0);
        assertNotNull(m);
        assertNull(m.source);
    }

    @Test
    public void fiveFieldSegmentsPopulateName() {
        // Single line, one 5-field segment: [0,0,0,0,0] → name[0]
        String json =
                "{\"version\":3,\"sources\":[\"x.js\"],\"names\":[\"helloName\"],"
                        + "\"mappings\":\"AAAAA\"}";
        SourceMap sm = SourceMap.parse(json);
        Mapping m = sm.originalFor(0, 0);
        assertNotNull(m);
        assertEquals("helloName", m.name);
    }

    @Test
    public void sourceRootIsApplied() {
        String json =
                "{\"version\":3,\"sources\":[\"foo.ts\"],\"sourceRoot\":\"src/\","
                        + "\"names\":[],\"mappings\":\"AAAA\"}";
        SourceMap sm = SourceMap.parse(json);
        assertEquals(List.of("src/foo.ts"), sm.sources());
        Mapping m = sm.originalFor(0, 0);
        assertEquals("src/foo.ts", m.source);
    }

    @Test
    public void sourceRootWithoutTrailingSlashAddsOne() {
        String json =
                "{\"version\":3,\"sources\":[\"foo.ts\"],\"sourceRoot\":\"src\","
                        + "\"names\":[],\"mappings\":\"AAAA\"}";
        SourceMap sm = SourceMap.parse(json);
        assertEquals(List.of("src/foo.ts"), sm.sources());
    }

    @Test
    public void sourceRootDoesNotTouchAbsoluteOrSchemeSources() {
        String json =
                "{\"version\":3,\"sources\":[\"/abs/path.ts\",\"https://x/y.ts\"],"
                        + "\"sourceRoot\":\"src/\",\"names\":[],\"mappings\":\"AAAA\"}";
        SourceMap sm = SourceMap.parse(json);
        assertEquals(List.of("/abs/path.ts", "https://x/y.ts"), sm.sources());
    }

    @Test
    public void sourcesContentRoundTrip() {
        String json =
                "{\"version\":3,\"sources\":[\"a.js\",\"b.js\"],"
                        + "\"sourcesContent\":[\"var x=1;\",null],"
                        + "\"names\":[],\"mappings\":\"AAAA\"}";
        SourceMap sm = SourceMap.parse(json);
        assertEquals("var x=1;", sm.sourceContent("a.js"));
        assertNull(sm.sourceContent("b.js"));
        assertNull(sm.sourceContent("c.js"));
    }

    @Test
    public void missingSourcesContentReturnsNull() {
        String json = "{\"version\":3,\"sources\":[\"a.js\"],\"names\":[],\"mappings\":\"AAAA\"}";
        SourceMap sm = SourceMap.parse(json);
        assertNull(sm.sourceContent("a.js"));
    }

    @Test
    public void generatedForReverseLookup() {
        // gen line 0 → src line 0, gen line 1 → src line 2, gen line 2 → src line 2 (again).
        //   [0,0,0,0] ; [0,0,2,0] ; [0,0,0,0]
        // Deltas: src=0 then 0, origLine delta: 0 → 2 → 0 (so absolute 0, 2, 2).
        // VLQ fields:
        //   "AAAA"
        //   "AAEA"   (origLine delta 2)
        //   "AAAA"   (origLine delta 0 → absolute 2)
        String json =
                "{\"version\":3,\"sources\":[\"a.js\"],\"names\":[],"
                        + "\"mappings\":\"AAAA;AAEA;AAAA\"}";
        SourceMap sm = SourceMap.parse(json);
        List<Mapping> for0 = sm.generatedFor("a.js", 0);
        assertEquals(1, for0.size());
        assertEquals(0, for0.get(0).generatedLine);
        List<Mapping> for2 = sm.generatedFor("a.js", 2);
        assertEquals(2, for2.size());
        // Sorted by (origLine, origCol); both map gen lines 1 and 2 back to source line 2.
        assertTrue(for2.get(0).generatedLine == 1 || for2.get(0).generatedLine == 2);
        assertTrue(for2.get(1).generatedLine == 1 || for2.get(1).generatedLine == 2);
        assertEquals(0, sm.generatedFor("missing.js", 0).size());
    }

    @Test
    public void indexedMapFlattens() {
        // Two sections: first covers gen line 0, second starts at gen line 2.
        String inner1 = "{\"version\":3,\"sources\":[\"a.js\"],\"names\":[],\"mappings\":\"AAAA\"}";
        String inner2 = "{\"version\":3,\"sources\":[\"b.js\"],\"names\":[],\"mappings\":\"AAAA\"}";
        String json =
                "{\"version\":3,\"sections\":["
                        + "{\"offset\":{\"line\":0,\"column\":0},\"map\":"
                        + inner1
                        + "},{\"offset\":{\"line\":2,\"column\":0},\"map\":"
                        + inner2
                        + "}]}";
        SourceMap sm = SourceMap.parse(json);
        assertEquals(List.of("a.js", "b.js"), sm.sources());
        assertEquals("a.js", sm.originalFor(0, 0).source);
        assertEquals("b.js", sm.originalFor(2, 0).source);
    }

    @Test
    public void parseDataUriBase64() {
        String inner = "{\"version\":3,\"sources\":[\"x.js\"],\"names\":[],\"mappings\":\"AAAA\"}";
        String dataUri =
                "data:application/json;base64,"
                        + Base64.getEncoder().encodeToString(inner.getBytes());
        SourceMap sm = SourceMap.parseDataUri(dataUri);
        assertEquals(List.of("x.js"), sm.sources());
    }

    @Test
    public void parseDataUriPlain() {
        String inner = "{\"version\":3,\"sources\":[\"x.js\"],\"names\":[],\"mappings\":\"AAAA\"}";
        // url-encode quotes so the URI is syntactically valid (even though the parser is lenient)
        String encoded = java.net.URLEncoder.encode(inner, java.nio.charset.StandardCharsets.UTF_8);
        SourceMap sm = SourceMap.parseDataUri("data:application/json," + encoded);
        assertEquals(List.of("x.js"), sm.sources());
    }

    @Test
    public void withBaseUrlResolvesRelativeSourcesAgainstMapUrl() {
        String json =
                "{\"version\":3,\"sources\":[\"original.js\",\"/abs.js\",\"https://x/y.js\"],"
                        + "\"names\":[],\"mappings\":\"AAAA\"}";
        SourceMap sm = SourceMap.parse(json, "file:///project/build/generated.js.map");
        assertEquals(
                List.of("file:///project/build/original.js", "/abs.js", "https://x/y.js"),
                sm.sources());
    }

    @Test
    public void rejectsUnsupportedVersion() {
        String json = "{\"version\":2,\"sources\":[\"a.js\"],\"names\":[],\"mappings\":\"\"}";
        assertThrows(SourceMapException.class, () -> SourceMap.parse(json));
    }

    @Test
    public void rejectsMalformedJson() {
        assertThrows(SourceMapException.class, () -> SourceMap.parse("{not-json"));
    }

    @Test
    public void rejectsInvalidMappingsSegmentLength() {
        // Two fields only is not a legal segment length.
        String json = "{\"version\":3,\"sources\":[\"a.js\"],\"names\":[],\"mappings\":\"AA\"}";
        assertThrows(SourceMapException.class, () -> SourceMap.parse(json));
    }

    // ---- helpers ----

    private static int readOne(String s) {
        int[] pos = {0};
        return Vlq.read(s, pos);
    }
}
