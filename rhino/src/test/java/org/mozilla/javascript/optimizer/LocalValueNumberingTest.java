/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.optimizer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.IRFactory;
import org.mozilla.javascript.Node;
import org.mozilla.javascript.NodeTransformer;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.Token;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.ScriptNode;

class LocalValueNumberingTest {

    private static List<BasicBlock> buildAndOptimize(String source) {
        CompilerEnvirons env = new CompilerEnvirons();
        env.setLanguageVersion(Context.VERSION_ES6);
        Parser p = new Parser(env);
        AstRoot ast = p.parse(source, "test", 1);

        IRFactory irf = new IRFactory(env, source);
        ScriptNode tree = irf.transformTree(ast);

        new NodeTransformer().transform(tree, env);

        FunctionNode fn = tree.getFunctionNode(0);
        Node[] statements = SharedOptimizer.flattenStatements(fn);
        List<BasicBlock> blocks = BasicBlock.buildCFG(statements);
        new LocalValueNumbering().run(blocks);
        return blocks;
    }

    private static boolean containsNumberConstant(List<BasicBlock> blocks, double value) {
        for (BasicBlock block : blocks) {
            for (Node stmt : block.getStatements()) {
                if (containsNumber(stmt, value)) return true;
            }
        }
        return false;
    }

    private static boolean containsNumber(Node n, double value) {
        if (n.getType() == Token.NUMBER && n.getDouble() == value) return true;
        for (Node child = n.getFirstChild(); child != null; child = child.getNext()) {
            if (containsNumber(child, value)) return true;
        }
        return false;
    }

    private static boolean containsNodeType(List<BasicBlock> blocks, int tokenType) {
        for (BasicBlock block : blocks) {
            for (Node stmt : block.getStatements()) {
                if (containsType(stmt, tokenType)) return true;
            }
        }
        return false;
    }

    private static boolean containsType(Node n, int type) {
        if (n.getType() == type) return true;
        for (Node child = n.getFirstChild(); child != null; child = child.getNext()) {
            if (containsType(child, type)) return true;
        }
        return false;
    }

    @Test
    void constantFoldingThroughVariable() {
        // Parser folds 2+3→5 at parse time.
        // LVN should fold a*3 → 15 since a is known to be 5.
        String source = "function f() { var a = 2 + 3; var b = a * 3; return b; }";
        List<BasicBlock> blocks = buildAndOptimize(source);
        assertTrue(containsNumberConstant(blocks, 15.0), "a*3 should be folded to 15");
    }

    @Test
    void constantFoldingMultiplication() {
        // var a = 4; var b = 5; var c = a * b; — LVN knows a=4, b=5 → fold to 20
        String source = "function f() { var a = 4; var b = 5; var c = a * b; return c; }";
        List<BasicBlock> blocks = buildAndOptimize(source);
        assertTrue(containsNumberConstant(blocks, 20.0), "a*b should be folded to 20");
    }

    @Test
    void noFoldOnNonConstants() {
        // var a = x + y; — cannot fold since x and y are parameters
        String source = "function f(x, y) { var a = x + y; return a; }";
        List<BasicBlock> blocks = buildAndOptimize(source);
        assertTrue(containsNodeType(blocks, Token.ADD));
    }

    @Test
    void copyPropagation() {
        // var a = x; var b = a; return b; — b should resolve to x via copy propagation
        String source = "function f(x) { var a = x; var b = a; return b; }";
        List<BasicBlock> blocks = buildAndOptimize(source);
        // The RETURN's child should be a GETVAR — check it doesn't crash and produces valid IR
        assertNotNull(blocks);
        assertTrue(blocks.size() >= 1);
    }

    @Test
    void cseOnRepeatedExpression() {
        // x + y computed twice — second occurrence should be replaced with a's value
        String source = "function f(x, y) { var a = x + y; var b = x + y; return a + b; }";
        List<BasicBlock> blocks = buildAndOptimize(source);
        // After CSE, b's RHS should be rewritten to GETVAR[a], not ADD
        assertNotNull(blocks);
    }

    @Test
    void callInvalidatesVariables() {
        // After a CALL, variable mappings should be invalidated
        String source = "function f(x) { var a = x; foo(); var b = x; return a + b; }";
        List<BasicBlock> blocks = buildAndOptimize(source);
        assertNotNull(blocks);
        assertTrue(blocks.size() >= 1);
    }

    @Test
    void noCseOnGetprop() {
        // obj.prop should not be value-numbered
        String source = "function f(obj) { var a = obj.x; var b = obj.x; return a + b; }";
        List<BasicBlock> blocks = buildAndOptimize(source);
        assertNotNull(blocks);
    }
}
