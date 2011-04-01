/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

package org.elasticsearch.search.facet.terms.shorts;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.util.PriorityQueue;
import org.elasticsearch.ElasticSearchIllegalArgumentException;
import org.elasticsearch.common.CacheRecycler;
import org.elasticsearch.common.collect.BoundedTreeSet;
import org.elasticsearch.common.collect.ImmutableSet;
import org.elasticsearch.common.trove.set.hash.TShortHashSet;
import org.elasticsearch.index.cache.field.data.FieldDataCache;
import org.elasticsearch.index.field.data.FieldData;
import org.elasticsearch.index.field.data.FieldDataType;
import org.elasticsearch.index.field.data.shorts.ShortFieldData;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.search.facet.AbstractFacetCollector;
import org.elasticsearch.search.facet.Facet;
import org.elasticsearch.search.facet.terms.TermsFacet;
import org.elasticsearch.search.facet.terms.support.EntryPriorityQueue;
import org.elasticsearch.search.internal.SearchContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author kimchy (shay.banon)
 */
public class TermsShortOrdinalsFacetCollector extends AbstractFacetCollector {

    private final FieldDataCache fieldDataCache;

    private final String indexFieldName;

    private final TermsFacet.ComparatorType comparatorType;

    private final int size;

    private final int numberOfShards;

    private final int minCount;

    private final FieldDataType fieldDataType;

    private ShortFieldData fieldData;

    private final List<ReaderAggregator> aggregators;

    private ReaderAggregator current;

    long missing;

    private final TShortHashSet excluded;

    public TermsShortOrdinalsFacetCollector(String facetName, String fieldName, int size, TermsFacet.ComparatorType comparatorType, boolean allTerms, SearchContext context,
                                            ImmutableSet<String> excluded) {
        super(facetName);
        this.fieldDataCache = context.fieldDataCache();
        this.size = size;
        this.comparatorType = comparatorType;
        this.numberOfShards = context.numberOfShards();

        MapperService.SmartNameFieldMappers smartMappers = context.mapperService().smartName(fieldName);
        if (smartMappers == null || !smartMappers.hasMapper()) {
            throw new ElasticSearchIllegalArgumentException("Field [" + fieldName + "] doesn't have a type, can't run terms short facet collector on it");
        } else {
            // add type filter if there is exact doc mapper associated with it
            if (smartMappers.hasDocMapper()) {
                setFilter(context.filterCache().cache(smartMappers.docMapper().typeFilter()));
            }

            if (smartMappers.mapper().fieldDataType() != FieldDataType.DefaultTypes.SHORT) {
                throw new ElasticSearchIllegalArgumentException("Field [" + fieldName + "] is not of short type, can't run terms short facet collector on it");
            }

            this.indexFieldName = smartMappers.mapper().names().indexName();
            this.fieldDataType = smartMappers.mapper().fieldDataType();
        }

        if (excluded == null || excluded.isEmpty()) {
            this.excluded = null;
        } else {
            this.excluded = new TShortHashSet(excluded.size());
            for (String s : excluded) {
                this.excluded.add(Short.parseShort(s));
            }
        }

        // minCount is offset by -1
        if (allTerms) {
            minCount = -1;
        } else {
            minCount = 0;
        }

        this.aggregators = new ArrayList<ReaderAggregator>(context.searcher().subReaders().length);
    }

    @Override protected void doSetNextReader(IndexReader reader, int docBase) throws IOException {
        if (current != null) {
            missing += current.counts[0];
            if (current.values.length > 1) {
                aggregators.add(current);
            }
        }
        fieldData = (ShortFieldData) fieldDataCache.cache(fieldDataType, reader, indexFieldName);
        current = new ReaderAggregator(fieldData);
    }

    @Override protected void doCollect(int doc) throws IOException {
        fieldData.forEachOrdinalInDoc(doc, current);
    }

    @Override public Facet facet() {
        if (current != null) {
            missing += current.counts[0];
            // if we have values for this one, add it
            if (current.values.length > 1) {
                aggregators.add(current);
            }
        }

        AggregatorPriorityQueue queue = new AggregatorPriorityQueue(aggregators.size());

        for (ReaderAggregator aggregator : aggregators) {
            CacheRecycler.pushIntArray(aggregator.counts); // release it here, anyhow we are on the same thread so won't be corrupted
            if (aggregator.nextPosition()) {
                queue.add(aggregator);
            }
        }

        // YACK, we repeat the same logic, but once with an optimizer priority queue for smaller sizes
        if (size < EntryPriorityQueue.LIMIT) {
            // optimize to use priority size
            EntryPriorityQueue ordered = new EntryPriorityQueue(size, comparatorType.comparator());

            while (queue.size() > 0) {
                ReaderAggregator agg = queue.top();
                short value = agg.current;
                int count = 0;
                do {
                    count += agg.counts[agg.position];
                    if (agg.nextPosition()) {
                        agg = queue.updateTop();
                    } else {
                        // we are done with this reader
                        queue.pop();
                        agg = queue.top();
                    }
                } while (agg != null && value == agg.current);

                if (count > minCount) {
                    if (excluded == null || !excluded.contains(value)) {
                        InternalShortTermsFacet.ShortEntry entry = new InternalShortTermsFacet.ShortEntry(value, count);
                        ordered.insertWithOverflow(entry);
                    }
                }
            }
            InternalShortTermsFacet.ShortEntry[] list = new InternalShortTermsFacet.ShortEntry[ordered.size()];
            for (int i = ordered.size() - 1; i >= 0; i--) {
                list[i] = (InternalShortTermsFacet.ShortEntry) ordered.pop();
            }
            return new InternalShortTermsFacet(facetName, comparatorType, size, Arrays.asList(list), missing);
        }

        BoundedTreeSet<InternalShortTermsFacet.ShortEntry> ordered = new BoundedTreeSet<InternalShortTermsFacet.ShortEntry>(comparatorType.comparator(), size);

        while (queue.size() > 0) {
            ReaderAggregator agg = queue.top();
            short value = agg.current;
            int count = 0;
            do {
                count += agg.counts[agg.position];
                if (agg.nextPosition()) {
                    agg = queue.updateTop();
                } else {
                    // we are done with this reader
                    queue.pop();
                    agg = queue.top();
                }
            } while (agg != null && value == agg.current);

            if (count > minCount) {
                if (excluded == null || !excluded.contains(value)) {
                    InternalShortTermsFacet.ShortEntry entry = new InternalShortTermsFacet.ShortEntry(value, count);
                    ordered.add(entry);
                }
            }
        }
        return new InternalShortTermsFacet(facetName, comparatorType, size, ordered, missing);
    }

    public static class ReaderAggregator implements FieldData.OrdinalInDocProc {

        final short[] values;
        final int[] counts;

        int position = 0;
        short current;

        public ReaderAggregator(ShortFieldData fieldData) {
            this.values = fieldData.values();
            this.counts = CacheRecycler.popIntArray(fieldData.values().length);
        }

        @Override public void onOrdinal(int docId, int ordinal) {
            counts[ordinal]++;
        }

        public boolean nextPosition() {
            if (++position >= values.length) {
                return false;
            }
            current = values[position];
            return true;
        }
    }

    public static class AggregatorPriorityQueue extends PriorityQueue<ReaderAggregator> {

        public AggregatorPriorityQueue(int size) {
            initialize(size);
        }

        @Override protected boolean lessThan(ReaderAggregator a, ReaderAggregator b) {
            return a.current < b.current;
        }
    }
}
