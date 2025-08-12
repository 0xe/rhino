/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import static org.junit.Assert.*;

import org.junit.Test;

public class FunctionCompilationTest {

    private static class TestContextFactory extends ContextFactory {
        @Override
        protected boolean hasFeature(Context cx, int featureIndex) {
            if (featureIndex == Context.FEATURE_FUNCTION_COMPILATION) {
                return true;
            }
            return super.hasFeature(cx, featureIndex);
        }

        @Override
        protected void onContextCreated(Context cx) {
            cx.setInterpretedMode(true);
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setFunctionCompilationThreshold(5); // Set a low threshold for testing
            cx.setFunctionCompilationIcodeSizeThreshold(
                    5); // Set a very low icode size threshold for basic tests
            super.onContextCreated(cx);
        }
    }

    private static final TestContextFactory factory = new TestContextFactory();

    private void withContext(TestWithContext test) throws Exception {
        Context cx = factory.enterContext();
        try {
            ScriptableObject scope = cx.initStandardObjects();
            test.run(cx, scope);
        } finally {
            Context.exit();
        }
    }

    @FunctionalInterface
    private interface TestWithContext {
        void run(Context cx, ScriptableObject scope) throws Exception;
    }

    @Test
    public void testInvocationCounting() throws Exception {
        withContext(
                (cx, scope) -> {
                    // Define a function that will be invoked multiple times
                    String script =
                            "function test() { return 'test'; }\n"
                                    + "var f = test;\n"
                                    + // Store function reference
                                    "f(); f(); f(); f(); f();";

                    // Create a mock function compiler to track compilation
                    final boolean[] compiled = {false};
                    cx.setFunctionCompiler(
                            new Context.FunctionCompiler() {
                                @Override
                                public Callable compile(
                                        InterpretedFunction ifun,
                                        Context cx,
                                        Scriptable scope,
                                        Scriptable thisObj,
                                        Object[] args) {
                                    compiled[0] = true;
                                    assertEquals(
                                            "Function should have been called 5 times before compilation",
                                            5,
                                            ifun.getInvocationCount());
                                    return null; // Return null to continue with interpretation
                                }
                            });

                    // Execute the script
                    cx.evaluateString(scope, script, "test", 1, null);

                    // Get the function object to check its state
                    Object testFn = ScriptableObject.getProperty(scope, "test");
                    assertTrue("test should be a function", testFn instanceof InterpretedFunction);

                    // The function should have been marked for compilation after 5 invocations
                    assertTrue("Function should be marked for compilation", compiled[0]);
                    //            assertTrue("Function should be marked as compiled",
                    // ((InterpretedFunction)testFn).isCompiled());
                });
    }

    @Test
    public void testInvocationCountingCallNewCompiler() throws Exception {
        withContext(
                (cx, scope) -> {
                    // Define a function that will be invoked multiple times
                    String script =
                            "function test() { return 'test'; }\n"
                                    + "test(); test(); test(); test(); test(); // last invocation should trigger compilation";

                    // Create a mock function compiler to track compilation
                    cx.setFunctionCompiler(new IFnToClassCompiler());

                    // Execute the script
                    Object foo = cx.evaluateString(scope, script, "test", 1, null);
                    assertEquals("test", foo);

                    // Get the function object to check its state
                    Object testFn = ScriptableObject.getProperty(scope, "test");
                    assertTrue("test should be a function", testFn instanceof Function);
                    assertTrue(
                            "test should be an InterpretedFunction",
                            testFn instanceof InterpretedFunction);
                    InterpretedFunction ifun = (InterpretedFunction) testFn;
                    assertTrue("Function should be marked as compiled", ifun.isCompiled());
                });
    }

