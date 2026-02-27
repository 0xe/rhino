/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.optimizer;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Scriptable;

/**
 * Integration tests verifying that the SharedOptimizer pass does not change program semantics.
 * Tests run at both optimization levels (-1 = interpreter, 9 = compiled).
 */
class SharedOptimizerTest {

    private static Object eval(String script, int optLevel) {
        try (Context cx = ContextFactory.getGlobal().enterContext()) {
            cx.setOptimizationLevel(optLevel);
            cx.setLanguageVersion(Context.VERSION_ES6);
            Scriptable scope = cx.initStandardObjects();
            return cx.evaluateString(scope, script, "test", 1, null);
        }
    }

    private static void assertResultAtAllLevels(Object expected, String script) {
        for (int level : new int[] {-1, 9}) {
            Object result = eval(script, level);
            if (expected instanceof Number && result instanceof Number) {
                assertEquals(
                        ((Number) expected).doubleValue(),
                        ((Number) result).doubleValue(),
                        "at opt level " + level);
            } else {
                assertEquals(expected, result, "at opt level " + level);
            }
        }
    }

    @Test
    void straightLineArithmetic() {
        assertResultAtAllLevels(
                6.0, "function f() { var a = 1; var b = 2; var c = 3; return a + b + c; } f()");
    }

    @Test
    void ifElseBranching() {
        assertResultAtAllLevels(
                "yes", "function f(x) { if (x > 0) return 'yes'; else return 'no'; } f(1)");
        assertResultAtAllLevels(
                "no", "function f(x) { if (x > 0) return 'yes'; else return 'no'; } f(-1)");
    }

    @Test
    void whileLoop() {
        assertResultAtAllLevels(
                55.0,
                "function f() { var s = 0; var i = 1; while (i <= 10) { s += i; i++; } return s; } f()");
    }

    @Test
    void forLoop() {
        assertResultAtAllLevels(
                45.0,
                "function f() { var s = 0; for (var i = 0; i < 10; i++) { s += i; } return s; } f()");
    }

    @Test
    void breakAndContinue() {
        assertResultAtAllLevels(
                12.0,
                "function f() { var s = 0; for (var i = 0; i < 100; i++) { if (i >= 5) break; if (i % 2 == 0) continue; s += i; } return s + s + 4; } f()");
    }

    @Test
    void switchStatement() {
        assertResultAtAllLevels(
                "b",
                "function f(x) { switch(x) { case 1: return 'a'; case 2: return 'b'; default: return 'c'; } } f(2)");
    }

    @Test
    void tryCatch() {
        assertResultAtAllLevels(
                "caught",
                "function f() { try { throw 'err'; } catch(e) { return 'caught'; } } f()");
    }

    @Test
    void nestedFunctions() {
        assertResultAtAllLevels(
                7.0,
                "function f(x) { function g(y) { return y + 2; } return g(x) + g(x - 1); } f(2)");
    }

    @Test
    void recursion() {
        assertResultAtAllLevels(
                120.0,
                "function factorial(n) { if (n <= 1) return 1; return n * factorial(n - 1); } factorial(5)");
    }
}
