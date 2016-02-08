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
package org.elasticsearch.search.aggregations;

import org.elasticsearch.action.support.ToXContentToBytes;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregatorFactory;
import org.elasticsearch.search.aggregations.support.AggregationContext;
import org.elasticsearch.search.aggregations.support.AggregationPath;
import org.elasticsearch.search.aggregations.support.AggregationPath.PathElement;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 *
 */
public class AggregatorFactories {

    public static final AggregatorFactories EMPTY = new AggregatorFactories(null, new AggregatorFactory<?>[0],
            new ArrayList<PipelineAggregatorFactory>());

    private AggregatorFactory<?> parent;
    private AggregatorFactory<?>[] factories;
    private List<PipelineAggregatorFactory> pipelineAggregatorFactories;

    public static Builder builder() {
        return new Builder();
    }

    private AggregatorFactories(AggregatorFactory<?> parent, AggregatorFactory<?>[] factories,
            List<PipelineAggregatorFactory> pipelineAggregators) {
        this.parent = parent;
        this.factories = factories;
        this.pipelineAggregatorFactories = pipelineAggregators;
    }

    public List<PipelineAggregator> createPipelineAggregators() throws IOException {
        List<PipelineAggregator> pipelineAggregators = new ArrayList<>();
        for (PipelineAggregatorFactory factory : this.pipelineAggregatorFactories) {
            pipelineAggregators.add(factory.create());
        }
        return pipelineAggregators;
    }

    /**
     * Create all aggregators so that they can be consumed with multiple
     * buckets.
     */
    public Aggregator[] createSubAggregators(Aggregator parent) throws IOException {
        Aggregator[] aggregators = new Aggregator[countAggregators()];
        for (int i = 0; i < factories.length; ++i) {
            // TODO: sometimes even sub aggregations always get called with bucket 0, eg. if
            // you have a terms agg under a top-level filter agg. We should have a way to
            // propagate the fact that only bucket 0 will be collected with single-bucket
            // aggs
            final boolean collectsFromSingleBucket = false;
            aggregators[i] = factories[i].create(parent, collectsFromSingleBucket);
        }
        return aggregators;
    }

    public Aggregator[] createTopLevelAggregators() throws IOException {
        // These aggregators are going to be used with a single bucket ordinal, no need to wrap the PER_BUCKET ones
        Aggregator[] aggregators = new Aggregator[factories.length];
        for (int i = 0; i < factories.length; i++) {
            // top-level aggs only get called with bucket 0
            final boolean collectsFromSingleBucket = true;
            aggregators[i] = factories[i].create(null, collectsFromSingleBucket);
        }
        return aggregators;
    }

    /**
     * @return the number of sub-aggregator factories not including pipeline
     *         aggregator factories
     */
    public int countAggregators() {
        return factories.length;
    }

    /**
     * @return the number of pipeline aggregator factories
     */
    public int countPipelineAggregators() {
        return pipelineAggregatorFactories.size();
    }

    public void validate() {
        for (AggregatorFactory<?> factory : factories) {
            factory.validate();
        }
        for (PipelineAggregatorFactory factory : pipelineAggregatorFactories) {
            factory.validate(parent, factories, pipelineAggregatorFactories);
        }
    }

    public static class Builder extends ToXContentToBytes implements Writeable<Builder> {

        public final static Builder PROTOTYPE = new Builder();

        private final Set<String> names = new HashSet<>();
        private final List<AggregatorBuilder<?>> aggregatorBuilders = new ArrayList<>();
        private final List<PipelineAggregatorFactory> pipelineAggregatorFactories = new ArrayList<>();
        private boolean skipResolveOrder;

        public Builder addAggregators(AggregatorFactories factories) {
            throw new UnsupportedOperationException("This needs to be removed");
        }

        public Builder addAggregator(AggregatorBuilder<?> factory) {
            if (!names.add(factory.name)) {
                throw new IllegalArgumentException("Two sibling aggregations cannot have the same name: [" + factory.name + "]");
            }
            aggregatorBuilders.add(factory);
            return this;
        }

        public Builder addPipelineAggregator(PipelineAggregatorFactory pipelineAggregatorFactory) {
            this.pipelineAggregatorFactories.add(pipelineAggregatorFactory);
            return this;
        }

