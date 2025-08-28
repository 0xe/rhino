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

            CompilerEnvirons env = new CompilerEnvirons();
            env.initFromContext(cx);
            env.setStrictMode(idata.isStrict);
            //            env.setInterpretedMode(false);
            //            env.setOptimizationLevel(9); // TODO

            ClassCompiler compiler = new ClassCompiler(env);
            String className = "CompiledFunction" + counter.getAndIncrement();
            String fullClassName = "org.mozilla.javascript.compiled." + className;

            // For named function expressions, we need to bind the function name in its own scope
            String sourceToCompile = ifun.getRawSource();
            String functionName = ifun.getFunctionName();
            if (functionName != null && !functionName.isEmpty()) {
                // Wrap the function source to properly bind the function name in its scope
                sourceToCompile = createNamedFunctionWrapper(sourceToCompile, functionName);
            }

            Object[] results =
                    compiler.compileToClassFiles(
                            sourceToCompile,
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

            // Set the function name property (but not scope binding, which is handled during
            // compilation)
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

    /**
     * Creates a wrapper that properly binds the function name in its own scope. For a named
     * function expression like "function g() { return g; }", this creates a wrapper that ensures
     * 'g' refers to the function itself.
     */
    private String createNamedFunctionWrapper(String originalSource, String functionName) {
        // For named function expressions, we need to create a scope where the function name
        // is bound to the function itself. We do this by creating an IIFE that assigns
        // the function to a variable with the function's name.

        // Example transformation:
        // Original: "function g() { return g.toString() }"
        // Wrapped:  "(function() { var g = function g() { return g.toString() }; return g; })()"

        return "(function() { var "
                + functionName
                + " = "
                + originalSource
                + "; return "
                + functionName
                + "; })()";
    }
}
