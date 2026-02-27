/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.optimizer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.mozilla.javascript.Node;
import org.mozilla.javascript.Token;
import org.mozilla.javascript.ast.Scope;

/**
 * Local Value Numbering (LVN) pass operating per basic block.
 *
 * <p>In Rhino's lowered IR, variable assignments are represented as {@code EXPR_VOID { SETVAR {
 * STRING[name], rhs } }}. This pass assigns value numbers to expressions and uses them for:
 *
 * <ul>
 *   <li>Constant folding — binary arithmetic on two known constants
 *   <li>Common subexpression elimination (CSE) — reuse a previously computed value
 *   <li>Copy propagation — SETVAR x = y makes x share y's value number
 * </ul>
 */
class LocalValueNumbering {

    private Map<String, Integer> exprToVN;
    private Map<Integer, Node> vnToNode;
    private Map<String, Integer> varToVN;
    private Map<Integer, String> vnToVar;
    private int nextVN;

    void run(List<BasicBlock> blocks) {
        for (BasicBlock block : blocks) {
            processBlock(block);
        }
    }

    private void processBlock(BasicBlock block) {
        exprToVN = new HashMap<>();
        vnToNode = new HashMap<>();
        varToVN = new HashMap<>();
        vnToVar = new HashMap<>();
        nextVN = 0;

        int count = block.getStatementCount();
        for (int i = 0; i < count; i++) {
            Node stmt = block.getStatement(i);
            processStatement(stmt);
        }
    }

    private void processStatement(Node stmt) {
        int type = stmt.getType();

        if (type == Token.EXPR_VOID || type == Token.EXPR_RESULT) {
            Node child = stmt.getFirstChild();
            if (child == null) return;

            if (child.getType() == Token.SETVAR || child.getType() == Token.SETCONSTVAR) {
                processSetVar(child, stmt);
            } else {
                // General expression statement — number and possibly rewrite
                int vn = numberExpression(child);
                if (vn >= 0) {
                    Node rewritten = rewriteExpr(child, vn);
                    if (rewritten != null && rewritten != child) {
                        stmt.replaceChild(child, rewritten);
                    }
                }
                if (containsCall(child)) {
                    invalidateAllVars();
                }
            }
        } else if (type == Token.RETURN || type == Token.RETURN_RESULT) {
            Node child = stmt.getFirstChild();
            if (child != null) {
                int vn = numberExpression(child);
                if (vn >= 0) {
                    Node rewritten = rewriteExpr(child, vn);
                    if (rewritten != null && rewritten != child) {
                        stmt.replaceChild(child, rewritten);
                    }
                }
            }
        }
    }

    private void processSetVar(Node setvar, Node exprVoid) {
        Node nameNode = setvar.getFirstChild();
        if (nameNode == null) return;
        Node rhs = nameNode.getNext();
        if (rhs == null) return;

        String varName = nameNode.getString();

        // Number the RHS expression
        int vn = numberExpression(rhs);

        // Invalidate if RHS contains calls
        if (containsCall(rhs)) {
            invalidateAllVars();
            return;
        }

        if (vn >= 0) {
            // Try to rewrite the RHS (constant fold / CSE)
            Node rewritten = rewriteExpr(rhs, vn);
            if (rewritten != null && rewritten != rhs) {
                setvar.replaceChild(rhs, rewritten);
            }

            // Register this variable's value number
            varToVN.put(varName, vn);
            if (!vnToVar.containsKey(vn)) {
                vnToVar.put(vn, varName);
            }
        }
    }

    /**
     * Assigns a value number to an expression. Returns the VN, or -1 if the expression is not
     * numberable.
     */
    private int numberExpression(Node n) {
        if (n == null) return -1;

        switch (n.getType()) {
            case Token.NUMBER:
                {
                    String key = "NUM:" + n.getDouble();
                    return getOrAssignVN(key, n);
                }
            case Token.STRING:
                {
                    String key = "STR:" + n.getString();
                    return getOrAssignVN(key, n);
                }
            case Token.TRUE:
                return getOrAssignVN("TRUE", n);
            case Token.FALSE:
                return getOrAssignVN("FALSE", n);
            case Token.NULL:
                return getOrAssignVN("NULL", n);
            case Token.VOID:
                return getOrAssignVN("VOID", n);

            case Token.GETVAR:
                {
                    String varName = n.getString();
                    Integer vn = varToVN.get(varName);
                    if (vn != null) return vn;
                    int freshVN = nextVN++;
                    varToVN.put(varName, freshVN);
                    vnToNode.put(freshVN, n);
                    if (!vnToVar.containsKey(freshVN)) {
                        vnToVar.put(freshVN, varName);
                    }
                    return freshVN;
                }

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
                {
                    Node left = n.getFirstChild();
                    Node right = left != null ? left.getNext() : null;
                    int leftVN = numberExpression(left);
                    int rightVN = numberExpression(right);
                    if (leftVN < 0 || rightVN < 0) return -1;

                    // Try constant folding
                    Node folded = tryConstantFold(n.getType(), leftVN, rightVN);
                    if (folded != null) {
                        String foldKey = "NUM:" + folded.getDouble();
                        return getOrAssignVN(foldKey, folded);
                    }

                    String key = Token.typeToName(n.getType()) + ":" + leftVN + ":" + rightVN;
                    return getOrAssignVN(key, n);
                }

            case Token.NEG:
            case Token.POS:
            case Token.NOT:
            case Token.BITNOT:
            case Token.TYPEOF:
                {
                    Node child = n.getFirstChild();
                    int childVN = numberExpression(child);
                    if (childVN < 0) return -1;
                    String key = Token.typeToName(n.getType()) + ":" + childVN;
                    return getOrAssignVN(key, n);
                }

            case Token.CALL:
            case Token.NEW:
                invalidateAllVars();
                return -1;

            default:
                return -1;
        }
    }

