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

class DeadCodeEliminationTest {

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
        new DeadCodeElimination().run(blocks);
        return blocks;
    }

    /** Counts SETVAR nodes nested inside EXPR_VOID statements. */
    private static int countSetVar(List<BasicBlock> blocks) {
        int count = 0;
        for (BasicBlock block : blocks) {
            for (Node stmt : block.getStatements()) {
                if (stmt.getType() == Token.EXPR_VOID || stmt.getType() == Token.EXPR_RESULT) {
                    Node child = stmt.getFirstChild();
                    if (child != null
                            && (child.getType() == Token.SETVAR
                                    || child.getType() == Token.SETCONSTVAR)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** Counts EXPR_VOID statements that do NOT contain SETVAR. */
    private static int countPureExprVoid(List<BasicBlock> blocks) {
        int count = 0;
        for (BasicBlock block : blocks) {
            for (Node stmt : block.getStatements()) {
                if (stmt.getType() == Token.EXPR_VOID) {
                    Node child = stmt.getFirstChild();
                    if (child != null
                            && child.getType() != Token.SETVAR
                            && child.getType() != Token.SETCONSTVAR) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    @Test
    void pureExprVoidRemoved() {
        // "x;" inside a function — EXPR_VOID wrapping GETVAR, which is pure
        String source = "function f(x) { x; return 0; }";
        List<BasicBlock> blocks = buildAndOptimize(source);
        assertEquals(0, countPureExprVoid(blocks));
    }

    @Test
    void sideEffectfulExprVoidPreserved() {
        // "foo();" has a CALL — not pure, must be preserved
        String source = "function f() { foo(); return 0; }";
        List<BasicBlock> blocks = buildAndOptimize(source);
        assertEquals(1, countPureExprVoid(blocks));
    }

    @Test
    void deadSetvarOverwrittenBeforeRead() {
        // var a = 1; a = 2; return a; — first SETVAR is dead
        String source = "function f() { var a = 1; a = 2; return a; }";
        List<BasicBlock> blocks = buildAndOptimize(source);
        assertEquals(1, countSetVar(blocks));
    }

    @Test
    void liveSetvarPreserved() {
        // var a = 1; return a; — SETVAR is live (used by the return)
        String source = "function f() { var a = 1; return a; }";
        List<BasicBlock> blocks = buildAndOptimize(source);
        assertEquals(1, countSetVar(blocks));
    }

    @Test
    void sideEffectfulRhsPreservesSetvar() {
        // var a = foo(); a = 2; — first SETVAR has CALL RHS, not pure, must stay
        String source = "function f() { var a = foo(); a = 2; return a; }";
        List<BasicBlock> blocks = buildAndOptimize(source);
        assertEquals(2, countSetVar(blocks));
    }

    @Test
    void terminalBlockDeadVar() {
        // var a = 1 is dead because a is never read before the return
        String source = "function f() { var a = 1; var b = 2; return b; }";
        List<BasicBlock> blocks = buildAndOptimize(source);
        assertEquals(1, countSetVar(blocks));
    }

    @Test
    void nonTerminalBlockConservative() {
        // In a block with successors, assume all vars are live-out
        String source = "function f(x) { var a = 1; if (x) { return a; } return 0; }";
        List<BasicBlock> blocks = buildAndOptimize(source);
        assertTrue(countSetVar(blocks) >= 1);
    }

    @Test
    void cascadingDeadCode() {
        // a = 1, b = a, c = b, c = 3, return c
        // c = b dead (killed by c = 3), b = a dead (b never read), a = 1 dead (a never read)
        String source = "function f() { var a = 1; var b = a; var c = b; c = 3; return c; }";
        List<BasicBlock> blocks = buildAndOptimize(source);
        assertEquals(1, countSetVar(blocks));
    }
}
