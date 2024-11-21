package org.mozilla.javascript;

import org.junit.Assert;
import org.junit.Test;
import org.mozilla.javascript.tests.Utils;

public class SymbolHasInstanceTest {
    @Test
    public void testSymbolHasInstanceIsPresent() {
        String script =
                ""
                        + "var f = {\n"
                        + "   [Symbol.hasInstance](value) { "
                        + "   }"
                        + "};\n"
                        + "var g = {};\n"
                        + "`${f.hasOwnProperty(Symbol.hasInstance)}:${g.hasOwnProperty(Symbol.hasInstance)}`";
        Utils.runWithAllOptimizationLevels(
                (cx) -> {
                    cx.setLanguageVersion(Context.VERSION_ES6);
                    final Scriptable scope = cx.initStandardObjects();
                    String result =
                            (String)
                                    cx.evaluateString(
                                            scope, script, "testSymbolHasInstance", 0, null);
                    Assert.assertEquals("true:false", result);
                    return null;
                });
    }

    @Test
    public void testSymbolHasInstanceCanBeCalledLikeAnotherMethod() {
        String script =
                ""
                        + "var f = {\n"
                        + "   [Symbol.hasInstance](value) { "
                        + "       return 42;"
                        + "   }"
                        + "};\n"
                        + "f[Symbol.hasInstance]() == 42";
        Utils.runWithAllOptimizationLevels(
                (cx) -> {
                    cx.setLanguageVersion(Context.VERSION_ES6);
                    final Scriptable scope = cx.initStandardObjects();
                    Object result =
                            cx.evaluateString(scope, script, "testSymbolHasInstance", 0, null);
                    Assert.assertEquals(true, result);
                    return null;
                });
    }

    // See: https://tc39.es/ecma262/#sec-function.prototype-%symbol.hasinstance%
    @Test
    public void testFunctionPrototypeSymbolHasInstanceHasAttributes() {
        String script =
                "var a = Object.getOwnPropertyDescriptor(Function.prototype, Symbol.hasInstance);\n"
                        + "a.writable + ':' + a.configurable + ':' + a.enumerable";
        Utils.runWithAllOptimizationLevels(
                (cx) -> {
                    cx.setLanguageVersion(Context.VERSION_ES6);
                    final Scriptable scope = cx.initStandardObjects();
                    Object result =
                            cx.evaluateString(scope, script, "testSymbolHasInstance", 0, null);
                    Assert.assertEquals("false:false:false", result);
                    return null;
                });
    }

    @Test
    public void testFunctionPrototypeSymbolHasInstance() {
        String script =
                "(Function.prototype[Symbol.hasInstance] instanceof Function) + ':' + "
                        + "Function.prototype[Symbol.hasInstance].call(Function, Object)\n";
        Utils.runWithAllOptimizationLevels(
                (cx) -> {
                    cx.setLanguageVersion(Context.VERSION_ES6);
                    final Scriptable scope = cx.initStandardObjects();
                    Object result =
                            cx.evaluateString(scope, script, "testSymbolHasInstance", 0, null);
                    Assert.assertEquals("true:true", result);
                    return null;
                });
    }

    @Test
    public void testSymbolHasInstanceIsInvokedInInstanceOf() {
        String script =
                ""
                        + "var globalSet = 0;"
                        + "var f = {\n"
                        + "   [Symbol.hasInstance](value) { "
                        + "       globalSet = 1;"
                        + "       return true;"
                        + "   }"
                        + "}\n"
                        + "var g = {}\n"
                        + "Object.setPrototypeOf(g, f);\n"
                        + "g instanceof f;"
                        + "globalSet == 1";
        Utils.runWithAllOptimizationLevels(
                (cx) -> {
                    cx.setLanguageVersion(Context.VERSION_ES6);
                    final Scriptable scope = cx.initStandardObjects();
                    Object result =
                            cx.evaluateString(scope, script, "testSymbolHasInstance", 0, null);
                    Assert.assertEquals(true, result);
                    return null;
                });
    }

    @Test
    public void testThrowTypeErrorOnNonObjectIncludingSymbol() {
        String script =
                ""
                        + "var f = function() {}; \n"
                        + "f.prototype = Symbol(); \n"
                        + "f[Symbol.hasInstance]({})";

        Utils.runWithAllOptimizationLevels(
                (cx) -> {
                    cx.setLanguageVersion(Context.VERSION_ES6);
                    final Scriptable scope = cx.initStandardObjects();
                    var error =
                            Assert.assertThrows(
                                    EcmaError.class,
                                    () ->
                                            cx.evaluateString(
                                                    scope,
                                                    script,
                                                    "testSymbolHasInstance",
                                                    0,
                                                    null));
                    Assert.assertTrue(
                            error.toString()
                                    .contains("'prototype' property of  is not an object."));
                    return null;
                });
    }
}
