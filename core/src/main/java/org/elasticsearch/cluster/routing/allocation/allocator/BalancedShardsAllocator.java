/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cluster.routing.allocation.allocator;

import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.IntroSorter;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.RoutingNodes;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.UnassignedInfo;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.cluster.routing.allocation.ShardAllocationDecision;
import org.elasticsearch.cluster.routing.allocation.ShardAllocationDecision.WeightedDecision;
import org.elasticsearch.cluster.routing.allocation.decider.AllocationDeciders;
import org.elasticsearch.cluster.routing.allocation.decider.Decision;
import org.elasticsearch.cluster.routing.allocation.decider.Decision.Type;
import org.elasticsearch.cluster.routing.allocation.decider.DiskThresholdDecider;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.gateway.PriorityComparator;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.elasticsearch.cluster.routing.ShardRoutingState.RELOCATING;

/**
 * The {@link BalancedShardsAllocator} re-balances the nodes allocations
 * within an cluster based on a {@link WeightFunction}. The clusters balance is defined by four parameters which can be set
 * in the cluster update API that allows changes in real-time:
 * <ul><li><code>cluster.routing.allocation.balance.shard</code> - The <b>shard balance</b> defines the weight factor
 * for shards allocated on a {@link RoutingNode}</li>
 * <li><code>cluster.routing.allocation.balance.index</code> - The <b>index balance</b> defines a factor to the number
 * of {@link org.elasticsearch.cluster.routing.ShardRouting}s per index allocated on a specific node</li>
 * <li><code>cluster.routing.allocation.balance.threshold</code> - A <b>threshold</b> to set the minimal optimization
 * value of operations that should be performed</li>
 * </ul>
 * <p>
 * These parameters are combined in a {@link WeightFunction} that allows calculation of node weights which
 * are used to re-balance shards based on global as well as per-index factors.
 */
public class BalancedShardsAllocator extends AbstractComponent implements ShardsAllocator {

    public static final Setting<Float> INDEX_BALANCE_FACTOR_SETTING =
        Setting.floatSetting("cluster.routing.allocation.balance.index", 0.55f, 0.0f, Property.Dynamic, Property.NodeScope);
    public static final Setting<Float> SHARD_BALANCE_FACTOR_SETTING =
        Setting.floatSetting("cluster.routing.allocation.balance.shard", 0.45f, 0.0f, Property.Dynamic, Property.NodeScope);
    public static final Setting<Float> THRESHOLD_SETTING =
        Setting.floatSetting("cluster.routing.allocation.balance.threshold", 1.0f, 0.0f,
            Property.Dynamic, Property.NodeScope);

    private volatile WeightFunction weightFunction;
    private volatile float threshold;

    public BalancedShardsAllocator(Settings settings) {
        this(settings, new ClusterSettings(settings, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS));
    }

    @Inject
    public BalancedShardsAllocator(Settings settings, ClusterSettings clusterSettings) {
        super(settings);
        setWeightFunction(INDEX_BALANCE_FACTOR_SETTING.get(settings), SHARD_BALANCE_FACTOR_SETTING.get(settings));
        setThreshold(THRESHOLD_SETTING.get(settings));
        clusterSettings.addSettingsUpdateConsumer(INDEX_BALANCE_FACTOR_SETTING, SHARD_BALANCE_FACTOR_SETTING, this::setWeightFunction);
        clusterSettings.addSettingsUpdateConsumer(THRESHOLD_SETTING, this::setThreshold);
    }

    private void setWeightFunction(float indexBalance, float shardBalanceFactor) {
        weightFunction = new WeightFunction(indexBalance, shardBalanceFactor);
    }

    private void setThreshold(float threshold) {
        this.threshold = threshold;
    }

    @Override
    public Map<DiscoveryNode, Float> weighShard(RoutingAllocation allocation, ShardRouting shard) {
        final Balancer balancer = new Balancer(logger, allocation, weightFunction, threshold);
        return balancer.weighShard(shard);
    }

    @Override
    public void allocate(RoutingAllocation allocation) {
        if (allocation.routingNodes().size() == 0) {
            /* with no nodes this is pointless */
            return;
        }
        final Balancer balancer = new Balancer(logger, allocation, weightFunction, threshold);
        balancer.allocateUnassigned();
        balancer.moveShards();
        balancer.balance();
    }

    /**
     * Returns the currently configured delta threshold
     */
    public float getThreshold() {
        return threshold;
    }

    /**
     * Returns the index related weight factor.
     */
    public float getIndexBalance() {
        return weightFunction.indexBalance;
    }

    /**
     * Returns the shard related weight factor.
     */
    public float getShardBalance() {
        return weightFunction.shardBalance;
    }


    /**
     * This class is the primary weight function used to create balanced over nodes and shards in the cluster.
     * Currently this function has 3 properties:
     * <ul>
     * <li><code>index balance</code> - balance property over shards per index</li>
     * <li><code>shard balance</code> - balance property over shards per cluster</li>
     * </ul>
     * <p>
     * Each of these properties are expressed as factor such that the properties factor defines the relative importance of the property for the
     * weight function. For example if the weight function should calculate the weights only based on a global (shard) balance the index balance
     * can be set to <tt>0.0</tt> and will in turn have no effect on the distribution.
     * </p>
     * The weight per index is calculated based on the following formula:
     * <ul>
     * <li>
     * <code>weight<sub>index</sub>(node, index) = indexBalance * (node.numShards(index) - avgShardsPerNode(index))</code>
     * </li>
     * <li>
     * <code>weight<sub>node</sub>(node, index) = shardBalance * (node.numShards() - avgShardsPerNode)</code>
     * </li>
     * </ul>
     * <code>weight(node, index) = weight<sub>index</sub>(node, index) + weight<sub>node</sub>(node, index)</code>
     */
    public static class WeightFunction {

        private final float indexBalance;
        private final float shardBalance;
        private final float theta0;
        private final float theta1;