    @Test
    public void testFunctionWithContinuationDoesNotCompile() throws Exception {
        withContext(
                (cx, scope) -> {
                    String script =
                            "function testContinuation() {\n"
                                    + "    var c = getContinuation();\n"
                                    + "    return 'test';\n"
                                    + "}\n"
                                    + "function getContinuation() { return new Continuation(); }\n"
                                    + "for (var i = 0; i < 10; i++) {\n"
                                    + "    try { testContinuation(); } catch(e) {}\n"
                                    + "}\n";

                    cx.setFunctionCompiler(new IFnToClassCompiler());

                    try {
                        cx.evaluateString(scope, script, "test", 1, null);
                    } catch (Exception e) {
                    }

                    Object testFn = ScriptableObject.getProperty(scope, "testContinuation");
                    assertTrue(
                            "testContinuation should be a function",
                            testFn instanceof InterpretedFunction);

                    InterpretedFunction ifun = (InterpretedFunction) testFn;
                    assertFalse(
                            "Function with continuation should not be compiled", ifun.isCompiled());
                });
    }

    @Test
    public void testCompilationThreshold() throws Exception {
        withContext(
                (cx, scope) -> {
                    // Track invocations and compilation
                    final boolean[] wasCompiled = {false};

                    // Define a function that will be invoked multiple times
                    String script =
                            "function test() { return 'test'; }\n"
                                    + "var f = test;\n"
                                    + // Store function reference
                                    "f(); f(); f(); f(); f();";

                    cx.setFunctionCompiler(
                            new Context.FunctionCompiler() {
                                @Override
                                public Callable compile(
                                        InterpretedFunction ifun,
                                        Context cx,
                                        Scriptable scope,
                                        Scriptable thisObj,
                                        Object[] args) {
                                    wasCompiled[0] = true;
                                    // Return a simple callable that returns a fixed value
                                    return new BaseFunction() {
                                        @Override
                                        public Object call(
                                                Context cx,
                                                Scriptable scope,
                                                Scriptable thisObj,
                                                Object[] args) {
                                            return "compiled";
                                        }
                                    };
                                }
                            });

                    // Execute the script
                    Object result = cx.evaluateString(scope, script, "test", 1, null);

                    // The function should have been compiled and the compiled version should be
                    // used
                    assertTrue("Function should have been compiled", wasCompiled[0]);

                    // The last call should return the compiled result
                    assertEquals("Compiled function should return 'compiled'", "compiled", result);

                    // Verify the function is marked as compiled
                    Object testFn = ScriptableObject.getProperty(scope, "test");
                    assertTrue("test should be a function", testFn instanceof Function);
                    assertTrue(
                            "test should be an InterpretedFunction",
                            testFn instanceof InterpretedFunction);
                    InterpretedFunction ifun = (InterpretedFunction) testFn;
                    assertTrue("Function should be marked as compiled", ifun.isCompiled());
                });
    }

    @Test
    public void testInvocationCountIncrements() throws Exception {
        withContext(
                (cx, scope) -> {
                    // Define a function and call it multiple times
                    String script =
                            "function test() { return 'test'; }\n"
                                    + "var f = test;\n"
                                    + // Store function reference
                                    "f(); f(); f(); f(); f();";

                    // Execute the script
                    cx.evaluateString(scope, script, "test", 1, null);

                    // Get the function object after execution
                    Object testFn = ScriptableObject.getProperty(scope, "test");
                    assertTrue("test should be a function", testFn instanceof InterpretedFunction);

                    // Verify the invocation count
                    InterpretedFunction ifun = (InterpretedFunction) testFn;
                    assertEquals(
                            "Function should have been called 5 times",
                            5,
                            ifun.getInvocationCount());
                    //            assertTrue("Function should be marked for compilation",
                    // ifun.isCompiled());
                });
    }

