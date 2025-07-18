package org.mozilla.javascript;

import java.util.concurrent.atomic.AtomicInteger;
import org.mozilla.javascript.optimizer.ClassCompiler;

public class IFnToClassCompiler implements Context.FunctionCompiler {
    static AtomicInteger counter = new AtomicInteger();

    @Override
    public Callable compile(
            InterpretedFunction ifun,
            Context cx,
            Scriptable scope,
            Scriptable thisObj,
            Object[] args) {
        try {
            InterpreterData idata = ifun.idata;
            if (ifun.getRawSource() == null) {
                return null; // No source available
            }

            if (idata.itsNeedsActivation) {
                ifun.compilationAttempted = true;
                return null;
            }

            // Check if this function uses constructions that can't be compiled in chunks
            // This should catch self-referencing named function expressions
            if (idata.usesConstructionsThatCantBeCompiledInChunk) {
                ifun.compilationAttempted = true;
                return null;
            }

            CompilerEnvirons env = new CompilerEnvirons();
            env.initFromContext(cx);
            env.setStrictMode(idata.isStrict);
            //            env.setInterpretedMode(false);
            //            env.setOptimizationLevel(9); // TODO

            String functionName = ifun.getFunctionName();
            String source = ifun.getRawSource();

            /*
            Sort of a hacky fix for

            FunctionTest > secondFunctionWithSameNameStrict
            FunctionTest > functionReferItself
            MozillaSuiteTest > [3453, js=testsrc/tests/ecma_3/Function/regress-193555.js, interpreted=true]
            language/statements/function/S14_A2.js
            language/statements/function/S13_A3_T1.js

            Have to think more.
             */
            if (source != null
                    && functionName != null
                    && !functionName.isEmpty()
                    && hasPotentialSelfReference(source, functionName)) {
                ifun.compilationAttempted = true;
                return null;
            }

            ClassCompiler compiler = new ClassCompiler(env);
            String className = "CompiledFunction" + counter.getAndIncrement();
            String fullClassName = "org.mozilla.javascript.compiled." + className;

            Object[] results =
                    compiler.compileToClassFiles(
                            source,
                            idata.itsSourceFile,
                            0, // TODO
                            fullClassName,
                            true);

            if (results == null || results.length < 2) {
                return null; // TODO: Compilation failed
            }

            ClassLoader parentLoader = cx.getApplicationClassLoader();
            ClassLoader loader =
                    new ClassLoader(parentLoader) {
                        @Override
                        protected Class<?> findClass(String name) throws ClassNotFoundException {
                            for (int i = 0; i < results.length; i += 2) {
                                String className = (String) results[i];
                                if (name.equals(className)) {
                                    byte[] classBytes = (byte[]) results[i + 1];
                                    return defineClass(name, classBytes, 0, classBytes.length);
                                }
                            }
                            return super.findClass(name);
                        }
                    };

            Class<?> clazz = loader.loadClass(fullClassName);
            var compiledFunction =
                    (NativeFunction)
                            clazz.getDeclaredConstructors()[0].newInstance(
                                    ifun.getParentScope(), cx, 1);
            compiledFunction.setPrototypeProperty(ifun.getPrototypeProperty());
            compiledFunction.setHomeObject(ifun.getHomeObject());

            // Set the function name property
            if (functionName != null && !functionName.isEmpty()) {
                compiledFunction.put("name", compiledFunction, functionName);
            }

            return compiledFunction;
        } catch (Exception e) {
            // Log the error and fall back to interpretation
            Context.reportError("Error compiling function: " + e);
            return null;
        } catch (VerifyError err) {
            Context.reportError("Error compiling function: " + err);
            return null;
        }
    }

    private boolean hasPotentialSelfReference(String source, String functionName) {
        // Check for patterns like "return g" or "typeof g" where g is the function name
        String pattern1 = "return " + functionName;
        String pattern2 = "typeof " + functionName;
        String pattern3 = functionName + ".toString()";
        String pattern4 = functionName + "."; // Any property access
        String pattern5 = "(" + functionName + ")"; // Function call argument like norm(func)

        return source.contains(pattern1)
                || source.contains(pattern2)
                || source.contains(pattern3)
                || source.contains(pattern4)
                || source.contains(pattern5);
    }
}
