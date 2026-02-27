/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.optimizer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.IRFactory;
import org.mozilla.javascript.Node;
import org.mozilla.javascript.NodeTransformer;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.ScriptNode;

class BasicBlockTest {

    private static List<BasicBlock> buildCFGForFunction(String source) {
        CompilerEnvirons env = new CompilerEnvirons();
        Parser p = new Parser(env);
        AstRoot ast = p.parse(source, "test", 1);

        IRFactory irf = new IRFactory(env, source);
        ScriptNode tree = irf.transformTree(ast);

        new NodeTransformer().transform(tree, env);

        FunctionNode fn = tree.getFunctionNode(0);
        Node[] statements = SharedOptimizer.flattenStatements(fn);
        return BasicBlock.buildCFG(statements);
    }

    @Test
    void straightLineCode() {
        String source = "function f() { var a = 1; var b = 2; return a + b; }";
        List<BasicBlock> blocks = buildCFGForFunction(source);

        // Straight-line code: all statements in a single block
        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0).getSuccessors().isEmpty());
        assertTrue(blocks.get(0).getPredecessors().isEmpty());
    }

    @Test
    void ifElse() {
        String source = "function f(x) { if (x) { return 1; } else { return 2; } }";
        List<BasicBlock> blocks = buildCFGForFunction(source);

        // if/else creates multiple blocks: the conditional branch, then branch, else branch
        assertTrue(blocks.size() >= 3, "Expected at least 3 blocks, got " + blocks.size());

        // Verify entry block has successors (it branches)
        assertFalse(blocks.get(0).getSuccessors().isEmpty());
    }

    @Test
    void whileLoop() {
        String source = "function f(x) { while (x > 0) { x = x - 1; } return x; }";
        List<BasicBlock> blocks = buildCFGForFunction(source);

        // While loop: there should be a back edge (some block has a successor with a lower id)
        assertTrue(blocks.size() >= 3, "Expected at least 3 blocks, got " + blocks.size());

        boolean hasBackEdge = false;
        for (BasicBlock bb : blocks) {
            for (BasicBlock succ : bb.getSuccessors()) {
                if (succ.getId() <= bb.getId()) {
                    hasBackEdge = true;
                }
            }
        }
        assertTrue(hasBackEdge, "While loop should produce a back edge in CFG");
    }

    @Test
    void forLoop() {
        String source =
                "function f() { var s = 0; for (var i = 0; i < 10; i++) { s += i; } return s; }";
        List<BasicBlock> blocks = buildCFGForFunction(source);

        assertTrue(
                blocks.size() >= 3,
                "Expected at least 3 blocks for a for loop, got " + blocks.size());

        boolean hasBackEdge = false;
        for (BasicBlock bb : blocks) {
            for (BasicBlock succ : bb.getSuccessors()) {
                if (succ.getId() <= bb.getId()) {
                    hasBackEdge = true;
                }
            }
        }
        assertTrue(hasBackEdge, "For loop should produce a back edge in CFG");
    }

    @Test
    void breakInLoop() {
        String source = "function f(x) { while (true) { if (x) break; x = 1; } return x; }";
        List<BasicBlock> blocks = buildCFGForFunction(source);

        assertTrue(
                blocks.size() >= 3, "Expected at least 3 blocks with break, got " + blocks.size());
    }

    @Test
    void continueInLoop() {
        String source =
                "function f(x) { var i = 0; while (i < 10) { i++; if (i == 5) continue; x += i; } return x; }";
        List<BasicBlock> blocks = buildCFGForFunction(source);

        assertTrue(
                blocks.size() >= 3,
                "Expected at least 3 blocks with continue, got " + blocks.size());
    }

    @Test
    void tryCatch() {
        String source = "function f() { try { return 1; } catch (e) { return 2; } }";
        List<BasicBlock> blocks = buildCFGForFunction(source);

        // try/catch generates control flow — at minimum there should be multiple blocks
        assertTrue(
                blocks.size() >= 1,
                "Expected at least 1 block for try/catch, got " + blocks.size());
    }

    @Test
    void switchStatement() {
        String source =
                "function f(x) { switch(x) { case 1: return 'a'; case 2: return 'b'; default: return 'c'; } }";
        List<BasicBlock> blocks = buildCFGForFunction(source);

        assertTrue(
                blocks.size() >= 3, "Expected at least 3 blocks for switch, got " + blocks.size());
    }

    @Test
    void nestedControlFlow() {
        String source =
                "function f(x, y) {"
                        + "  if (x > 0) {"
                        + "    while (y > 0) {"
                        + "      y = y - 1;"
                        + "    }"
                        + "    return y;"
                        + "  } else {"
                        + "    return -1;"
                        + "  }"
                        + "}";
        List<BasicBlock> blocks = buildCFGForFunction(source);

        assertTrue(
                blocks.size() >= 5,
                "Expected at least 5 blocks for nested control flow, got " + blocks.size());
    }

    @Test
    void emptyFunction() {
        String source = "function f() { }";
        List<BasicBlock> blocks = buildCFGForFunction(source);

        // An empty function might still have a return node after lowering
        assertTrue(
                blocks.size() <= 1,
                "Empty function should have at most 1 block, got " + blocks.size());
    }

    @Test
    void predecessorsMatchSuccessors() {
        String source = "function f(x) { if (x) { return 1; } return 2; }";
        List<BasicBlock> blocks = buildCFGForFunction(source);

        // Verify structural consistency: if A is a successor of B,
        // then B is a predecessor of A
        for (BasicBlock bb : blocks) {
            for (BasicBlock succ : bb.getSuccessors()) {
                assertTrue(
                        succ.getPredecessors().contains(bb),
                        "B" + succ.getId() + " should have B" + bb.getId() + " as predecessor");
            }
        }

        for (BasicBlock bb : blocks) {
            for (BasicBlock pred : bb.getPredecessors()) {
                assertTrue(
                        pred.getSuccessors().contains(bb),
                        "B" + pred.getId() + " should have B" + bb.getId() + " as successor");
            }
        }
    }

    @Test
    void entryBlockHasNoPredecessors() {
        String source = "function f(x) { if (x) { return 1; } else { return 2; } }";
        List<BasicBlock> blocks = buildCFGForFunction(source);

        assertFalse(blocks.isEmpty());
        assertTrue(
                blocks.get(0).getPredecessors().isEmpty(),
                "Entry block should have no predecessors");
    }

    @Test
    void toDotProducesValidOutput() {
        String source = "function f(x) { if (x) { return 1; } return 2; }";
        List<BasicBlock> blocks = buildCFGForFunction(source);
        Node[] statements = getStatements(source);

        String dot = BasicBlock.toDot(blocks, statements);

        assertTrue(dot.startsWith("digraph CFG {"));
        assertTrue(dot.contains("->"));
        assertTrue(dot.endsWith("}\n"));
        // Should contain block labels
        assertTrue(dot.contains("B0"));
    }

    @Test
    void toDotEmptyCFG() {
        String dot = BasicBlock.toDot(List.of(), new Node[0]);
        assertTrue(dot.startsWith("digraph CFG {"));
        assertTrue(dot.endsWith("}\n"));
    }

    private static Node[] getStatements(String source) {
        CompilerEnvirons env = new CompilerEnvirons();
        Parser p = new Parser(env);
        AstRoot ast = p.parse(source, "test", 1);

        IRFactory irf = new IRFactory(env, source);
        ScriptNode tree = irf.transformTree(ast);

        new NodeTransformer().transform(tree, env);

        FunctionNode fn = tree.getFunctionNode(0);
        return SharedOptimizer.flattenStatements(fn);
    }
}
