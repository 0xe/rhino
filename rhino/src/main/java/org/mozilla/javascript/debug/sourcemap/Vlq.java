/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.debug.sourcemap;

import java.util.Arrays;

/**
 * Base64-VLQ decoder used by source-map {@code "mappings"} strings.
 *
 * <p>Each VLQ integer is encoded as one or more base64 digits. Bit 5 of each digit (mask {@code
 * 0x20}) is the continuation flag; bits 0..4 are data. The assembled value's low bit is the sign of
 * the final magnitude, so {@code sign ? -(result >>> 1) : (result >>> 1)}.
 */
final class Vlq {

    private static final int[] B64 = new int[128];

    static {
        Arrays.fill(B64, -1);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int i = 0; i < 64; i++) {
            B64[alphabet.charAt(i)] = i;
        }
    }

    private Vlq() {}

    /**
     * Reads a single signed VLQ integer starting at {@code pos[0]} and advances {@code pos[0]} to
     * one past the final digit.
     *
     * @throws SourceMapException on truncated input or a non-base64 character
     */
    static int read(String s, int[] pos) {
        int result = 0;
        int shift = 0;
        boolean cont;
        do {
            if (pos[0] >= s.length()) {
                throw new SourceMapException("truncated VLQ at position " + pos[0]);
            }
            char ch = s.charAt(pos[0]++);
            if (ch > 127) {
                throw new SourceMapException(
                        "non-base64 character in VLQ: U+" + Integer.toHexString(ch));
            }
            int d = B64[ch];
            if (d < 0) {
                throw new SourceMapException("bad base64 digit '" + ch + "'");
            }
            cont = (d & 0x20) != 0;
            int digit = d & 0x1f;
            result |= digit << shift;
            shift += 5;
            if (shift > 31 && cont) {
                throw new SourceMapException("VLQ overflow");
            }
        } while (cont);
        boolean negative = (result & 1) != 0;
        int magnitude = result >>> 1;
        return negative ? -magnitude : magnitude;
    }
}