    @Test
    public void testMultipleFunctions() throws Exception {
        withContext(
                (cx, scope) -> {
                    // Track which functions were compiled
                    final boolean[] func1Compiled = {false};
                    final boolean[] func2Compiled = {false};

                    String script =
                            "function func1() { return 'func1'; }\n"
                                    + "function func2() { return 'func2'; }\n"
                                    + "// Store function references\n"
                                    + "var f1 = func1;\n"
                                    + "var f2 = func2;\n"
                                    + "// Call func1 5 times\n"
                                    + "for (var i = 0; i < 5; i++) { f1(); }\n"
                                    + "// Call func2 3 times\n"
                                    + "for (var i = 0; i < 3; i++) { f2(); }\n"
                                    + "// Return a value to verify execution\n"
                                    + "'done';";

                    cx.setFunctionCompiler(
                            new Context.FunctionCompiler() {
                                @Override
                                public Callable compile(
                                        InterpretedFunction ifun,
                                        Context cx,
                                        Scriptable scope,
                                        Scriptable thisObj,
                                        Object[] args) {
                                    // Check which function is being compiled
                                    if (ifun.getFunctionName().equals("func1")) {
                                        func1Compiled[0] = true;
                                    } else if (ifun.getFunctionName().equals("func2")) {
                                        func2Compiled[0] = true;
                                    }
                                    return null; // Continue with interpretation
                                }
                            });

                    // Execute the script
                    Object result = cx.evaluateString(scope, script, "test", 1, null);

                    // Get the function objects to check their states
                    Object func1 = ScriptableObject.getProperty(scope, "func1");
                    Object func2 = ScriptableObject.getProperty(scope, "func2");
                    assertTrue("func1 should be a function", func1 instanceof InterpretedFunction);
                    assertTrue("func2 should be a function", func2 instanceof InterpretedFunction);

                    InterpretedFunction ifun1 = (InterpretedFunction) func1;
                    InterpretedFunction ifun2 = (InterpretedFunction) func2;

                    // Verify results
                    assertEquals("Script should complete successfully", "done", result);
                    assertTrue("func1 should be marked for compilation", func1Compiled[0]);
                    assertEquals(
                            "func1 should have been called 5 times", 5, ifun1.getInvocationCount());
                    //            assertTrue("func1 should be marked as compiled",
                    // ifun1.isCompiled());

                    assertFalse("func2 should not be marked for compilation yet", func2Compiled[0]);
                    assertEquals(
                            "func2 should have been called 3 times", 3, ifun2.getInvocationCount());
                    //            assertFalse("func2 should not be marked as compiled",
                    // ifun2.isCompiled());
                });
    }

    @Test
    public void testDebugIcodeSizes() throws Exception {
        withContext(
                (cx, scope) -> {
                    // Define various functions to see their icode sizes
                    String script =
                            "function simpleFunction() { return 'simple'; }\n"
                                    + "function mediumFunction(x) { return x + 1; }\n"
                                    + "function complexFunction(x, y) {\n"
                                    + "  var a = x + y;\n"
                                    + "  if (a > 10) {\n"
                                    + "    return a * 2;\n"
                                    + "  } else {\n"
                                    + "    return a + 1;\n"
                                    + "  }\n"
                                    + "}\n";

                    // Execute the script
                    cx.evaluateString(scope, script, "debug-test", 1, null);

                    // Check icode sizes
                    Object simpleFn = ScriptableObject.getProperty(scope, "simpleFunction");
                    Object mediumFn = ScriptableObject.getProperty(scope, "mediumFunction");
                    Object complexFn = ScriptableObject.getProperty(scope, "complexFunction");

                    assertTrue(
                            "simpleFunction should be a function",
                            simpleFn instanceof InterpretedFunction);
                    assertTrue(
                            "mediumFunction should be a function",
                            mediumFn instanceof InterpretedFunction);
                    assertTrue(
                            "complexFunction should be a function",
                            complexFn instanceof InterpretedFunction);

                    InterpretedFunction simpleIfun = (InterpretedFunction) simpleFn;
                    InterpretedFunction mediumIfun = (InterpretedFunction) mediumFn;
                    InterpretedFunction complexIfun = (InterpretedFunction) complexFn;

                    int simpleIcodeSize = simpleIfun.idata.itsICode.length;
                    int mediumIcodeSize = mediumIfun.idata.itsICode.length;
                    int complexIcodeSize = complexIfun.idata.itsICode.length;

                    System.out.println("Simple function icode size: " + simpleIcodeSize);
                    System.out.println("Medium function icode size: " + mediumIcodeSize);
                    System.out.println("Complex function icode size: " + complexIcodeSize);

                    // This test is just for debugging - we'll adjust our threshold based on these
                    // results
                });
    }