        public WeightFunction(float indexBalance, float shardBalance) {
            float sum = indexBalance + shardBalance;
            if (sum <= 0.0f) {
                throw new IllegalArgumentException("Balance factors must sum to a value > 0 but was: " + sum);
            }
            theta0 = shardBalance / sum;
            theta1 = indexBalance / sum;
            this.indexBalance = indexBalance;
            this.shardBalance = shardBalance;
        }

        public float weight(Balancer balancer, ModelNode node, String index) {
            return weight(balancer, node, index, 0);
        }

        public float weightShardAdded(Balancer balancer, ModelNode node, String index) {
            return weight(balancer, node, index, 1);
        }

        public float weightShardRemoved(Balancer balancer, ModelNode node, String index) {
            return weight(balancer, node, index, -1);
        }

        private float weight(Balancer balancer, ModelNode node, String index, int numAdditionalShards) {
            final float weightShard = node.numShards() + numAdditionalShards - balancer.avgShardsPerNode();
            final float weightIndex = node.numShards(index) + numAdditionalShards - balancer.avgShardsPerNode(index);
            return theta0 * weightShard + theta1 * weightIndex;
        }
    }

    /**
     * A {@link Balancer}
     */
    public static class Balancer {
        private final Logger logger;
        private final Map<String, ModelNode> nodes;
        private final RoutingAllocation allocation;
        private final RoutingNodes routingNodes;
        private final WeightFunction weight;

        private final float threshold;
        private final MetaData metaData;
        private final float avgShardsPerNode;
        private final NodeSorter sorter;

        public Balancer(Logger logger, RoutingAllocation allocation, WeightFunction weight, float threshold) {
            this.logger = logger;
            this.allocation = allocation;
            this.weight = weight;
            this.threshold = threshold;
            this.routingNodes = allocation.routingNodes();
            this.metaData = allocation.metaData();
            avgShardsPerNode = ((float) metaData.getTotalNumberOfShards()) / routingNodes.size();
            nodes = Collections.unmodifiableMap(buildModelFromAssigned());
            sorter = newNodeSorter();
        }

        /**
         * Returns an array view on the nodes in the balancer. Nodes should not be removed from this list.
         */
        private ModelNode[] nodesArray() {
            return nodes.values().toArray(new ModelNode[nodes.size()]);
        }

        /**
         * Returns the average of shards per node for the given index
         */
        public float avgShardsPerNode(String index) {
            return ((float) metaData.index(index).getTotalNumberOfShards()) / nodes.size();
        }

        /**
         * Returns the global average of shards per node
         */
        public float avgShardsPerNode() {
            return avgShardsPerNode;
        }

        /**
         * Returns a new {@link NodeSorter} that sorts the nodes based on their
         * current weight with respect to the index passed to the sorter. The
         * returned sorter is not sorted. Use {@link NodeSorter#reset(String)}
         * to sort based on an index.
         */
        private NodeSorter newNodeSorter() {
            return new NodeSorter(nodesArray(), weight, this);
        }

        private static float absDelta(float lower, float higher) {
            assert higher >= lower : higher + " lt " + lower +" but was expected to be gte";
            return Math.abs(higher - lower);
        }

        private static boolean lessThan(float delta, float threshold) {
            /* deltas close to the threshold are "rounded" to the threshold manually
               to prevent floating point problems if the delta is very close to the
               threshold ie. 1.000000002 which can trigger unnecessary balance actions*/
            return delta <= (threshold + 0.001f);
        }

        /**
         * Balances the nodes on the cluster model according to the weight function.
         * The actual balancing is delegated to {@link #balanceByWeights()}
         */
        private void balance() {
            if (logger.isTraceEnabled()) {
                logger.trace("Start balancing cluster");
            }
            if (allocation.hasPendingAsyncFetch()) {
                /*
                 * see https://github.com/elastic/elasticsearch/issues/14387
                 * if we allow rebalance operations while we are still fetching shard store data
                 * we might end up with unnecessary rebalance operations which can be super confusion/frustrating
                 * since once the fetches come back we might just move all the shards back again.
                 * Therefore we only do a rebalance if we have fetched all information.
                 */
                logger.debug("skipping rebalance due to in-flight shard/store fetches");
                return;
            }
            if (allocation.deciders().canRebalance(allocation).type() != Type.YES) {
                logger.trace("skipping rebalance as it is disabled");
                return;
            }
            if (nodes.size() < 2) { /* skip if we only have one node */
                logger.trace("skipping rebalance as single node only");
                return;
            }
            balanceByWeights();
        }

        public Map<DiscoveryNode, Float> weighShard(ShardRouting shard) {
            final ModelNode[] modelNodes = sorter.modelNodes;
            final float[] weights = sorter.weights;

            buildWeightOrderedIndices();
            Map<DiscoveryNode, Float> nodes = new HashMap<>(modelNodes.length);
            float currentNodeWeight = 0.0f;
            for (int i = 0; i < modelNodes.length; i++) {
                if (modelNodes[i].getNodeId().equals(shard.currentNodeId())) {
                    // If a node was found with the shard, use that weight instead of 0.0
                    currentNodeWeight = weights[i];
                    break;
                }
            }

            for (int i = 0; i < modelNodes.length; i++) {
                final float delta = currentNodeWeight - weights[i];
                nodes.put(modelNodes[i].getRoutingNode().node(), delta);
            }
            return nodes;
        }

