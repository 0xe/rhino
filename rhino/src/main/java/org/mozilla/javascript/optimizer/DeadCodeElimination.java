/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.optimizer;

import java.util.List;
import org.mozilla.javascript.Node;
import org.mozilla.javascript.Token;

/**
 * Trivial dead code elimination pass operating per basic block.
 *
 * <p>In Rhino's lowered IR, variable assignments are represented as {@code EXPR_VOID { SETVAR {
 * STRING[name], rhs } }}. This pass removes:
 *
 * <ul>
 *   <li>{@code EXPR_VOID} wrapping a pure expression (value is discarded, no side effects)
 *   <li>{@code EXPR_VOID { SETVAR }} where the variable is killed before being read within the same
 *       block and the RHS is pure
 * </ul>
 *
 * <p>Iterates to fixpoint within each block.
 */
class DeadCodeElimination {

    void run(List<BasicBlock> blocks) {
        for (BasicBlock block : blocks) {
            eliminateDeadCode(block);
        }
    }

    private void eliminateDeadCode(BasicBlock block) {
        boolean changed = true;
        while (changed) {
            changed = false;
            int count = block.getStatementCount();
            for (int i = 0; i < count; i++) {
                Node stmt = block.getStatement(i);
                if (stmt.getType() != Token.EXPR_VOID) continue;

                Node child = stmt.getFirstChild();
                if (child == null) continue;

                if (child.getType() == Token.SETVAR || child.getType() == Token.SETCONSTVAR) {
                    // EXPR_VOID { SETVAR { STRING[name], rhs } }
                    Node nameNode = child.getFirstChild();
                    Node rhs = nameNode != null ? nameNode.getNext() : null;
                    if (rhs != null && isPure(rhs) && isDeadStore(block, i)) {
                        block.setStatement(block.getAbsoluteIndex(i), new Node(Token.EMPTY));
                        changed = true;
                    }
                } else if (isPure(child)) {
                    // EXPR_VOID wrapping a pure expression with no SETVAR
                    block.setStatement(block.getAbsoluteIndex(i), new Node(Token.EMPTY));
                    changed = true;
                }
            }
        }
    }

    /**
     * Returns true if the SETVAR inside the EXPR_VOID at position {@code storeIndex} is dead — the
     * variable is overwritten before it is read in the remaining statements of the block.
     *
     * <p>For non-terminal blocks (has successors), we conservatively assume all variables are
     * live-out, so only remove if overwritten before any read. For terminal blocks (no successors,
     * e.g. ends with RETURN), also remove if the variable is never read in remaining statements.
     */
    private boolean isDeadStore(BasicBlock block, int storeIndex) {
        Node exprVoid = block.getStatement(storeIndex);
        Node setvar = exprVoid.getFirstChild();
        String varName = setvar.getFirstChild().getString();
        boolean isTerminal = block.getSuccessors().isEmpty();
        int count = block.getStatementCount();

        for (int i = storeIndex + 1; i < count; i++) {
            Node stmt = block.getStatement(i);
            if (readsVar(stmt, varName)) {
                return false;
            }
            if (killsVar(stmt, varName)) {
                return true;
            }
        }

        // Reached end of block without a read or kill
        return isTerminal;
    }

    private boolean readsVar(Node node, String varName) {
        if (node == null) return false;
        if (node.getType() == Token.GETVAR && varName.equals(node.getString())) {
            return true;
        }
        // For SETVAR, only the RHS can read (the LHS NAME is a definition, not a use)
        if (node.getType() == Token.SETVAR || node.getType() == Token.SETCONSTVAR) {
            Node lhs = node.getFirstChild();
            if (lhs != null) {
                return readsVar(lhs.getNext(), varName);
            }
            return false;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (readsVar(child, varName)) return true;
        }
        return false;
    }

    private boolean killsVar(Node node, String varName) {
        // Walk into EXPR_VOID to find nested SETVAR
        if (node.getType() == Token.EXPR_VOID || node.getType() == Token.EXPR_RESULT) {
            Node child = node.getFirstChild();
            if (child != null) return killsVar(child, varName);
            return false;
        }
        return (node.getType() == Token.SETVAR || node.getType() == Token.SETCONSTVAR)
                && node.getFirstChild() != null
                && varName.equals(node.getFirstChild().getString());
    }

    static boolean isPure(Node n) {
        if (n == null) return true;
        switch (n.getType()) {
            case Token.GETVAR:
            case Token.NUMBER:
            case Token.STRING:
            case Token.TRUE:
            case Token.FALSE:
            case Token.NULL:
            case Token.VOID:
            case Token.THIS:
            case Token.EMPTY:
                return true;

            case Token.ADD:
            case Token.SUB:
            case Token.MUL:
            case Token.DIV:
            case Token.MOD:
            case Token.EQ:
            case Token.NE:
            case Token.SHEQ:
            case Token.SHNE:
            case Token.LT:
            case Token.LE:
            case Token.GT:
            case Token.GE:
            case Token.LSH:
            case Token.RSH:
            case Token.URSH:
            case Token.BITOR:
            case Token.BITXOR:
            case Token.BITAND:
            case Token.NEG:
            case Token.POS:
            case Token.NOT:
            case Token.BITNOT:
            case Token.TYPEOF:
                return childrenPure(n);

            default:
                return false;
        }
    }

    private static boolean childrenPure(Node n) {
        for (Node child = n.getFirstChild(); child != null; child = child.getNext()) {
            if (!isPure(child)) return false;
        }
        return true;
    }
}