        /**
         * FOR TESTING ONLY
         */
        Builder skipResolveOrder() {
            this.skipResolveOrder = true;
            return this;
        }

        public AggregatorFactories build(AggregationContext context, AggregatorFactory<?> parent) throws IOException {
            if (aggregatorBuilders.isEmpty() && pipelineAggregatorFactories.isEmpty()) {
                return EMPTY;
            }
            List<PipelineAggregatorFactory> orderedpipelineAggregators = null;
            if (skipResolveOrder) {
                orderedpipelineAggregators = new ArrayList<>(pipelineAggregatorFactories);
            } else {
                orderedpipelineAggregators = resolvePipelineAggregatorOrder(this.pipelineAggregatorFactories, this.aggregatorBuilders);
            }
            AggregatorFactory<?>[] aggFactories = new AggregatorFactory<?>[aggregatorBuilders.size()];
            for (int i = 0; i < aggregatorBuilders.size(); i++) {
                aggFactories[i] = aggregatorBuilders.get(i).build(context, parent);
            }
            return new AggregatorFactories(parent, aggFactories, orderedpipelineAggregators);
        }

        private List<PipelineAggregatorFactory> resolvePipelineAggregatorOrder(List<PipelineAggregatorFactory> pipelineAggregatorFactories,
                List<AggregatorBuilder<?>> aggFactories) {
            Map<String, PipelineAggregatorFactory> pipelineAggregatorFactoriesMap = new HashMap<>();
            for (PipelineAggregatorFactory factory : pipelineAggregatorFactories) {
                pipelineAggregatorFactoriesMap.put(factory.getName(), factory);
            }
            Map<String, AggregatorBuilder<?>> aggFactoriesMap = new HashMap<>();
            for (AggregatorBuilder<?> aggFactory : aggFactories) {
                aggFactoriesMap.put(aggFactory.name, aggFactory);
            }
            List<PipelineAggregatorFactory> orderedPipelineAggregatorrs = new LinkedList<>();
            List<PipelineAggregatorFactory> unmarkedFactories = new ArrayList<PipelineAggregatorFactory>(pipelineAggregatorFactories);
            Set<PipelineAggregatorFactory> temporarilyMarked = new HashSet<PipelineAggregatorFactory>();
            while (!unmarkedFactories.isEmpty()) {
                PipelineAggregatorFactory factory = unmarkedFactories.get(0);
                resolvePipelineAggregatorOrder(aggFactoriesMap, pipelineAggregatorFactoriesMap, orderedPipelineAggregatorrs,
                        unmarkedFactories, temporarilyMarked, factory);
            }
            return orderedPipelineAggregatorrs;
        }