        /**
         * Balances the nodes on the cluster model according to the weight
         * function. The configured threshold is the minimum delta between the
         * weight of the maximum node and the minimum node according to the
         * {@link WeightFunction}. This weight is calculated per index to
         * distribute shards evenly per index. The balancer tries to relocate
         * shards only if the delta exceeds the threshold. In the default case
         * the threshold is set to <tt>1.0</tt> to enforce gaining relocation
         * only, or in other words relocations that move the weight delta closer
         * to <tt>0.0</tt>
         */
        private void balanceByWeights() {
            final AllocationDeciders deciders = allocation.deciders();
            final ModelNode[] modelNodes = sorter.modelNodes;
            final float[] weights = sorter.weights;
            for (String index : buildWeightOrderedIndices()) {
                IndexMetaData indexMetaData = metaData.index(index);

                // find nodes that have a shard of this index or where shards of this index are allowed to be allocated to,
                // move these nodes to the front of modelNodes so that we can only balance based on these nodes
                int relevantNodes = 0;
                for (int i = 0; i < modelNodes.length; i++) {
                    ModelNode modelNode = modelNodes[i];
                    if (modelNode.getIndex(index) != null
                        || deciders.canAllocate(indexMetaData, modelNode.getRoutingNode(), allocation).type() != Type.NO) {
                        // swap nodes at position i and relevantNodes
                        modelNodes[i] = modelNodes[relevantNodes];
                        modelNodes[relevantNodes] = modelNode;
                        relevantNodes++;
                    }
                }

                if (relevantNodes < 2) {
                    continue;
                }

                sorter.reset(index, 0, relevantNodes);
                int lowIdx = 0;
                int highIdx = relevantNodes - 1;
                while (true) {
                    final ModelNode minNode = modelNodes[lowIdx];
                    final ModelNode maxNode = modelNodes[highIdx];
                    advance_range:
                    if (maxNode.numShards(index) > 0) {
                        final float delta = absDelta(weights[lowIdx], weights[highIdx]);
                        if (lessThan(delta, threshold)) {
                            if (lowIdx > 0 && highIdx-1 > 0 // is there a chance for a higher delta?
                                && (absDelta(weights[0], weights[highIdx-1]) > threshold) // check if we need to break at all
                                ) {
                                /* This is a special case if allocations from the "heaviest" to the "lighter" nodes is not possible
                                 * due to some allocation decider restrictions like zone awareness. if one zone has for instance
                                 * less nodes than another zone. so one zone is horribly overloaded from a balanced perspective but we
                                 * can't move to the "lighter" shards since otherwise the zone would go over capacity.
                                 *
                                 * This break jumps straight to the condition below were we start moving from the high index towards
                                 * the low index to shrink the window we are considering for balance from the other direction.
                                 * (check shrinking the window from MAX to MIN)
                                 * See #3580
                                 */
                                break advance_range;
                            }
                            if (logger.isTraceEnabled()) {
                                logger.trace("Stop balancing index [{}]  min_node [{}] weight: [{}]  max_node [{}] weight: [{}]  delta: [{}]",
                                        index, maxNode.getNodeId(), weights[highIdx], minNode.getNodeId(), weights[lowIdx], delta);
                            }
                            break;
                        }
                        if (logger.isTraceEnabled()) {
                            logger.trace("Balancing from node [{}] weight: [{}] to node [{}] weight: [{}]  delta: [{}]",
                                    maxNode.getNodeId(), weights[highIdx], minNode.getNodeId(), weights[lowIdx], delta);
                        }
                        /* pass the delta to the replication function to prevent relocations that only swap the weights of the two nodes.
                         * a relocation must bring us closer to the balance if we only achieve the same delta the relocation is useless */
                        if (tryRelocateShard(minNode, maxNode, index, delta)) {
                            /*
                             * TODO we could be a bit smarter here, we don't need to fully sort necessarily
                             * we could just find the place to insert linearly but the win might be minor
                             * compared to the added complexity
                             */
                            weights[lowIdx] = sorter.weight(modelNodes[lowIdx]);
                            weights[highIdx] = sorter.weight(modelNodes[highIdx]);
                            sorter.sort(0, relevantNodes);
                            lowIdx = 0;
                            highIdx = relevantNodes - 1;
                            continue;
                        }
                    }
                    if (lowIdx < highIdx - 1) {
                        /* Shrinking the window from MIN to MAX
                         * we can't move from any shard from the min node lets move on to the next node
                         * and see if the threshold still holds. We either don't have any shard of this
                         * index on this node of allocation deciders prevent any relocation.*/
                        lowIdx++;
                    } else if (lowIdx > 0) {
                        /* Shrinking the window from MAX to MIN
                         * now we go max to min since obviously we can't move anything to the max node
                         * lets pick the next highest */
                        lowIdx = 0;
                        highIdx--;
                    } else {
                        /* we are done here, we either can't relocate anymore or we are balanced */
                        break;
                    }
                }
            }
        }

        /**
         * This builds a initial index ordering where the indices are returned
         * in most unbalanced first. We need this in order to prevent over
         * allocations on added nodes from one index when the weight parameters
         * for global balance overrule the index balance at an intermediate
         * state. For example this can happen if we have 3 nodes and 3 indices
         * with 3 primary and 1 replica shards. At the first stage all three nodes hold
         * 2 shard for each index. Now we add another node and the first index
         * is balanced moving three shards from two of the nodes over to the new node since it
         * has no shards yet and global balance for the node is way below
         * average. To re-balance we need to move shards back eventually likely
         * to the nodes we relocated them from.
         */
        private String[] buildWeightOrderedIndices() {
            final String[] indices = allocation.routingTable().indicesRouting().keys().toArray(String.class);
            final float[] deltas = new float[indices.length];
            for (int i = 0; i < deltas.length; i++) {
                sorter.reset(indices[i]);
                deltas[i] = sorter.delta();
            }
            new IntroSorter() {

                float pivotWeight;

                @Override
                protected void swap(int i, int j) {
                    final String tmpIdx = indices[i];
                    indices[i] = indices[j];
                    indices[j] = tmpIdx;
                    final float tmpDelta = deltas[i];
                    deltas[i] = deltas[j];
                    deltas[j] = tmpDelta;
                }

                @Override
                protected int compare(int i, int j) {
                    return Float.compare(deltas[j], deltas[i]);
                }

                @Override
                protected void setPivot(int i) {
                    pivotWeight = deltas[i];
                }

                @Override
                protected int comparePivot(int j) {
                    return Float.compare(deltas[j], pivotWeight);
                }
            }.sort(0, deltas.length);

            return indices;
        }