    @Test
    public void testIcodeSizeThreshold() throws Exception {
        // Create a context factory with very high icode size threshold to test that small functions
        // don't compile
        ContextFactory factoryWithHighIcodeThreshold =
                new ContextFactory() {
                    @Override
                    protected boolean hasFeature(Context cx, int featureIndex) {
                        if (featureIndex == Context.FEATURE_FUNCTION_COMPILATION) {
                            return true;
                        }
                        return super.hasFeature(cx, featureIndex);
                    }

                    @Override
                    protected void onContextCreated(Context cx) {
                        cx.setInterpretedMode(true);
                        cx.setLanguageVersion(Context.VERSION_ES6);
                        cx.setFunctionCompilationThreshold(1); // Very low invocation threshold
                        cx.setFunctionCompilationIcodeSizeThreshold(
                                10000); // Very high icode size threshold
                        super.onContextCreated(cx);
                    }
                };

        Context cx = factoryWithHighIcodeThreshold.enterContext();
        try {
            ScriptableObject scope = cx.initStandardObjects();

            // Track compilation attempts
            final boolean[] compiled = {false};
            cx.setFunctionCompiler(
                    new Context.FunctionCompiler() {
                        @Override
                        public Callable compile(
                                InterpretedFunction ifun,
                                Context cx,
                                Scriptable scope,
                                Scriptable thisObj,
                                Object[] args) {
                            compiled[0] = true;
                            return null; // Return null to continue with interpretation
                        }
                    });

            // Define a very simple function with small icode
            String script =
                    "function smallFunction(x) {\n"
                            + "  return x + 1;\n"
                            + // Very simple function - should have small icode
                            "}\n"
                            + "\n"
                            + "// Call many times to exceed invocation threshold\n"
                            + "var result = 0;\n"
                            + "for (var i = 0; i < 10; i++) {\n"
                            + "  result += smallFunction(i);\n"
                            + "}\n"
                            + "result;";

            // Execute the script
            Object result = cx.evaluateString(scope, script, "icode-size-test", 1, null);

            // Get the function object to check its state
            Object testFn = ScriptableObject.getProperty(scope, "smallFunction");
            assertTrue("smallFunction should be a function", testFn instanceof InterpretedFunction);
            InterpretedFunction ifun = (InterpretedFunction) testFn;

            // The function should have been called many times but not compiled due to small icode
            // size
            assertFalse(
                    "Function should not be compiled due to insufficient icode size", compiled[0]);
            assertTrue(
                    "Function should have been called more than threshold",
                    ifun.getInvocationCount() > 1);
        } finally {
            Context.exit();
        }
    }

