package org.mozilla.javascript.tests;

import static org.junit.Assert.*;

import org.junit.Test;
import org.mozilla.javascript.*;

/** Tests for the BytecodeToClassCompiler implementation. */
public class BytecodeToClassCompilerTest {

    /** Sets up a context with the BytecodeToClassCompiler */
    private Context createContext() {
        Context cx = Context.enter();
        cx.setOptimizationLevel(9); // Maximum optimization
        cx.setLanguageVersion(Context.VERSION_ES6);
        cx.setFunctionCompiler(new BytecodeToClassCompiler());
        return cx;
    }

    /** Tests basic arithmetic operations */
    @Test
    public void testBasicArithmetic() {
        Context cx = createContext();
        try {
            ScriptableObject scope = cx.initStandardObjects();

            // Simple addition
            Object result =
                    cx.evaluateString(
                            scope,
                            "function add(a, b) { return a + b; }; add(2, 3);",
                            "test",
                            1,
                            null);
            assertEquals(5.0, ((Number) result).doubleValue(), 0.0001);

            // Mixed operations
            result =
                    cx.evaluateString(
                            scope,
                            "function calc(a, b, c) { return a * b + c; }; calc(3, 4, 2);",
                            "test",
                            1,
                            null);
            assertEquals(14.0, ((Number) result).doubleValue(), 0.0001);
        } finally {
            Context.exit();
        }
    }

    /** Tests variable assignments and control flow */
    @Test
    public void testVariablesAndControlFlow() {
        Context cx = createContext();
        try {
            ScriptableObject scope = cx.initStandardObjects();

            // Variable assignment and if/else
            String script =
                    "function test(x) { "
                            + "  var result = 0; "
                            + "  if (x > 5) { "
                            + "    result = x * 2; "
                            + "  } else { "
                            + "    result = x + 2; "
                            + "  } "
                            + "  return result; "
                            + "}; "
                            + "test(10) + ',' + test(3);";

            Object result = cx.evaluateString(scope, script, "test", 1, null);
            assertEquals("20,5", result);
        } finally {
            Context.exit();
        }
    }

    /** Tests object and array operations */
    @Test
    public void testObjectsAndArrays() {
        Context cx = createContext();
        try {
            ScriptableObject scope = cx.initStandardObjects();

            // Object properties
            String script =
                    "function testObject() { "
                            + "  var obj = {a: 1, b: 2}; "
                            + "  obj.c = 3; "
                            + "  return obj.a + obj.b + obj.c; "
                            + "}; "
                            + "testObject();";

            Object result = cx.evaluateString(scope, script, "test", 1, null);
            assertEquals(6.0, ((Number) result).doubleValue(), 0.0001);

            // Arrays
            script =
                    "function testArray() { "
                            + "  var arr = [1, 2, 3]; "
                            + "  arr.push(4); "
                            + "  return arr[0] + arr[1] + arr[2] + arr[3]; "
                            + "}; "
                            + "testArray();";

            result = cx.evaluateString(scope, script, "test", 1, null);
            assertEquals(10.0, ((Number) result).doubleValue(), 0.0001);
        } finally {
            Context.exit();
        }
    }

    /** Tests function calls and recursion */
    @Test
    public void testFunctionCalls() {
        Context cx = createContext();
        try {
            ScriptableObject scope = cx.initStandardObjects();

            // Function calls
            String script =
                    "function add(a, b) { return a + b; }; "
                            + "function multiply(a, b) { return a * b; }; "
                            + "function calculate(a, b) { return add(a, b) * multiply(a, b); }; "
                            + "calculate(3, 4);";

            Object result = cx.evaluateString(scope, script, "test", 1, null);
            assertEquals(
                    84.0, ((Number) result).doubleValue(), 0.0001); // (3+4) * (3*4) = 7 * 12 = 84

            // Recursion - factorial
            script =
                    "function factorial(n) { "
                            + "  if (n <= 1) return 1; "
                            + "  return n * factorial(n-1); "
                            + "}; "
                            + "factorial(5);";

            result = cx.evaluateString(scope, script, "test", 1, null);
            assertEquals(120.0, ((Number) result).doubleValue(), 0.0001); // 5! = 120
        } finally {
            Context.exit();
        }
    }

    /** Tests increment/decrement operations */
    @Test
    public void testIncrementDecrement() {
        Context cx = createContext();
        try {
            ScriptableObject scope = cx.initStandardObjects();

            String script =
                    "function testIncDec() { "
                            + "  var a = 5; "
                            + "  var b = a++; "
                            + "  var c = ++a; "
                            + "  var d = a--; "
                            + "  var e = --a; "
                            + "  return b + ',' + c + ',' + d + ',' + e; "
                            + "}; "
                            + "testIncDec();";

            Object result = cx.evaluateString(scope, script, "test", 1, null);
            assertEquals("5,7,7,5", result);
        } finally {
            Context.exit();
        }
    }

    /** Tests logical operators */
    @Test
    public void testLogicalOperators() {
        Context cx = createContext();
        try {
            ScriptableObject scope = cx.initStandardObjects();

            String script =
                    "function testLogical() { "
                            + "  var results = []; "
                            + "  results.push(true && false); "
                            + "  results.push(true || false); "
                            + "  results.push(false && maybeError()); "
                            + // Short-circuit should prevent error
                            "  results.push(true || maybeError()); "
                            + // Short-circuit should prevent error
                            "  function maybeError() { throw 'Should not be called'; } "
                            + "  return results.join(','); "
                            + "}; "
                            + "testLogical();";

            Object result = cx.evaluateString(scope, script, "test", 1, null);
            assertEquals("false,true,false,true", result);
        } finally {
            Context.exit();
        }
    }
}
