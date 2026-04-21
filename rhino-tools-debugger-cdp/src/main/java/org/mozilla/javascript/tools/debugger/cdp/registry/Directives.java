/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.debugger.cdp.registry;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code //# sourceMappingURL=...} / {@code //# sourceURL=...} directives (and the block
 * comment forms) out of source text. The last occurrence wins, per the convention DevTools and
 * TypeScript honor.
 */
public final class Directives {

    private Directives() {}

    private static final Pattern LINE_MAP =
            Pattern.compile(
                    "(?m)^[\\t ]*//[#@][\\t ]*sourceMappingURL[\\t ]*=[\\t ]*(\\S+)[\\t ]*$");
    private static final Pattern BLOCK_MAP =
            Pattern.compile("/\\*[#@][\\t ]*sourceMappingURL[\\t ]*=[\\t ]*(\\S+?)[\\t ]*\\*/");
    private static final Pattern LINE_URL =
            Pattern.compile("(?m)^[\\t ]*//[#@][\\t ]*sourceURL[\\t ]*=[\\t ]*(\\S+)[\\t ]*$");
    private static final Pattern BLOCK_URL =
            Pattern.compile("/\\*[#@][\\t ]*sourceURL[\\t ]*=[\\t ]*(\\S+?)[\\t ]*\\*/");

    public static String parseSourceMappingURL(String src) {
        return lastMatch(src, LINE_MAP, BLOCK_MAP);
    }

    public static String parseSourceURL(String src) {
        return lastMatch(src, LINE_URL, BLOCK_URL);
    }

    private static String lastMatch(String src, Pattern a, Pattern b) {
        if (src == null) return null;
        String result = null;
        Matcher m = a.matcher(src);
        while (m.find()) result = m.group(1);
        Matcher m2 = b.matcher(src);
        while (m2.find()) result = m2.group(1);
        return result;
    }
}