    private int getOrAssignVN(String key, Node n) {
        Integer existing = exprToVN.get(key);
        if (existing != null) return existing;
        int vn = nextVN++;
        exprToVN.put(key, vn);
        vnToNode.put(vn, n);
        return vn;
    }

    /**
     * If both operands map to NUMBER nodes, compute the result at compile time. Returns a new
     * NUMBER node with the result, or null if folding is not possible.
     */
    private Node tryConstantFold(int op, int leftVN, int rightVN) {
        Node leftNode = vnToNode.get(leftVN);
        Node rightNode = vnToNode.get(rightVN);
        if (leftNode == null
                || rightNode == null
                || leftNode.getType() != Token.NUMBER
                || rightNode.getType() != Token.NUMBER) {
            return null;
        }

        double l = leftNode.getDouble();
        double r = rightNode.getDouble();
        double result;

        switch (op) {
            case Token.ADD:
                result = l + r;
                break;
            case Token.SUB:
                result = l - r;
                break;
            case Token.MUL:
                result = l * r;
                break;
            case Token.DIV:
                result = l / r;
                break;
            case Token.MOD:
                result = l % r;
                break;
            default:
                return null;
        }

        return Node.newNumber(result);
    }

    /**
     * If the VN maps to a constant NUMBER, replaces the expression with that constant. If the VN
     * has a canonical variable and the expression is compound, replaces with a GETVAR. For simple
     * GETVAR nodes, applies copy propagation.
     */
    private Node rewriteExpr(Node original, int vn) {
        int type = original.getType();

        // For simple leaf nodes, only do copy propagation on GETVAR
        if (type == Token.GETVAR) {
            String canonVar = vnToVar.get(vn);
            if (canonVar != null && !canonVar.equals(original.getString())) {
                return makeGetVar(canonVar, original);
            }
            return null;
        }
        if (type == Token.NUMBER
                || type == Token.STRING
                || type == Token.TRUE
                || type == Token.FALSE
                || type == Token.NULL
                || type == Token.VOID) {
            return null;
        }

        // For compound expressions: check if this VN maps to a constant
        Node vnNode = vnToNode.get(vn);
        if (vnNode != null && vnNode.getType() == Token.NUMBER) {
            return Node.newNumber(vnNode.getDouble());
        }

        // CSE: if the VN has a canonical variable, replace with GETVAR
        String canonVar = vnToVar.get(vn);
        if (canonVar != null) {
            Scope scope = findScope(original);
            if (scope != null) {
                Node replacement = Node.newString(Token.GETVAR, canonVar);
                ((org.mozilla.javascript.ast.Name) replacement).setScope(scope);
                return replacement;
            }
        }

        return null;
    }

    private Node makeGetVar(String varName, Node original) {
        Node replacement = Node.newString(Token.GETVAR, varName);
        Scope scope = original.getScope();
        if (scope != null) {
            ((org.mozilla.javascript.ast.Name) replacement).setScope(scope);
        }
        return replacement;
    }

    private Scope findScope(Node n) {
        if (n == null) return null;
        if (n.getType() == Token.GETVAR) {
            return n.getScope();
        }
        for (Node child = n.getFirstChild(); child != null; child = child.getNext()) {
            Scope s = findScope(child);
            if (s != null) return s;
        }
        return null;
    }

    private boolean containsCall(Node n) {
        if (n == null) return false;
        if (n.getType() == Token.CALL || n.getType() == Token.NEW) return true;
        for (Node child = n.getFirstChild(); child != null; child = child.getNext()) {
            if (containsCall(child)) return true;
        }
        return false;
    }

    private void invalidateAllVars() {
        vnToVar.clear();
        varToVN.clear();
    }
}