    @Test
    public void testBothThresholdsMustBeMet() throws Exception {
        // Test that both invocation count AND icode size thresholds must be met for compilation
        ContextFactory factoryWithBothThresholds =
                new ContextFactory() {
                    @Override
                    protected boolean hasFeature(Context cx, int featureIndex) {
                        if (featureIndex == Context.FEATURE_FUNCTION_COMPILATION) {
                            return true;
                        }
                        return super.hasFeature(cx, featureIndex);
                    }

                    @Override
                    protected void onContextCreated(Context cx) {
                        cx.setInterpretedMode(true);
                        cx.setLanguageVersion(Context.VERSION_ES6);
                        cx.setFunctionCompilationThreshold(10); // Higher invocation threshold
                        cx.setFunctionCompilationIcodeSizeThreshold(
                                50); // Reasonable icode size threshold
                        super.onContextCreated(cx);
                    }
                };

        Context cx = factoryWithBothThresholds.enterContext();
        try {
            ScriptableObject scope = cx.initStandardObjects();

            // Track compilation attempts
            final boolean[] compiled = {false};
            cx.setFunctionCompiler(
                    new Context.FunctionCompiler() {
                        @Override
                        public Callable compile(
                                InterpretedFunction ifun,
                                Context cx,
                                Scriptable scope,
                                Scriptable thisObj,
                                Object[] args) {
                            compiled[0] = true;
                            return null; // Return null to continue with interpretation
                        }
                    });

            // Define a complex function with large icode but call it only a few times
            String script =
                    "function complexFunction(x, y, z) {\n"
                            + "  var a = x + y;\n"
                            + "  var b = y + z;\n"
                            + "  var c = z + x;\n"
                            + "  if (a > b && b > c) {\n"
                            + "    return a * b * c;\n"
                            + "  } else if (b > c && c > a) {\n"
                            + "    return b * c * a;\n"
                            + "  } else {\n"
                            + "    return c * a * b;\n"
                            + "  }\n"
                            + "}\n"
                            + "\n"
                            + "// Call only a few times (less than invocation threshold)\n"
                            + "var result = 0;\n"
                            + "for (var i = 0; i < 5; i++) {\n"
                            + "  result += complexFunction(i, i+1, i+2);\n"
                            + "}\n"
                            + "result;";

            // Execute the script
            Object result = cx.evaluateString(scope, script, "both-thresholds-test", 1, null);

            // Get the function object to check its state
            Object testFn = ScriptableObject.getProperty(scope, "complexFunction");
            assertTrue(
                    "complexFunction should be a function", testFn instanceof InterpretedFunction);
            InterpretedFunction ifun = (InterpretedFunction) testFn;

            // The function should not be compiled because invocation count is too low
            assertFalse(
                    "Function should not be compiled due to insufficient invocation count",
                    compiled[0]);
            assertTrue(
                    "Function should have been called less than threshold",
                    ifun.getInvocationCount() < 10);
        } finally {
            Context.exit();
        }
    }

    @Test
    public void testCompilationFailureFallsBackToInterpretation() throws Exception {
        withContext(
                (cx, scope) -> {
                    // Track invocations and compilation attempts
                    final int[] compileAttempts = {0};

                    String script =
                            "function test() { return 'original'; }\n"
                                    + "// Store function reference\n"
                                    + "var f = test;\n"
                                    + "// Call the function more than the threshold\n"
                                    + "var result = '';\n"
                                    + "for (var i = 0; i < 10; i++) {\n"
                                    + "  result += f();\n"
                                    + "}\n"
                                    + "result;";

                    cx.setFunctionCompiler(
                            new Context.FunctionCompiler() {
                                @Override
                                public Callable compile(
                                        InterpretedFunction ifun,
                                        Context cx,
                                        Scriptable scope,
                                        Scriptable thisObj,
                                        Object[] args) {
                                    compileAttempts[0]++;
                                    // Always fail compilation
                                    return null;
                                }
                            });

                    // Execute the script
                    Object result = cx.evaluateString(scope, script, "test", 1, null);

                    // Get the function object to check its state
                    Object testFn = ScriptableObject.getProperty(scope, "test");
                    assertTrue("test should be a function", testFn instanceof InterpretedFunction);
                    InterpretedFunction ifun = (InterpretedFunction) testFn;

                    // Verify results
                    assertEquals(
                            "Should have 10 'original' strings", "original".repeat(10), result);
                    assertEquals(
                            "Should have attempted compilation six times", 6, compileAttempts[0]);
                    assertEquals(
                            "Function should have been called 10 times",
                            10,
                            ifun.getInvocationCount());
                    assertFalse(
                            "Function should not be marked as compiled after failed compilation",
                            ifun.isCompiled());
                });
    }
}