        /**
         * Move started shards that can not be allocated to a node anymore
         *
         * For each shard to be moved this function executes a move operation
         * to the minimal eligible node with respect to the
         * weight function. If a shard is moved the shard will be set to
         * {@link ShardRoutingState#RELOCATING} and a shadow instance of this
         * shard is created with an incremented version in the state
         * {@link ShardRoutingState#INITIALIZING}.
         */
        public void moveShards() {
            // Iterate over the started shards interleaving between nodes, and check if they can remain. In the presence of throttling
            // shard movements, the goal of this iteration order is to achieve a fairer movement of shards from the nodes that are
            // offloading the shards.
            for (Iterator<ShardRouting> it = allocation.routingNodes().nodeInterleavedShardIterator(); it.hasNext(); ) {
                ShardRouting shardRouting = it.next();
                final MoveDecision moveDecision = makeMoveDecision(shardRouting);
                if (moveDecision.move()) {
                    final ModelNode sourceNode = nodes.get(shardRouting.currentNodeId());
                    final ModelNode targetNode = nodes.get(moveDecision.getAssignedNodeId());
                    sourceNode.removeShard(shardRouting);
                    Tuple<ShardRouting, ShardRouting> relocatingShards = routingNodes.relocateShard(shardRouting, targetNode.getNodeId(),
                        allocation.clusterInfo().getShardSize(shardRouting, ShardRouting.UNAVAILABLE_EXPECTED_SHARD_SIZE), allocation.changes());
                    targetNode.addShard(relocatingShards.v2());
                    if (logger.isTraceEnabled()) {
                        logger.trace("Moved shard [{}] to node [{}]", shardRouting, targetNode.getRoutingNode());
                    }
                } else if (moveDecision.cannotRemain()) {
                    logger.trace("[{}][{}] can't move", shardRouting.index(), shardRouting.id());
                }
            }
        }

        /**
         * Makes a decision on whether to move a started shard to another node.  The following rules apply
         * to the {@link MoveDecision} return object:
         *   1. If the shard is not started, no decision will be taken and {@link MoveDecision#isDecisionTaken()} will return false.
         *   2. If the shard is allowed to remain on its current node, no attempt will be made to move the shard and
         *      {@link MoveDecision#canRemainDecision} will have a decision type of YES.  All other fields in the object will be null.
         *   3. If the shard is not allowed to remain on its current node, then {@link MoveDecision#finalDecision} will be populated
         *      with the decision of moving to another node.  If {@link MoveDecision#finalDecision} returns YES, then
         *      {@link MoveDecision#assignedNodeId} will return a non-null value, otherwise the assignedNodeId will be null.
         *   4. If the method is invoked in explain mode (e.g. from the cluster allocation explain APIs), then
         *      {@link MoveDecision#finalExplanation} and {@link MoveDecision#nodeDecisions} will have non-null values.
         */
        public MoveDecision makeMoveDecision(final ShardRouting shardRouting) {
            if (shardRouting.started() == false) {
                // we can only move started shards
                return MoveDecision.DECISION_NOT_TAKEN;
            }

            final boolean explain = allocation.debugDecision();
            final ModelNode sourceNode = nodes.get(shardRouting.currentNodeId());
            assert sourceNode != null && sourceNode.containsShard(shardRouting);
            RoutingNode routingNode = sourceNode.getRoutingNode();
            Decision canRemain = allocation.deciders().canRemain(shardRouting, routingNode, allocation);
            if (canRemain.type() != Decision.Type.NO) {
                return MoveDecision.stay(canRemain, explain);
            }

            sorter.reset(shardRouting.getIndexName());
            /*
             * the sorter holds the minimum weight node first for the shards index.
             * We now walk through the nodes until we find a node to allocate the shard.
             * This is not guaranteed to be balanced after this operation we still try best effort to
             * allocate on the minimal eligible node.
             */
            Type bestDecision = Type.NO;
            RoutingNode targetNode = null;
            final Map<String, WeightedDecision> nodeExplanationMap = explain ? new HashMap<>() : null;
            for (ModelNode currentNode : sorter.modelNodes) {
                if (currentNode != sourceNode) {
                    RoutingNode target = currentNode.getRoutingNode();
                    // don't use canRebalance as we want hard filtering rules to apply. See #17698
                    Decision allocationDecision = allocation.deciders().canAllocate(shardRouting, target, allocation);
                    if (explain) {
                        nodeExplanationMap.put(currentNode.getNodeId(), new WeightedDecision(allocationDecision, sorter.weight(currentNode)));
                    }
                    // TODO maybe we can respect throttling here too?
                    if (allocationDecision.type().higherThan(bestDecision)) {
                        bestDecision = allocationDecision.type();
                        if (bestDecision == Type.YES) {
                            targetNode = target;
                            if (explain == false) {
                                // we are not in explain mode and already have a YES decision on the best weighted node,
                                // no need to continue iterating
                                break;
                            }
                        }
                    }
                }
            }

            return MoveDecision.decision(canRemain, bestDecision, explain, shardRouting.currentNodeId(),
                targetNode != null ? targetNode.nodeId() : null, nodeExplanationMap);
        }

        /**
         * Builds the internal model from all shards in the given
         * {@link Iterable}. All shards in the {@link Iterable} must be assigned
         * to a node. This method will skip shards in the state
         * {@link ShardRoutingState#RELOCATING} since each relocating shard has
         * a shadow shard in the state {@link ShardRoutingState#INITIALIZING}
         * on the target node which we respect during the allocation / balancing
         * process. In short, this method recreates the status-quo in the cluster.
         */
        private Map<String, ModelNode> buildModelFromAssigned() {
            Map<String, ModelNode> nodes = new HashMap<>();
            for (RoutingNode rn : routingNodes) {
                ModelNode node = new ModelNode(rn);
                nodes.put(rn.nodeId(), node);
                for (ShardRouting shard : rn) {
                    assert rn.nodeId().equals(shard.currentNodeId());
                    /* we skip relocating shards here since we expect an initializing shard with the same id coming in */
                    if (shard.state() != RELOCATING) {
                        node.addShard(shard);
                        if (logger.isTraceEnabled()) {
                            logger.trace("Assigned shard [{}] to node [{}]", shard, node.getNodeId());
                        }
                    }
                }
            }
            return nodes;
        }

