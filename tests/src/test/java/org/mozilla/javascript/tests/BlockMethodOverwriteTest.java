package org.mozilla.javascript.tests;

import org.junit.Test;
import org.mozilla.javascript.testutils.Utils;

public class BlockMethodOverwriteTest {
    @Test
    public void redefinition() {
        String script =
                "var ret = [];\n" +
                        "{\n" +
                        "    function foo () { return 1 }\n" +
                        "    ret.push(foo() === 1);\n" +
                        "    {\n" +
                        "        function foo () { return 2 }\n" +
                        "        ret.push(foo() === 2);\n" +
                        "    }\n" +
                        "    ret.push(foo() === 1);\n" +
                        "};" +
                        "ret.join(',')";
        Utils.assertWithAllModes("true,true,true", script);
    }

    @Test
    public void multipleNestingLevels() {
        String script =
                "var ret = [];\n" +
                        "function outer() { return 'outer' }\n" +
                        "{\n" +
                        "    function foo () { return 1 }\n" +
                        "    ret.push(foo() === 1);\n" +
                        "    ret.push(outer() === 'outer');\n" +
                        "    {\n" +
                        "        function foo () { return 2 }\n" +
                        "        ret.push(foo() === 2);\n" +
                        "        {\n" +
                        "            function foo () { return 3 }\n" +
                        "            ret.push(foo() === 3);\n" +
                        "        }\n" +
                        "        ret.push(foo() === 2);\n" +
                        "    }\n" +
                        "    ret.push(foo() === 1);\n" +
                        "};" +
                        "ret.join(',')";
        Utils.assertWithAllModes("true,true,true,true,true,true", script);
    }

    @Test
    public void blockScopeWithIfStatement() {
        String script =
                "var ret = [];\n" +
                        "function test() { return 'global' }\n" +
                        "if (true) {\n" +
                        "    function test() { return 'if' }\n" +
                        "    ret.push(test() === 'if');\n" +
                        "}\n" +
                        "ret.push(test() === 'global');\n" +
                        "ret.join(',')";
        Utils.assertWithAllModes("true,true", script);
    }

    @Test
    public void blockScopeWithForLoop() {
        String script =
                "var ret = [];\n" +
                        "function helper() { return 'outer' }\n" +
                        "for (var i = 0; i < 1; i++) {\n" +
                        "    function helper() { return 'loop' }\n" +
                        "    ret.push(helper() === 'loop');\n" +
                        "}\n" +
                        "ret.push(helper() === 'outer');\n" +
                        "ret.join(',')";
        Utils.assertWithAllModes("true,true", script);
    }

    @Test
    public void sameFunctionNameDifferentBlocks() {
        String script =
                "var ret = [];\n" +
                        "{\n" +
                        "    function test() { return 'block1' }\n" +
                        "    ret.push(test() === 'block1');\n" +
                        "}\n" +
                        "{\n" +
                        "    function test() { return 'block2' }\n" +
                        "    ret.push(test() === 'block2');\n" +
                        "}\n" +
                        "ret.join(',')";
        Utils.assertWithAllModes("true,true", script);
    }

    @Test
    public void functionAccessibilityOutsideBlock() {
        String script =
                "var accessible = false;\n" +
                        "{\n" +
                        "    function blockFunc() { return 'blocked' }\n" +
                        "}\n" +
                        "try {\n" +
                        "    blockFunc();\n" +
                        "} catch (e) {\n" +
                        "    accessible = (e instanceof ReferenceError);\n" +
                        "}\n" +
                        "accessible";
        Utils.assertWithAllModes("true", script);
    }
}
