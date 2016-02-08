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
package org.elasticsearch.search.aggregations.bucket.nested;

import com.carrotsearch.hppc.LongIntHashMap;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.util.BitSet;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.mapper.object.ObjectMapper;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.AggregatorFactories;
import org.elasticsearch.search.aggregations.AggregatorFactory;
import org.elasticsearch.search.aggregations.AggregatorBuilder;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.LeafBucketCollector;
import org.elasticsearch.search.aggregations.LeafBucketCollectorBase;
import org.elasticsearch.search.aggregations.bucket.SingleBucketAggregator;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;
import org.elasticsearch.search.aggregations.support.AggregationContext;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 *
 */
public class ReverseNestedAggregator extends SingleBucketAggregator {

    static final ParseField PATH_FIELD = new ParseField("path");

    private final Query parentFilter;
    private final BitSetProducer parentBitsetProducer;

    public ReverseNestedAggregator(String name, AggregatorFactories factories, ObjectMapper objectMapper,
            AggregationContext aggregationContext, Aggregator parent, List<PipelineAggregator> pipelineAggregators, Map<String, Object> metaData)
            throws IOException {
        super(name, factories, aggregationContext, parent, pipelineAggregators, metaData);
        if (objectMapper == null) {
            parentFilter = Queries.newNonNestedFilter();
        } else {
            parentFilter = objectMapper.nestedTypeFilter();
        }
        parentBitsetProducer = context.searchContext().bitsetFilterCache().getBitSetProducer(parentFilter);
    }

    @Override
    protected LeafBucketCollector getLeafCollector(LeafReaderContext ctx, final LeafBucketCollector sub) throws IOException {
        // In ES if parent is deleted, then also the children are deleted, so the child docs this agg receives
        // must belong to parent docs that is alive. For this reason acceptedDocs can be null here.
        final BitSet parentDocs = parentBitsetProducer.getBitSet(ctx);
        if (parentDocs == null) {
            return LeafBucketCollector.NO_OP_COLLECTOR;
        }
        final LongIntHashMap bucketOrdToLastCollectedParentDoc = new LongIntHashMap(32);
        return new LeafBucketCollectorBase(sub, null) {
            @Override
            public void collect(int childDoc, long bucket) throws IOException {
                // fast forward to retrieve the parentDoc this childDoc belongs to
                final int parentDoc = parentDocs.nextSetBit(childDoc);
                assert childDoc <= parentDoc && parentDoc != DocIdSetIterator.NO_MORE_DOCS;

                int keySlot = bucketOrdToLastCollectedParentDoc.indexOf(bucket);
                if (bucketOrdToLastCollectedParentDoc.indexExists(keySlot)) {
                    int lastCollectedParentDoc = bucketOrdToLastCollectedParentDoc.indexGet(keySlot);
                    if (parentDoc > lastCollectedParentDoc) {
                        collectBucket(sub, parentDoc, bucket);
                        bucketOrdToLastCollectedParentDoc.indexReplace(keySlot, parentDoc);
                    }
                } else {
                    collectBucket(sub, parentDoc, bucket);
                    bucketOrdToLastCollectedParentDoc.indexInsert(keySlot, bucket, parentDoc);
                }
            }
        };
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrdinal) throws IOException {
        return new InternalReverseNested(name, bucketDocCount(owningBucketOrdinal), bucketAggregations(owningBucketOrdinal), pipelineAggregators(),
                metaData());
    }

    @Override
    public InternalAggregation buildEmptyAggregation() {
        return new InternalReverseNested(name, 0, buildEmptySubAggregations(), pipelineAggregators(), metaData());
    }

    Query getParentFilter() {
        return parentFilter;
    }

    public static class ReverseNestedAggregatorBuilder extends AggregatorBuilder<ReverseNestedAggregatorBuilder> {

        private String path;

        public ReverseNestedAggregatorBuilder(String name) {
            super(name, InternalReverseNested.TYPE);
        }

        /**
         * Set the path to use for this nested aggregation. The path must match
         * the path to a nested object in the mappings. If it is not specified
         * then this aggregation will go back to the root document.
         */
        public ReverseNestedAggregatorBuilder path(String path) {
            this.path = path;
            return this;
        }

        /**
         * Get the path to use for this nested aggregation.
         */
        public String path() {
            return path;
        }

        @Override
        protected AggregatorFactory<?> doBuild(AggregationContext context) throws IOException {
            return new ReverseNestedAggregatorFactory(name, type, path);
        }

        @Override
        protected XContentBuilder internalXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            if (path != null) {
                builder.field(PATH_FIELD.getPreferredName(), path);
            }
            builder.endObject();
            return builder;
        }

        @Override
        protected ReverseNestedAggregatorBuilder doReadFrom(String name, StreamInput in) throws IOException {
            ReverseNestedAggregatorBuilder factory = new ReverseNestedAggregatorBuilder(name);
            factory.path = in.readOptionalString();
            return factory;
        }

        @Override
        protected void doWriteTo(StreamOutput out) throws IOException {
            out.writeOptionalString(path);
        }

        @Override
        protected int doHashCode() {
            return Objects.hash(path);
        }

        @Override
        protected boolean doEquals(Object obj) {
            ReverseNestedAggregatorBuilder other = (ReverseNestedAggregatorBuilder) obj;
            return Objects.equals(path, other.path);
        }
    }
}