        /**
         * Allocates all given shards on the minimal eligible node for the shards index
         * with respect to the weight function. All given shards must be unassigned.
         */
        private void allocateUnassigned() {
            RoutingNodes.UnassignedShards unassigned = routingNodes.unassigned();
            assert !nodes.isEmpty();
            if (logger.isTraceEnabled()) {
                logger.trace("Start allocating unassigned shards");
            }
            if (unassigned.isEmpty()) {
                return;
            }

            /*
             * TODO: We could be smarter here and group the shards by index and then
             * use the sorter to save some iterations.
             */
            final AllocationDeciders deciders = allocation.deciders();
            final PriorityComparator secondaryComparator = PriorityComparator.getAllocationComparator(allocation);
            final Comparator<ShardRouting> comparator = (o1, o2) -> {
                if (o1.primary() ^ o2.primary()) {
                    return o1.primary() ? -1 : o2.primary() ? 1 : 0;
                }
                final int indexCmp;
                if ((indexCmp = o1.getIndexName().compareTo(o2.getIndexName())) == 0) {
                    return o1.getId() - o2.getId();
                }
                // this comparator is more expensive than all the others up there
                // that's why it's added last even though it could be easier to read
                // if we'd apply it earlier. this comparator will only differentiate across
                // indices all shards of the same index is treated equally.
                final int secondary = secondaryComparator.compare(o1, o2);
                return secondary == 0 ? indexCmp : secondary;
            };
            /*
             * we use 2 arrays and move replicas to the second array once we allocated an identical
             * replica in the current iteration to make sure all indices get allocated in the same manner.
             * The arrays are sorted by primaries first and then by index and shard ID so a 2 indices with 2 replica and 1 shard would look like:
             * [(0,P,IDX1), (0,P,IDX2), (0,R,IDX1), (0,R,IDX1), (0,R,IDX2), (0,R,IDX2)]
             * if we allocate for instance (0, R, IDX1) we move the second replica to the secondary array and proceed with
             * the next replica. If we could not find a node to allocate (0,R,IDX1) we move all it's replicas to ignoreUnassigned.
             */
            ShardRouting[] primary = unassigned.drain();
            ShardRouting[] secondary = new ShardRouting[primary.length];
            int secondaryLength = 0;
            int primaryLength = primary.length;
            ArrayUtil.timSort(primary, comparator);
            final Set<ModelNode> throttledNodes = Collections.newSetFromMap(new IdentityHashMap<>());
            do {
                for (int i = 0; i < primaryLength; i++) {
                    ShardRouting shard = primary[i];
                    ShardAllocationDecision allocationDecision = decideAllocateUnassigned(shard, throttledNodes);
                    final Type decisionType = allocationDecision.getFinalDecisionType();
                    final String assignedNodeId = allocationDecision.getAssignedNodeId();
                    final ModelNode minNode = assignedNodeId != null ? nodes.get(assignedNodeId) : null;

                    if (decisionType == Type.YES) {
                        if (logger.isTraceEnabled()) {
                            logger.trace("Assigned shard [{}] to [{}]", shard, minNode.getNodeId());
                        }

                        final long shardSize = DiskThresholdDecider.getExpectedShardSize(shard, allocation,
                            ShardRouting.UNAVAILABLE_EXPECTED_SHARD_SIZE);
                        shard = routingNodes.initializeShard(shard, minNode.getNodeId(), null, shardSize, allocation.changes());
                        minNode.addShard(shard);
                        if (!shard.primary()) {
                            // copy over the same replica shards to the secondary array so they will get allocated
                            // in a subsequent iteration, allowing replicas of other shards to be allocated first
                            while(i < primaryLength-1 && comparator.compare(primary[i], primary[i+1]) == 0) {
                                secondary[secondaryLength++] = primary[++i];
                            }
                        }
                    } else {
                        // did *not* receive a YES decision
                        if (logger.isTraceEnabled()) {
                            logger.trace("No eligible node found to assign shard [{}] decision [{}]", shard, decisionType);
                        }

                        if (minNode != null) {
                            // throttle decision scenario
                            assert decisionType == Type.THROTTLE;
                            final long shardSize = DiskThresholdDecider.getExpectedShardSize(shard, allocation,
                                ShardRouting.UNAVAILABLE_EXPECTED_SHARD_SIZE);
                            minNode.addShard(shard.initialize(minNode.getNodeId(), null, shardSize));
                            final RoutingNode node = minNode.getRoutingNode();
                            final Decision.Type nodeLevelDecision = deciders.canAllocate(node, allocation).type();
                            if (nodeLevelDecision != Type.YES) {
                                if (logger.isTraceEnabled()) {
                                    logger.trace("Can not allocate on node [{}] remove from round decision [{}]", node, decisionType);
                                }
                                assert nodeLevelDecision == Type.NO;
                                throttledNodes.add(minNode);
                            }
                        } else {
                            assert decisionType == Type.NO;
                            if (logger.isTraceEnabled()) {
                                logger.trace("No Node found to assign shard [{}]", shard);
                            }
                        }

                        UnassignedInfo.AllocationStatus allocationStatus = UnassignedInfo.AllocationStatus.fromDecision(decisionType);
                        unassigned.ignoreShard(shard, allocationStatus, allocation.changes());
                        if (!shard.primary()) { // we could not allocate it and we are a replica - check if we can ignore the other replicas
                            while(i < primaryLength-1 && comparator.compare(primary[i], primary[i+1]) == 0) {
                                unassigned.ignoreShard(primary[++i], allocationStatus, allocation.changes());
                            }
                        }
                    }
                }
                primaryLength = secondaryLength;
                ShardRouting[] tmp = primary;
                primary = secondary;
                secondary = tmp;
                secondaryLength = 0;
            } while (primaryLength > 0);
            // clear everything we have either added it or moved to ignoreUnassigned
        }

