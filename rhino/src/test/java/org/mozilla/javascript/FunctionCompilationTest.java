/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
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
            cx.setOptimizationLevel(-1); // Disable optimizations for testing
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setFunctionCompilationThreshold(5); // Set a low threshold for testing
            super.onContextCreated(cx);
        }
    }
    
    private static final TestContextFactory factory = new TestContextFactory();
    
    @Before
    public void setUp() {
        // Ensure the factory is set before any tests run
        if (ContextFactory.getGlobal() != factory) {
            ContextFactory.initGlobal(factory);
        }
    }
    
    private Context createContext() {
        return factory.enterContext();
    }
    
    private void withContext(TestWithContext test) throws Exception {
        Context cx = createContext();
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
        withContext((cx, scope) -> {
            // Define a function that will be invoked multiple times
            String script = "function test() { return 'test'; }\n" +
                          "var f = test;\n" +  // Store function reference
                          "f(); f(); f(); f(); f();";
            
            // Create a mock function compiler to track compilation
            final boolean[] compiled = {false};
            cx.setFunctionCompiler(new Context.FunctionCompiler() {
                @Override
                public Callable compile(InterpretedFunction ifun) {
                    compiled[0] = true;
                    assertEquals("Function should have been called 5 times before compilation", 
                        5, ifun.getInvocationCount());
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
//            assertTrue("Function should be marked as compiled", ((InterpretedFunction)testFn).isCompiled());
        });
    }
    
    @Test
    public void testCompilationThreshold() throws Exception {
        withContext((cx, scope) -> {
            // Track invocations and compilation
            final boolean[] wasCompiled = {false};
            
            // Define a function that will be invoked multiple times
            String script = "function test() { return 'test'; }\n" +
                          "var f = test;\n" +  // Store function reference
                          "f(); f(); f(); f(); f();";
            
            cx.setFunctionCompiler(new Context.FunctionCompiler() {
                @Override
                public Callable compile(InterpretedFunction ifun) {
                    wasCompiled[0] = true;
                    // Return a simple callable that returns a fixed value
                    return new BaseFunction() {
                        @Override
                        public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                            return "compiled";
                        }
                    };
                }
            });
            
            // Execute the script
            Object result = cx.evaluateString(scope, script, "test", 1, null);
            
            // The function should have been compiled and the compiled version should be used
            assertTrue("Function should have been compiled", wasCompiled[0]);
            
            // The last call should return the compiled result
            assertEquals("Compiled function should return 'compiled'", "compiled", result);
            
            // Verify the function is marked as compiled
            Object testFn = ScriptableObject.getProperty(scope, "test");
            assertTrue("test should be a function", testFn instanceof InterpretedFunction);
//            assertTrue("Function should be marked as compiled", ((InterpretedFunction)testFn).isCompiled());
        });
    }
    
    @Test
    public void testInvocationCountIncrements() throws Exception {
        withContext((cx, scope) -> {
            // Define a function and call it multiple times
            String script = "function test() { return 'test'; }\n" +
                          "var f = test;\n" +  // Store function reference
                          "f(); f(); f(); f(); f();";
            
            // Execute the script
            cx.evaluateString(scope, script, "test", 1, null);
            
            // Get the function object after execution
            Object testFn = ScriptableObject.getProperty(scope, "test");
            assertTrue("test should be a function", testFn instanceof InterpretedFunction);
            
            // Verify the invocation count
            InterpretedFunction ifun = (InterpretedFunction) testFn;
            assertEquals("Function should have been called 5 times", 
                        5, ifun.getInvocationCount());
//            assertTrue("Function should be marked for compilation", ifun.isCompiled());
        });
    }
    
    @Test
    public void testMultipleFunctions() throws Exception {
        withContext((cx, scope) -> {
            // Track which functions were compiled
            final boolean[] func1Compiled = {false};
            final boolean[] func2Compiled = {false};
            
            String script = "function func1() { return 'func1'; }\n" +
                          "function func2() { return 'func2'; }\n" +
                          "// Store function references\n" +
                          "var f1 = func1;\n" +
                          "var f2 = func2;\n" +
                          "// Call func1 5 times\n" +
                          "for (var i = 0; i < 5; i++) { f1(); }\n" +
                          "// Call func2 3 times\n" +
                          "for (var i = 0; i < 3; i++) { f2(); }\n" +
                          "// Return a value to verify execution\n" +
                          "'done';";
            
            cx.setFunctionCompiler(new Context.FunctionCompiler() {
                @Override
                public Callable compile(InterpretedFunction ifun) {
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
            assertEquals("func1 should have been called 5 times", 
                        5, ifun1.getInvocationCount());
//            assertTrue("func1 should be marked as compiled", ifun1.isCompiled());
            
            assertFalse("func2 should not be marked for compilation yet", func2Compiled[0]);
            assertEquals("func2 should have been called 3 times", 3, ifun2.getInvocationCount());
//            assertFalse("func2 should not be marked as compiled", ifun2.isCompiled());
        });
    }
    
    @Test
    public void testCompilationFailureFallsBackToInterpretation() throws Exception {
        withContext((cx, scope) -> {
            // Track invocations and compilation attempts
            final int[] compileAttempts = {0};
            
            String script = "function test() { return 'original'; }\n" +
                          "// Store function reference\n" +
                          "var f = test;\n" +
                          "// Call the function more than the threshold\n" +
                          "var result = '';\n" +
                          "for (var i = 0; i < 10; i++) {\n" +
                          "  result += f();\n" +
                          "}\n" +
                          "result;";
            
            cx.setFunctionCompiler(new Context.FunctionCompiler() {
                @Override
                public Callable compile(InterpretedFunction ifun) {
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
            assertEquals("Should have 10 'original' strings", 
                        "original".repeat(10), result);
            assertEquals("Should have attempted compilation six times", 6, compileAttempts[0]);
            assertEquals("Function should have been called 10 times", 10, ifun.getInvocationCount());
            assertFalse("Function should not be marked as compiled after failed compilation", 
                       ifun.isCompiled());
        });
    }
}