        private void resolvePipelineAggregatorOrder(Map<String, AggregatorBuilder<?>> aggFactoriesMap,
                Map<String, PipelineAggregatorFactory> pipelineAggregatorFactoriesMap,
                List<PipelineAggregatorFactory> orderedPipelineAggregators, List<PipelineAggregatorFactory> unmarkedFactories, Set<PipelineAggregatorFactory> temporarilyMarked,
                PipelineAggregatorFactory factory) {
            if (temporarilyMarked.contains(factory)) {
                throw new IllegalArgumentException("Cyclical dependancy found with pipeline aggregator [" + factory.getName() + "]");
            } else if (unmarkedFactories.contains(factory)) {
                temporarilyMarked.add(factory);
                String[] bucketsPaths = factory.getBucketsPaths();
                for (String bucketsPath : bucketsPaths) {
                    List<AggregationPath.PathElement> bucketsPathElements = AggregationPath.parse(bucketsPath).getPathElements();
                    String firstAggName = bucketsPathElements.get(0).name;
                    if (bucketsPath.equals("_count") || bucketsPath.equals("_key")) {
                        continue;
                    } else if (aggFactoriesMap.containsKey(firstAggName)) {
                        AggregatorBuilder<?> aggFactory = aggFactoriesMap.get(firstAggName);
                        for (int i = 1; i < bucketsPathElements.size(); i++) {
                            PathElement pathElement = bucketsPathElements.get(i);
                            String aggName = pathElement.name;
                            if ((i == bucketsPathElements.size() - 1) && (aggName.equalsIgnoreCase("_key") || aggName.equals("_count"))) {
                                break;
                            } else {
                                // Check the non-pipeline sub-aggregator
                                // factories
                                AggregatorBuilder<?>[] subFactories = aggFactory.factoriesBuilder.getAggregatorFactories();
                                boolean foundSubFactory = false;
                                for (AggregatorBuilder<?> subFactory : subFactories) {
                                    if (aggName.equals(subFactory.name)) {
                                        aggFactory = subFactory;
                                        foundSubFactory = true;
                                        break;
                                    }
                                }
                                // Check the pipeline sub-aggregator factories
                                if (!foundSubFactory && (i == bucketsPathElements.size() - 1)) {
                                    List<PipelineAggregatorFactory> subPipelineFactories = aggFactory.factoriesBuilder.pipelineAggregatorFactories;
                                    for (PipelineAggregatorFactory subFactory : subPipelineFactories) {
                                        if (aggName.equals(subFactory.name())) {
                                            foundSubFactory = true;
                                            break;
                                        }
                                    }
                                }
                                if (!foundSubFactory) {
                                    throw new IllegalArgumentException("No aggregation [" + aggName + "] found for path [" + bucketsPath
                                            + "]");
                                }
                            }
                        }
                        continue;
                    } else {
                        PipelineAggregatorFactory matchingFactory = pipelineAggregatorFactoriesMap.get(firstAggName);
                        if (matchingFactory != null) {
                            resolvePipelineAggregatorOrder(aggFactoriesMap, pipelineAggregatorFactoriesMap, orderedPipelineAggregators,
                                    unmarkedFactories,
                                    temporarilyMarked, matchingFactory);
                        } else {
                            throw new IllegalArgumentException("No aggregation found for path [" + bucketsPath + "]");
                        }
                    }
                }
                unmarkedFactories.remove(factory);
                temporarilyMarked.remove(factory);
                orderedPipelineAggregators.add(factory);
            }
        }

        AggregatorBuilder<?>[] getAggregatorFactories() {
            return this.aggregatorBuilders.toArray(new AggregatorBuilder<?>[this.aggregatorBuilders.size()]);
        }

        List<PipelineAggregatorFactory> getPipelineAggregatorFactories() {
            return this.pipelineAggregatorFactories;
        }

        public int count() {
            return aggregatorBuilders.size() + pipelineAggregatorFactories.size();
        }

        @Override
        public Builder readFrom(StreamInput in) throws IOException {
            Builder builder = new Builder();
            int factoriesSize = in.readVInt();
            for (int i = 0; i < factoriesSize; i++) {
                AggregatorBuilder<?> factory = in.readAggregatorFactory();
                builder.addAggregator(factory);
            }
            int pipelineFactoriesSize = in.readVInt();
            for (int i = 0; i < pipelineFactoriesSize; i++) {
                PipelineAggregatorFactory factory = in.readPipelineAggregatorFactory();
                builder.addPipelineAggregator(factory);
            }
            return builder;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeVInt(this.aggregatorBuilders.size());
            for (AggregatorBuilder<?> factory : aggregatorBuilders) {
                out.writeAggregatorFactory(factory);
            }
            out.writeVInt(this.pipelineAggregatorFactories.size());
            for (PipelineAggregatorFactory factory : pipelineAggregatorFactories) {
                out.writePipelineAggregatorFactory(factory);
            }
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            if (aggregatorBuilders != null) {
                for (AggregatorBuilder<?> subAgg : aggregatorBuilders) {
                    subAgg.toXContent(builder, params);
                }
            }
            if (pipelineAggregatorFactories != null) {
                for (PipelineAggregatorFactory subAgg : pipelineAggregatorFactories) {
                    subAgg.toXContent(builder, params);
                }
            }
            builder.endObject();
            return builder;
        }

        @Override
        public int hashCode() {
            return Objects.hash(aggregatorBuilders, pipelineAggregatorFactories);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            Builder other = (Builder) obj;
            if (!Objects.equals(aggregatorBuilders, other.aggregatorBuilders))
                return false;
            if (!Objects.equals(pipelineAggregatorFactories, other.pipelineAggregatorFactories))
                return false;
            return true;
        }
    }
}