        /**
         * Make a decision for allocating an unassigned shard.  This method returns a two values in a tuple: the
         * first value is the {@link Decision} taken to allocate the unassigned shard, the second value is the
         * {@link ModelNode} representing the node that the shard should be assigned to.  If the decision returned
         * is of type {@link Type#NO}, then the assigned node will be null.
         */
        private ShardAllocationDecision decideAllocateUnassigned(final ShardRouting shard, final Set<ModelNode> throttledNodes) {
            if (shard.assignedToNode()) {
                // we only make decisions for unassigned shards here
                return ShardAllocationDecision.DECISION_NOT_TAKEN;
            }

            Decision shardLevelDecision = allocation.deciders().canAllocate(shard, allocation);
            if (shardLevelDecision.type() == Type.NO) {
                // NO decision for allocating the shard, irrespective of any particular node, so exit early
                return ShardAllocationDecision.no(shardLevelDecision, explain("cannot allocate shard in its current state"));
            }

            /* find an node with minimal weight we can allocate on*/
            float minWeight = Float.POSITIVE_INFINITY;
            ModelNode minNode = null;
            Decision decision = null;
            final boolean explain = allocation.debugDecision();
            if (throttledNodes.size() >= nodes.size() && explain == false) {
                // all nodes are throttled, so we know we won't be able to allocate this round,
                // so if we are not in explain mode, short circuit
                return ShardAllocationDecision.no(UnassignedInfo.AllocationStatus.DECIDERS_NO, null);
            }
            /* Don't iterate over an identity hashset here the
             * iteration order is different for each run and makes testing hard */
            Map<String, WeightedDecision> nodeExplanationMap = explain ? new HashMap<>() : null;
            for (ModelNode node : nodes.values()) {
                if ((throttledNodes.contains(node) || node.containsShard(shard)) && explain == false) {
                    // decision is NO without needing to check anything further, so short circuit
                    continue;
                }

                // simulate weight if we would add shard to node
                float currentWeight = weight.weightShardAdded(this, node, shard.getIndexName());
                // moving the shard would not improve the balance, and we are not in explain mode, so short circuit
                if (currentWeight > minWeight && explain == false) {
                    continue;
                }

                Decision currentDecision = allocation.deciders().canAllocate(shard, node.getRoutingNode(), allocation);
                if (explain) {
                    nodeExplanationMap.put(node.getNodeId(), new WeightedDecision(currentDecision, currentWeight));
                }
                if (currentDecision.type() == Type.YES || currentDecision.type() == Type.THROTTLE) {
                    final boolean updateMinNode;
                    if (currentWeight == minWeight) {
                        /*  we have an equal weight tie breaking:
                         *  1. if one decision is YES prefer it
                         *  2. prefer the node that holds the primary for this index with the next id in the ring ie.
                         *  for the 3 shards 2 replica case we try to build up:
                         *    1 2 0
                         *    2 0 1
                         *    0 1 2
                         *  such that if we need to tie-break we try to prefer the node holding a shard with the minimal id greater
                         *  than the id of the shard we need to assign. This works find when new indices are created since
                         *  primaries are added first and we only add one shard set a time in this algorithm.
                         */
                        if (currentDecision.type() == decision.type()) {
                            final int repId = shard.id();
                            final int nodeHigh = node.highestPrimary(shard.index().getName());
                            final int minNodeHigh = minNode.highestPrimary(shard.getIndexName());
                            updateMinNode = ((((nodeHigh > repId && minNodeHigh > repId)
                                                   || (nodeHigh < repId && minNodeHigh < repId))
                                                  && (nodeHigh < minNodeHigh))
                                                 || (nodeHigh > minNodeHigh && nodeHigh > repId && minNodeHigh < repId));
                        } else {
                            updateMinNode = currentDecision.type() == Type.YES;
                        }
                    } else {
                        updateMinNode = true;
                    }
                    if (updateMinNode) {
                        minNode = node;
                        minWeight = currentWeight;
                        decision = currentDecision;
                    }
                }
            }
            if (decision == null) {
                // decision was not set and a node was not assigned, so treat it as a NO decision
                decision = Decision.NO;
            }
            return ShardAllocationDecision.fromDecision(
                decision,
                minNode != null ? minNode.getNodeId() : null,
                explain,
                nodeExplanationMap
            );
        }

        // provide an explanation, if in explain mode
        private String explain(String explanation) {
            if (allocation.debugDecision()) {
                return explanation;
            } else {
                return null;
            }
        }

        /**
         * Tries to find a relocation from the max node to the minimal node for an arbitrary shard of the given index on the
         * balance model. Iff this method returns a <code>true</code> the relocation has already been executed on the
         * simulation model as well as on the cluster.
         */
        private boolean tryRelocateShard(ModelNode minNode, ModelNode maxNode, String idx, float minCost) {
            final ModelIndex index = maxNode.getIndex(idx);
            Decision decision = null;
            if (index != null) {
                if (logger.isTraceEnabled()) {
                    logger.trace("Try relocating shard for index index [{}] from node [{}] to node [{}]", idx, maxNode.getNodeId(),
                            minNode.getNodeId());
                }
                ShardRouting candidate = null;
                final AllocationDeciders deciders = allocation.deciders();
                for (ShardRouting shard : index) {
                    if (shard.started()) {
                        // skip initializing, unassigned and relocating shards we can't relocate them anyway
                        Decision allocationDecision = deciders.canAllocate(shard, minNode.getRoutingNode(), allocation);
                        Decision rebalanceDecision = deciders.canRebalance(shard, allocation);
                        if (((allocationDecision.type() == Type.YES) || (allocationDecision.type() == Type.THROTTLE))
                                && ((rebalanceDecision.type() == Type.YES) || (rebalanceDecision.type() == Type.THROTTLE))) {
                            if (maxNode.containsShard(shard)) {
                                // simulate moving shard from maxNode to minNode
                                final float delta = weight.weightShardAdded(this, minNode, idx) - weight.weightShardRemoved(this, maxNode, idx);
                                if (delta < minCost ||
                                        (candidate != null && delta == minCost && candidate.id() > shard.id())) {
                                    /* this last line is a tie-breaker to make the shard allocation alg deterministic
                                     * otherwise we rely on the iteration order of the index.getAllShards() which is a set.*/
                                    minCost = delta;
                                    candidate = shard;
                                    decision = new Decision.Multi().add(allocationDecision).add(rebalanceDecision);
                                }
                            }
                        }
                    }
                }

                if (candidate != null) {
                    /* allocate on the model even if not throttled */
                    maxNode.removeShard(candidate);
                    long shardSize = allocation.clusterInfo().getShardSize(candidate, ShardRouting.UNAVAILABLE_EXPECTED_SHARD_SIZE);

                    if (decision.type() == Type.YES) { /* only allocate on the cluster if we are not throttled */
                        logger.debug("Relocate shard [{}] from node [{}] to node [{}]", candidate, maxNode.getNodeId(),
                                    minNode.getNodeId());
                        /* now allocate on the cluster */
                        minNode.addShard(routingNodes.relocateShard(candidate, minNode.getNodeId(), shardSize, allocation.changes()).v1());
                        return true;
                    } else {
                        assert decision.type() == Type.THROTTLE;
                        minNode.addShard(candidate.relocate(minNode.getNodeId(), shardSize));
                    }
                }
            }
            if (logger.isTraceEnabled()) {
                logger.trace("Couldn't find shard to relocate from node [{}] to node [{}] allocation decision [{}]", maxNode.getNodeId(),
                        minNode.getNodeId(), decision == null ? "NO" : decision.type().name());
            }
            return false;
        }

    }

