/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.debug.sourcemap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny, self-contained JSON reader that produces plain Java structures: {@link Map} for objects,
 * {@link List} for arrays, {@link String}, {@link Long} / {@link Double} for numbers, {@link
 * Boolean}, or {@code null}.
 *
 * <p>Used by {@link SourceMap} so parsing does not require a live Rhino {@link
 * org.mozilla.javascript.Context}. That keeps {@code SourceMap.parse} callable from any thread at
 * any time — including from {@code RhinoException} stack-trace rewriting.
 */
final class MiniJson {

    private final String src;
    private int pos;

    private MiniJson(String src) {
        this.src = src;
        this.pos = 0;
    }

    static Object parse(String json) {
        if (json == null) throw new SourceMapException("JSON input is null");
        MiniJson p = new MiniJson(json);
        Object v = p.readValue();
        p.skipWs();
        if (p.pos != json.length()) {
            throw new SourceMapException("trailing content at position " + p.pos);
        }
        return v;
    }

    private Object readValue() {
        skipWs();
        if (pos >= src.length()) throw new SourceMapException("unexpected end of input");
        char c = src.charAt(pos);
        if (c == '{') return readObject();
        if (c == '[') return readArray();
        if (c == '"') return readString();
        if (c == 't' || c == 'f') return readBool();
        if (c == 'n') return readNull();
        if (c == '-' || (c >= '0' && c <= '9')) return readNumber();
        throw new SourceMapException("unexpected character '" + c + "' at " + pos);
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> out = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') {
            pos++;
            return out;
        }
        while (true) {
            skipWs();
            if (peek() != '"') throw new SourceMapException("expected string key at " + pos);
            String key = readString();
            skipWs();
            expect(':');
            Object value = readValue();
            out.put(key, value);
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return out;
            }
            throw new SourceMapException("expected ',' or '}' at " + pos);
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> out = new ArrayList<>();
        skipWs();
        if (peek() == ']') {
            pos++;
            return out;
        }
        while (true) {
            out.add(readValue());
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return out;
            }
            throw new SourceMapException("expected ',' or ']' at " + pos);
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (pos >= src.length()) throw new SourceMapException("bad escape at eof");
                char e = src.charAt(pos++);
                switch (e) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 > src.length()) {
                            throw new SourceMapException("truncated unicode escape at " + pos);
                        }
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            code = (code << 4) | hex(src.charAt(pos++));
                        }
                        sb.append((char) code);
                        break;
                    default:
                        throw new SourceMapException("bad escape \\" + e);
                }
            } else if (c < 0x20) {
                throw new SourceMapException(
                        "unescaped control character U+" + Integer.toHexString(c));
            } else {
                sb.append(c);
            }
        }
        throw new SourceMapException("unterminated string");
    }

    private Boolean readBool() {
        if (src.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        throw new SourceMapException("expected boolean at " + pos);
    }

    private Object readNull() {
        if (src.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw new SourceMapException("expected null at " + pos);
    }

    private Number readNumber() {
        int start = pos;
        if (src.charAt(pos) == '-') pos++;
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        boolean isFloat = false;
        if (pos < src.length() && src.charAt(pos) == '.') {
            isFloat = true;
            pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        }
        if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            isFloat = true;
            pos++;
            if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        }
        String raw = src.substring(start, pos);
        try {
            if (isFloat) return Double.parseDouble(raw);
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new SourceMapException("bad number '" + raw + "'", e);
        }
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                pos++;
            } else {
                return;
            }
        }
    }

    private char peek() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private void expect(char c) {
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw new SourceMapException("expected '" + c + "' at " + pos);
        }
        pos++;
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        throw new SourceMapException("bad hex digit '" + c + "'");
    }
}
