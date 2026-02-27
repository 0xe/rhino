/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.optimizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.mozilla.javascript.Node;
import org.mozilla.javascript.Token;
import org.mozilla.javascript.ast.Jump;

/**
 * A clean basic block and control-flow graph (CFG) representation.
 *
 * <p>Builds from a flattened {@code Node[]} (the same approach as {@link Block#buildBlocks()}), but
 * provides a clean public API not coupled to {@link OptFunctionNode} or liveness analysis.
 */
public class BasicBlock {

    private final int id;
    private final int startIndex;
    private final int endIndex;
    private final Node[] statements;
    private final List<BasicBlock> successors = new ArrayList<>();
    private final List<BasicBlock> predecessors = new ArrayList<>();

    BasicBlock(int id, int startIndex, int endIndex, Node[] statements) {
        this.id = id;
        this.startIndex = startIndex;
        this.endIndex = endIndex;
        this.statements = statements;
    }

    public int getId() {
        return id;
    }

    public int getStartIndex() {
        return startIndex;
    }

    public int getEndIndex() {
        return endIndex;
    }

    public List<Node> getStatements() {
        List<Node> result = new ArrayList<>();
        for (int i = startIndex; i <= endIndex; i++) {
            result.add(statements[i]);
        }
        return result;
    }

    public List<BasicBlock> getSuccessors() {
        return Collections.unmodifiableList(successors);
    }

    public List<BasicBlock> getPredecessors() {
        return Collections.unmodifiableList(predecessors);
    }

    void addSuccessor(BasicBlock b) {
        if (!successors.contains(b)) {
            successors.add(b);
        }
    }

    void addPredecessor(BasicBlock b) {
        if (!predecessors.contains(b)) {
            predecessors.add(b);
        }
    }

    /** Builds a CFG from a flattened statement array. */
    public static List<BasicBlock> buildCFG(Node[] statementNodes) {
        if (statementNodes.length == 0) {
            return Collections.emptyList();
        }

        // Phase 1: identify block boundaries
        List<int[]> blockRanges = new ArrayList<>();
        int beginNodeIndex = 0;

        for (int i = 0; i < statementNodes.length; i++) {
            switch (statementNodes[i].getType()) {
                case Token.TARGET:
                    if (i != beginNodeIndex) {
                        blockRanges.add(new int[] {beginNodeIndex, i - 1});
                        beginNodeIndex = i;
                    }
                    break;
                case Token.IFNE:
                case Token.IFEQ:
                case Token.GOTO:
                    blockRanges.add(new int[] {beginNodeIndex, i});
                    beginNodeIndex = i + 1;
                    break;
            }
        }

        if (beginNodeIndex < statementNodes.length) {
            blockRanges.add(new int[] {beginNodeIndex, statementNodes.length - 1});
        }

        // Phase 2: create BasicBlock objects and index TARGET nodes
        List<BasicBlock> blocks = new ArrayList<>();
        Map<Node, BasicBlock> targetToBlock = new HashMap<>();
        for (int i = 0; i < blockRanges.size(); i++) {
            int[] range = blockRanges.get(i);
            BasicBlock bb = new BasicBlock(i, range[0], range[1], statementNodes);
            blocks.add(bb);
            if (statementNodes[range[0]].getType() == Token.TARGET) {
                targetToBlock.put(statementNodes[range[0]], bb);
            }
        }

        // Phase 3: build successor/predecessor edges
        for (int i = 0; i < blocks.size(); i++) {
            BasicBlock bb = blocks.get(i);
            Node blockEndNode = statementNodes[bb.endIndex];
            int blockEndNodeType = blockEndNode.getType();

            // Fall-through edge (unless block ends with unconditional GOTO)
            if (blockEndNodeType != Token.GOTO && i < blocks.size() - 1) {
                BasicBlock fallThrough = blocks.get(i + 1);
                bb.addSuccessor(fallThrough);
                fallThrough.addPredecessor(bb);
            }

            // Branch edge
            if (blockEndNodeType == Token.IFNE
                    || blockEndNodeType == Token.IFEQ
                    || blockEndNodeType == Token.GOTO) {
                Node target = ((Jump) blockEndNode).target;
                BasicBlock branchTarget = targetToBlock.get(target);
                if (branchTarget != null) {
                    bb.addSuccessor(branchTarget);
                    branchTarget.addPredecessor(bb);
                }
            }
        }

        return blocks;
    }

    /** Generates DOT (Graphviz) output for a list of basic blocks. */
    public static String toDot(List<BasicBlock> blocks, Node[] statementNodes) {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph CFG {\n");
        sb.append("  node [shape=box, fontname=\"Courier\"];\n");

        for (BasicBlock bb : blocks) {
            sb.append("  B").append(bb.id).append(" [label=\"B").append(bb.id).append("\\l");
            for (int i = bb.startIndex; i <= bb.endIndex; i++) {
                String typeName = Token.typeToName(statementNodes[i].getType());
                sb.append("  ").append(typeName).append("\\l");
            }
            sb.append("\"];\n");
        }

        for (BasicBlock bb : blocks) {
            for (BasicBlock succ : bb.successors) {
                sb.append("  B").append(bb.id).append(" -> B").append(succ.id).append(";\n");
            }
        }

        sb.append("}\n");
        return sb.toString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("B").append(id);
        sb.append("[").append(startIndex).append("..").append(endIndex).append("]");
        sb.append(" succ={");
        for (int i = 0; i < successors.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("B").append(successors.get(i).id);
        }
        sb.append("} pred={");
        for (int i = 0; i < predecessors.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("B").append(predecessors.get(i).id);
        }
        sb.append("}");
        return sb.toString();
    }
}