    static class ModelNode implements Iterable<ModelIndex> {
        private final Map<String, ModelIndex> indices = new HashMap<>();
        private int numShards = 0;
        private final RoutingNode routingNode;

        public ModelNode(RoutingNode routingNode) {
            this.routingNode = routingNode;
        }

        public ModelIndex getIndex(String indexId) {
            return indices.get(indexId);
        }

        public String getNodeId() {
            return routingNode.nodeId();
        }

        public RoutingNode getRoutingNode() {
            return routingNode;
        }

        public int numShards() {
            return numShards;
        }

        public int numShards(String idx) {
            ModelIndex index = indices.get(idx);
            return index == null ? 0 : index.numShards();
        }

        public int highestPrimary(String index) {
            ModelIndex idx = indices.get(index);
            if (idx != null) {
                return idx.highestPrimary();
            }
            return -1;
        }

        public void addShard(ShardRouting shard) {
            ModelIndex index = indices.get(shard.getIndexName());
            if (index == null) {
                index = new ModelIndex(shard.getIndexName());
                indices.put(index.getIndexId(), index);
            }
            index.addShard(shard);
            numShards++;
        }

        public void removeShard(ShardRouting shard) {
            ModelIndex index = indices.get(shard.getIndexName());
            if (index != null) {
                index.removeShard(shard);
                if (index.numShards() == 0) {
                    indices.remove(shard.getIndexName());
                }
            }
            numShards--;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Node(").append(routingNode.nodeId()).append(")");
            return sb.toString();
        }

        @Override
        public Iterator<ModelIndex> iterator() {
            return indices.values().iterator();
        }

        public boolean containsShard(ShardRouting shard) {
            ModelIndex index = getIndex(shard.getIndexName());
            return index == null ? false : index.containsShard(shard);
        }

    }

    static final class ModelIndex implements Iterable<ShardRouting> {
        private final String id;
        private final Set<ShardRouting> shards = new HashSet<>(4); // expect few shards of same index to be allocated on same node
        private int highestPrimary = -1;

        public ModelIndex(String id) {
            this.id = id;
        }

        public int highestPrimary() {
            if (highestPrimary == -1) {
                int maxId = -1;
                for (ShardRouting shard : shards) {
                    if (shard.primary()) {
                        maxId = Math.max(maxId, shard.id());
                    }
                }
                return highestPrimary = maxId;
            }
            return highestPrimary;
        }

        public String getIndexId() {
            return id;
        }

        public int numShards() {
            return shards.size();
        }

        @Override
        public Iterator<ShardRouting> iterator() {
            return shards.iterator();
        }

        public void removeShard(ShardRouting shard) {
            highestPrimary = -1;
            assert shards.contains(shard) : "Shard not allocated on current node: " + shard;
            shards.remove(shard);
        }

        public void addShard(ShardRouting shard) {
            highestPrimary = -1;
            assert !shards.contains(shard) : "Shard already allocated on current node: " + shard;
            shards.add(shard);
        }

        public boolean containsShard(ShardRouting shard) {
            return shards.contains(shard);
        }
    }

    static final class NodeSorter extends IntroSorter {

        final ModelNode[] modelNodes;
        /* the nodes weights with respect to the current weight function / index */
        final float[] weights;
        private final WeightFunction function;
        private String index;
        private final Balancer balancer;
        private float pivotWeight;

        public NodeSorter(ModelNode[] modelNodes, WeightFunction function, Balancer balancer) {
            this.function = function;
            this.balancer = balancer;
            this.modelNodes = modelNodes;
            weights = new float[modelNodes.length];
        }

        /**
         * Resets the sorter, recalculates the weights per node and sorts the
         * nodes by weight, with minimal weight first.
         */
        public void reset(String index, int from, int to) {
            this.index = index;
            for (int i = from; i < to; i++) {
                weights[i] = weight(modelNodes[i]);
            }
            sort(from, to);
        }

        public void reset(String index) {
            reset(index, 0, modelNodes.length);
        }

        public float weight(ModelNode node) {
            return function.weight(balancer, node, index);
        }

        @Override
        protected void swap(int i, int j) {
            final ModelNode tmpNode = modelNodes[i];
            modelNodes[i] = modelNodes[j];
            modelNodes[j] = tmpNode;
            final float tmpWeight = weights[i];
            weights[i] = weights[j];
            weights[j] = tmpWeight;
        }

        @Override
        protected int compare(int i, int j) {
            return Float.compare(weights[i], weights[j]);
        }

        @Override
        protected void setPivot(int i) {
            pivotWeight = weights[i];
        }

