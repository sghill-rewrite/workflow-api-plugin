package org.jenkinsci.plugins.workflow.graph;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jenkinsci.plugins.workflow.flow.GraphListener;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides overall insight into the structure of a flow graph... but with limited visibility so we can change implementation.
 * Designed to work entirely on the basis of the {@link FlowNode#getId()} rather than the {@link FlowNode}s themselves.
 */
@SuppressFBWarnings(value = "ES_COMPARING_STRINGS_WITH_EQ", justification = "Can can use instance identity when comparing to a final constant")
@Restricted(NoExternalUse.class)
public final class StandardGraphLookupView implements GraphLookupView, GraphListener, GraphListener.Synchronous {

    static final String INCOMPLETE = "";

    /** Map the blockStartNode to its endNode, to accellerate a range of operations */
    ConcurrentHashMap<String, String> blockStartToEnd = new ConcurrentHashMap<>();

    /** Map a node to its nearest enclosing block */
    ConcurrentHashMap<String, String> nearestEnclosingBlock = new ConcurrentHashMap<>();

    public void clearCache() {
        blockStartToEnd.clear();
        nearestEnclosingBlock.clear();
    }

    /** Update with a new node added to the flowgraph */
    @Override
    public void onNewHead(@NonNull FlowNode newHead) {
        if (newHead instanceof BlockEndNode) {
            String startNodeId = ((BlockEndNode)newHead).getStartNode().getId();
            blockStartToEnd.put(startNodeId, newHead.getId());
            String enclosingId = nearestEnclosingBlock.get(startNodeId);
            if (enclosingId != null) {
                nearestEnclosingBlock.put(newHead.getId(), enclosingId);
            }
        } else {
            if (newHead instanceof BlockStartNode) {
                blockStartToEnd.put(newHead.getId(), INCOMPLETE);
            }

            // Now we try to generate enclosing block info for caching, by looking at parents
            // But we don't try to hard -- usually the cache is populated and we defer recursive walks of the graph
            List<FlowNode> parents = newHead.getParents();
            if (parents.size() > 0) {
                FlowNode parent = parents.get(0);  // Multiple parents only for end of a parallel, and then both are BlockEndNodes

                if (parent instanceof BlockStartNode) {
                    nearestEnclosingBlock.put(newHead.getId(), parent.getId());
                } else {
                    // Try to reuse info from cache for this node:
                    //   If the parent ended a block, we use info from the start of the block since that is at the same block nesting level as our new head
                    //   Otherwise the parent is a normal atom node, and the head is enclosed by the same block
                    String lookupId = (parent instanceof BlockEndNode) ? ((BlockEndNode) parent).getStartNode().getId() : parent.getId();
                    String enclosingId = nearestEnclosingBlock.get(lookupId);
                    if (enclosingId != null) {
                        nearestEnclosingBlock.put(newHead.getId(), enclosingId);
                    }
                }
            }
        }
    }

    /** Create a lookup view for an execution */
    public StandardGraphLookupView() {
    }

    @Override
    public boolean isActive(@NonNull FlowNode node) {
        if (node instanceof FlowEndNode) { // cf. JENKINS-26139
            return !node.getExecution().isComplete();
        } else if (node instanceof BlockStartNode){  // BlockStartNode
            return this.getEndNode((BlockStartNode)node) == null;
        } else {
            return node.getExecution().isCurrentHead(node);
        }
    }

    // Do a brute-force scan for the block end matching the start, caching info along the way for future use
    BlockEndNode bruteForceScanForEnd(@NonNull BlockStartNode start) {
        DepthFirstScanner scan = new DepthFirstScanner();
        scan.setup(start.getExecution().getCurrentHeads());
        for (FlowNode f : scan) {
            if (f instanceof BlockEndNode) {
                BlockEndNode end = (BlockEndNode)f;
                BlockStartNode maybeStart = end.getStartNode();
                // Cache start in case we need to scan again in the future
                blockStartToEnd.put(maybeStart.getId(), end.getId());
                if (start.equals(maybeStart)) {
                    return end;
                }
            } else if (f instanceof BlockStartNode) {
                BlockStartNode maybeThis = (BlockStartNode) f;

                // We're walking from the end to the start and see the start without finding the end first, block is incomplete
                blockStartToEnd.putIfAbsent(maybeThis.getId(), INCOMPLETE);
                if (start.equals(maybeThis)) {  // Early exit, the end can't be encountered before the start
                    return null;
                }
            }
        }
        return null;
    }




    /** Do a brute-force scan for the enclosing blocks **/
    BlockStartNode bruteForceScanForEnclosingBlock(@NonNull final FlowNode node) {
        FlowNode current = node;
        Set<String> visited = new HashSet<>();
        while (!(current instanceof FlowStartNode)) {  // Hunt back for enclosing blocks, a potentially expensive operation
            if (!visited.add(current.getId())) {
                throw new IllegalStateException("Cycle in flow graph for " + node.getExecution() + " involving " + current);
            }
            if (current instanceof BlockEndNode) {
                // Hop over the block to the start
                BlockStartNode start = ((BlockEndNode) current).getStartNode();
                blockStartToEnd.put(start.getId(), current.getId());
                current = start;
                continue;  // Simplifies cases below we only need to look at the immediately preceding node.
            }

            // Try for a cache hit
            if (current != node) {
                String enclosingIdFromCache = nearestEnclosingBlock.get(current.getId());
                if (enclosingIdFromCache != null) {
                    try {
                        return (BlockStartNode) node.getExecution().getNode(enclosingIdFromCache);
                    } catch (IOException ioe) {
                        throw new RuntimeException(ioe);
                    }
                }
            }

            // Now see if we have a winner among parents
            if (current.getParents().isEmpty()) {
                return null;
            }
            FlowNode parent = current.getParents().get(0);
            if (parent instanceof BlockStartNode) {
                nearestEnclosingBlock.put(current.getId(), parent.getId());
                return (BlockStartNode) parent;
            }
            current = parent;
        }

        return null;
    }

    @CheckForNull
    @Override
    public BlockEndNode getEndNode(@NonNull final BlockStartNode startNode) {

        String id = blockStartToEnd.get(startNode.getId());
        if (id != null) {
            try {
                return id == INCOMPLETE ? null : (BlockEndNode) startNode.getExecution().getNode(id);
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
        } else {
            // returns the end node or null
            // if this returns end node, it also adds start and end to blockStartToEnd
            return bruteForceScanForEnd(startNode);
        }
    }

    @CheckForNull
    @Override
    public BlockStartNode findEnclosingBlockStart(@NonNull FlowNode node) {
        if (node instanceof FlowStartNode || node instanceof FlowEndNode) {
            return null;
        }

        String id = nearestEnclosingBlock.get(node.getId());
        if (id != null) {
            try {
                return (BlockStartNode) node.getExecution().getNode(id);
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
        }

        // when scan completes, enclosing is in the cache if it exists
        return bruteForceScanForEnclosingBlock(node);
    }

    @Override
    public Iterable<BlockStartNode> iterateEnclosingBlocks(@NonNull FlowNode node) {
        return new EnclosingBlocksIterable(this, node);
    }

    @NonNull
    @Override
    public List<BlockStartNode> findAllEnclosingBlockStarts(@NonNull FlowNode node) {
        if (node instanceof FlowStartNode || node instanceof FlowEndNode) {
            return Collections.emptyList();
        }
        ArrayList<BlockStartNode> starts = new ArrayList<>(2);
        BlockStartNode currentlyEnclosing = findEnclosingBlockStart(node);
        while (currentlyEnclosing != null) {
            starts.add(currentlyEnclosing);
            currentlyEnclosing = findEnclosingBlockStart(currentlyEnclosing);
        }
        return starts;
    }
}
