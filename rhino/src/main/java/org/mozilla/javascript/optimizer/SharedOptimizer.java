/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.optimizer;

import java.util.ArrayList;
import java.util.List;
import org.mozilla.javascript.Node;
import org.mozilla.javascript.Token;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.ScriptNode;

/**
 * Entry point for shared optimization passes that run on both the interpreter and classfile
 * compiler paths.
 *
 * <p>This pass runs after {@link org.mozilla.javascript.NodeTransformer} has lowered the tree
 * (NAME→GETVAR, BREAK/CONTINUE→GOTO, etc.) so it sees the lowered IR. In the Codegen path, the
 * existing {@link Optimizer} still runs after this, applying its number-type specialization on top
 * of our results.
 */
public class SharedOptimizer {

    /**
     * Runs all shared optimization passes on the given script tree.
     *
     * <p>Iterates over each function in the tree, flattens it to a statement list, builds a CFG,
     * and runs optimization passes. Currently this only builds the CFG (a no-op in terms of tree
     * modification). Future lessons will add actual optimization passes (dead code elimination,
     * constant propagation, etc.).
     */
    public void optimize(ScriptNode scriptOrFn) {
        int functionCount = scriptOrFn.getFunctionCount();
        for (int i = 0; i < functionCount; i++) {
            FunctionNode fn = scriptOrFn.getFunctionNode(i);
            if (fn.requiresActivation()) {
                continue;
            }
            optimizeFunction(fn);
        }
    }

    private void optimizeFunction(FunctionNode fn) {
        Node[] statementNodes = flattenStatements(fn);
        if (statementNodes.length == 0) {
            return;
        }

        BasicBlock.buildCFG(statementNodes);
    }

    public static Node[] flattenStatements(Node node) {
        ArrayList<Node> statements = new ArrayList<>();
        flattenStatements_r(node, statements);
        return statements.toArray(new Node[0]);
    }

    private static void flattenStatements_r(Node node, List<Node> statements) {
        int type = node.getType();
        if (type == Token.BLOCK
                || type == Token.LOCAL_BLOCK
                || type == Token.LOOP
                || type == Token.FUNCTION) {
            Node child = node.getFirstChild();
            while (child != null) {
                flattenStatements_r(child, statements);
                child = child.getNext();
            }
        } else {
            statements.add(node);
        }
    }
}