        @Override
        protected int comparePivot(int j) {
            return Float.compare(pivotWeight, weights[j]);
        }

        public float delta() {
            return weights[weights.length - 1] - weights[0];
        }
    }

    /**
     * Represents a decision to relocate a started shard from its current node.
     */
    public abstract static class RelocationDecision {
        @Nullable
        private final Type finalDecision;
        @Nullable
        private final String finalExplanation;
        @Nullable
        private final String assignedNodeId;
        @Nullable
        private final Map<String, WeightedDecision> nodeDecisions;

        protected RelocationDecision(Type finalDecision, String finalExplanation, String assignedNodeId,
                                     Map<String, WeightedDecision> nodeDecisions) {
            this.finalDecision = finalDecision;
            this.finalExplanation = finalExplanation;
            this.assignedNodeId = assignedNodeId;
            this.nodeDecisions = nodeDecisions;
        }

        /**
         * Returns {@code true} if a decision was taken by the allocator, {@code false} otherwise.
         * If no decision was taken, then the rest of the fields in this object are meaningless and return {@code null}.
         */
        public boolean isDecisionTaken() {
            return finalDecision != null;
        }

        /**
         * Returns the final decision made by the allocator on whether to assign the shard, and
         * {@code null} if no decision was taken.
         */
        public Type getFinalDecisionType() {
            return finalDecision;
        }

        /**
         * Returns the free-text explanation for the reason behind the decision taken in {@link #getFinalDecisionType()}.
         */
        @Nullable
        public String getFinalExplanation() {
            return finalExplanation;
        }

        /**
         * Get the node id that the allocator will assign the shard to, unless {@link #getFinalDecisionType()} returns
         * a value other than {@link Decision.Type#YES}, in which case this returns {@code null}.
         */
        @Nullable
        public String getAssignedNodeId() {
            return assignedNodeId;
        }

        /**
         * Gets the individual node-level decisions that went into making the final decision as represented by
         * {@link #getFinalDecisionType()}.  The map that is returned has the node id as the key and a {@link WeightedDecision}.
         */
        @Nullable
        public Map<String, WeightedDecision> getNodeDecisions() {
            return nodeDecisions;
        }
    }

    /**
     * Represents a decision to move a started shard because it is no longer allowed to remain on its current node.
     */
    public static final class MoveDecision extends RelocationDecision {
        /** a constant representing no decision taken */
        public static final MoveDecision DECISION_NOT_TAKEN = new MoveDecision(null, null, null, null, null);
        /** cached decisions so we don't have to recreate objects for common decisions when not in explain mode. */
        private static final MoveDecision CACHED_STAY_DECISION = new MoveDecision(Decision.YES, Type.NO, null, null, null);
        private static final MoveDecision CACHED_CANNOT_MOVE_DECISION = new MoveDecision(Decision.NO, Type.NO, null, null, null);

        @Nullable
        private final Decision canRemainDecision;

        private MoveDecision(Decision canRemainDecision, Type finalDecision, String finalExplanation,
                             String assignedNodeId, Map<String, WeightedDecision> nodeDecisions) {
            super(finalDecision, finalExplanation, assignedNodeId, nodeDecisions);
            this.canRemainDecision = canRemainDecision;
        }

        /**
         * Creates a move decision for the shard being able to remain on its current node, so not moving.
         */
        public static MoveDecision stay(Decision canRemainDecision, boolean explain) {
            assert canRemainDecision.type() != Type.NO;
            if (explain) {
                final String explanation;
                if (explain) {
                    explanation = "shard is allowed to remain on its current node, so no reason to move";
                } else {
                    explanation = null;
                }
                return new MoveDecision(Objects.requireNonNull(canRemainDecision), Type.NO, explanation, null, null);
            } else {
                return CACHED_STAY_DECISION;
            }
        }

        /**
         * Creates a move decision for the shard not being able to remain on its current node.
         *
         * @param canRemainDecision the decision for whether the shard is allowed to remain on its current node
         * @param finalDecision the decision of whether to move the shard to another node
         * @param explain true if in explain mode
         * @param currentNodeId the current node id where the shard is assigned
         * @param assignedNodeId the node id for where the shard can move to
         * @param nodeDecisions the node-level decisions that comprised the final decision, non-null iff explain is true
         * @return the {@link MoveDecision} for moving the shard to another node
         */
        public static MoveDecision decision(Decision canRemainDecision, Type finalDecision, boolean explain, String currentNodeId,
                                            String assignedNodeId, Map<String, WeightedDecision> nodeDecisions) {
            assert canRemainDecision != null;
            assert canRemainDecision.type() != Type.YES : "create decision with MoveDecision#stay instead";
            String finalExplanation = null;
            if (explain) {
                assert currentNodeId != null;
                if (finalDecision == Type.YES) {
                    assert assignedNodeId != null;
                    finalExplanation = "shard cannot remain on node [" + currentNodeId + "], moving to node [" + assignedNodeId + "]";
                } else if (finalDecision == Type.THROTTLE) {
                    finalExplanation = "shard cannot remain on node [" + currentNodeId + "], throttled on moving to another node";
                } else {
                    finalExplanation = "shard cannot remain on node [" + currentNodeId + "], but cannot be assigned to any other node";
                }
            }
            if (finalExplanation == null && finalDecision == Type.NO) {
                // the final decision is NO (no node to move the shard to) and we are not in explain mode, return a cached version
                return CACHED_CANNOT_MOVE_DECISION;
            } else {
                assert ((assignedNodeId == null) == (finalDecision != Type.YES));
                return new MoveDecision(canRemainDecision, finalDecision, finalExplanation, assignedNodeId, nodeDecisions);
            }
        }

        /**
         * Returns {@code true} if the shard cannot remain on its current node and can be moved, returns {@code false} otherwise.
         */
        public boolean move() {
            return cannotRemain() && getFinalDecisionType() == Type.YES;
        }

        /**
         * Returns {@code true} if the shard cannot remain on its current node.
         */
        public boolean cannotRemain() {
            return isDecisionTaken() && canRemainDecision.type() == Type.NO;
        }
    }

}
