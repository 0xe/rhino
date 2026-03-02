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

    // --- Optimization-specific integration tests ---

    @Test
    void constantFolding() {
        assertResultAtAllLevels(5.0, "function f() { var x = 2 + 3; return x; } f()");
    }

    @Test
    void deadCodeAfterConstantFold() {
        // LVN folds 2+3→5, DCE can then clean up any dead intermediates
        assertResultAtAllLevels(
                15.0, "function f() { var a = 2 + 3; var b = a * 3; return b; } f()");
    }

    @Test
    void deadVariableElimination() {
        // var unused = 1; should not affect result
        assertResultAtAllLevels(42.0, "function f() { var unused = 1; var x = 42; return x; } f()");
    }

    @Test
    void copyPropagation() {
        assertResultAtAllLevels(
                10.0, "function f() { var a = 5; var b = a; var c = b; return a + c; } f()");
    }

    @Test
    void callPreservesSemantics() {
        // Ensure CALL-based invalidation keeps correctness
        assertResultAtAllLevels(
                "hello", "function f() { var a = 'hello'; (function(){})(); return a; } f()");
    }

    @Test
    void multipleConstantFolds() {
        assertResultAtAllLevels(
                50.0, "function f() { var a = 2 + 3; var b = 4 + 6; return a * b; } f()");
    }

    @Test
    void optimizationsToggleOff() {
        // Custom ContextFactory that disables shared optimizations
        ContextFactory noOptFactory =
                new ContextFactory() {
                    @Override
                    protected boolean hasFeature(Context cx, int featureIndex) {
                        if (featureIndex == Context.FEATURE_SHARED_OPTIMIZATIONS) {
                            return false;
                        }
                        return super.hasFeature(cx, featureIndex);
                    }
                };

        for (int level : new int[] {-1, 9}) {
            try (Context cx = noOptFactory.enterContext()) {
                cx.setOptimizationLevel(level);
                cx.setLanguageVersion(Context.VERSION_ES6);
                Scriptable scope = cx.initStandardObjects();
                Object result =
                        cx.evaluateString(
                                scope,
                                "function f() { var x = 2 + 3; return x; } f()",
                                "test",
                                1,
                                null);
                assertEquals(5.0, ((Number) result).doubleValue(), "toggle off, level " + level);
            }
        }
    }

    @Test
    void mixedArithmeticWithSideEffects() {
        // Ensure side-effectful code mixed with optimizable code works correctly
        assertResultAtAllLevels(
                8.0,
                "function f() {"
                        + "  var result = [];"
                        + "  var a = 2 + 3;"
                        + "  result.push(a);"
                        + "  var b = a + 3;"
                        + "  return b;"
                        + "} f()");
    }

    @Test
    void varHoistingNotBrokenByCopyProp() {
        // Regression: testsrc/tests/ecma/Statements/12.2-1.js
        // var x is hoisted in f(), so "var a = x" reads the local (undefined) x, not the outer x.
        // Copy propagation must not replace "a" with "x" when x is later reassigned.
        assertResultAtAllLevels(
                "undefined",
                "var x = 3;" + "function f() { var a = x; var x = 23; return typeof a; }" + "f()");
    }
}
