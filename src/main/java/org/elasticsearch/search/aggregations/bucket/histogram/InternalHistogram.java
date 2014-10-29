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
package org.elasticsearch.search.aggregations.bucket.histogram;

import com.carrotsearch.hppc.LongObjectOpenHashMap;
import com.google.common.collect.Lists;
import org.apache.lucene.util.CollectionUtil;
import org.elasticsearch.Version;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.rounding.Rounding;
import org.elasticsearch.common.text.StringText;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.common.util.LongObjectPagedHashMap;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.search.aggregations.AggregationStreams;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.InternalAggregations;
import org.elasticsearch.search.aggregations.bucket.BucketStreamContext;
import org.elasticsearch.search.aggregations.bucket.BucketStreams;
import org.elasticsearch.search.aggregations.support.format.ValueFormatter;
import org.elasticsearch.search.aggregations.support.format.ValueFormatterStreams;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

/**
 * TODO should be renamed to InternalNumericHistogram (see comment on {@link Histogram})?
 */
public class InternalHistogram<B extends InternalHistogram.Bucket> extends InternalAggregation implements Histogram {

    final static Type TYPE = new Type("histogram", "histo");
    final static Factory FACTORY = new Factory();

    private final static AggregationStreams.Stream STREAM = new AggregationStreams.Stream() {
        @Override
        public InternalHistogram readResult(StreamInput in) throws IOException {
            InternalHistogram histogram = new InternalHistogram();
            histogram.readFrom(in);
            return histogram;
        }
    };

    private final static BucketStreams.Stream<Bucket> BUCKET_STREAM = new BucketStreams.Stream<Bucket>() {
        @Override
        public Bucket readResult(StreamInput in, BucketStreamContext context) throws IOException {
            Bucket histogram = new Bucket(context.keyed(), context.formatter());
            histogram.readFrom(in);
            return histogram;
        }

        @Override
        public BucketStreamContext getBucketStreamContext(Bucket bucket) {
            BucketStreamContext context = new BucketStreamContext();
            context.formatter(bucket.formatter);
            context.keyed(bucket.keyed);
            return context;
        }
    };

    public static void registerStream() {

        AggregationStreams.registerStream(STREAM, TYPE.stream());
        BucketStreams.registerStream(BUCKET_STREAM, TYPE.stream());
    }

    public static class Bucket implements Histogram.Bucket {

        long key;
        long docCount;
        InternalAggregations aggregations;
        private transient final boolean keyed;
        protected transient final @Nullable ValueFormatter formatter;

        public Bucket(boolean keyed, @Nullable ValueFormatter formatter) {
            this.formatter = formatter;
            this.keyed = keyed;
        }

        public Bucket(long key, long docCount, boolean keyed, @Nullable ValueFormatter formatter, InternalAggregations aggregations) {
            this(keyed, formatter);
            this.key = key;
            this.docCount = docCount;
            this.aggregations = aggregations;
        }

        protected Factory<?> getFactory() {
            return FACTORY;
        }

        @Override
        public String getKey() {
            return formatter != null ? formatter.format(key) : ValueFormatter.RAW.format(key);
        }

        @Override
        public Text getKeyAsText() {
            return new StringText(getKey());
        }

        @Override
        public Number getKeyAsNumber() {
            return key;
        }

        @Override
        public long getDocCount() {
            return docCount;
        }

        @Override
        public Aggregations getAggregations() {
            return aggregations;
        }

        <B extends Bucket> B reduce(List<B> buckets, ReduceContext context) {
            List<InternalAggregations> aggregations = new ArrayList<>(buckets.size());
            long docCount = 0;
            for (Bucket bucket : buckets) {
                docCount += bucket.docCount;
                aggregations.add((InternalAggregations) bucket.getAggregations());
            }
            InternalAggregations aggs = InternalAggregations.reduce(aggregations, context);
            return (B) getFactory().createBucket(key, docCount, aggs, keyed, formatter);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            if (formatter != null && formatter != ValueFormatter.RAW) {
                Text keyTxt = new StringText(formatter.format(key));
                if (keyed) {
                    builder.startObject(keyTxt.string());
                } else {
                    builder.startObject();
                }
                builder.field(CommonFields.KEY_AS_STRING, keyTxt);
            } else {
                if (keyed) {
                    builder.startObject(String.valueOf(getKeyAsNumber()));
                } else {
                    builder.startObject();
                }
            }
            builder.field(CommonFields.KEY, key);
            builder.field(CommonFields.DOC_COUNT, docCount);
            aggregations.toXContentInternal(builder, params);
            builder.endObject();
            return builder;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            key = in.readLong();
            docCount = in.readVLong();
            aggregations = InternalAggregations.readAggregations(in);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeLong(key);
            out.writeVLong(docCount);
            aggregations.writeTo(out);
        }
    }

    static class EmptyBucketInfo {

        final Rounding rounding;
        final InternalAggregations subAggregations;
        final ExtendedBounds bounds;

        EmptyBucketInfo(Rounding rounding, InternalAggregations subAggregations) {
            this(rounding, subAggregations, null);
        }

        EmptyBucketInfo(Rounding rounding, InternalAggregations subAggregations, ExtendedBounds bounds) {
            this.rounding = rounding;
            this.subAggregations = subAggregations;
            this.bounds = bounds;
        }

        public static EmptyBucketInfo readFrom(StreamInput in) throws IOException {
            Rounding rounding = Rounding.Streams.read(in);
            InternalAggregations aggs = InternalAggregations.readAggregations(in);
            if (in.getVersion().onOrAfter(Version.V_1_1_0)) {
                if (in.readBoolean()) {
                    return new EmptyBucketInfo(rounding, aggs, ExtendedBounds.readFrom(in));
                }
            }
            return new EmptyBucketInfo(rounding, aggs);
        }

        public static void writeTo(EmptyBucketInfo info, StreamOutput out) throws IOException {
            Rounding.Streams.write(info.rounding, out);
            info.subAggregations.writeTo(out);
            if (out.getVersion().onOrAfter(Version.V_1_1_0)) {
                out.writeBoolean(info.bounds != null);
                if (info.bounds != null) {
                    info.bounds.writeTo(out);
                }
            }
        }

    }

    static class Factory<B extends InternalHistogram.Bucket> {

        protected Factory() {
        }

        public String type() {
            return TYPE.name();
        }

        public InternalHistogram<B> create(String name, List<B> buckets, InternalOrder order, long minDocCount,
                                           EmptyBucketInfo emptyBucketInfo, @Nullable ValueFormatter formatter, boolean keyed, Map<String, Object> metaData) {
            return new InternalHistogram<>(name, buckets, order, minDocCount, emptyBucketInfo, formatter, keyed, metaData);
        }

        public B createBucket(long key, long docCount, InternalAggregations aggregations, boolean keyed, @Nullable ValueFormatter formatter) {
            return (B) new Bucket(key, docCount, keyed, formatter, aggregations);
        }

    }

    protected List<B> buckets;
    private LongObjectOpenHashMap<B> bucketsMap;
    private InternalOrder order;
    private @Nullable ValueFormatter formatter;
    private boolean keyed;
    private long minDocCount;
    private EmptyBucketInfo emptyBucketInfo;

    InternalHistogram() {} // for serialization

    InternalHistogram(String name, List<B> buckets, InternalOrder order, long minDocCount,
                      EmptyBucketInfo emptyBucketInfo, @Nullable ValueFormatter formatter, boolean keyed, Map<String, Object> metaData) {
        super(name, metaData);
        this.buckets = buckets;
        this.order = order;
        assert (minDocCount == 0) == (emptyBucketInfo != null);
        this.minDocCount = minDocCount;
        this.emptyBucketInfo = emptyBucketInfo;
        this.formatter = formatter;
        this.keyed = keyed;
    }

    @Override
    public Type type() {
        return TYPE;
    }

    @Override
    public List<B> getBuckets() {
        return buckets;
    }

    @Override
    public B getBucketByKey(String key) {
        return getBucketByKey(Long.valueOf(key));
    }

    @Override
    public B getBucketByKey(Number key) {
        if (bucketsMap == null) {
            bucketsMap = new LongObjectOpenHashMap<>(buckets.size());
            for (B bucket : buckets) {
                bucketsMap.put(bucket.key, bucket);
            }
        }
        return bucketsMap.get(key.longValue());
    }

    protected Factory<B> getFactory() {
        return FACTORY;
    }

    @Override
    public InternalAggregation reduce(ReduceContext reduceContext) {
        List<InternalAggregation> aggregations = reduceContext.aggregations();

        LongObjectPagedHashMap<List<B>> bucketsByKey = new LongObjectPagedHashMap<>(reduceContext.bigArrays());
        for (InternalAggregation aggregation : aggregations) {
            InternalHistogram<B> histogram = (InternalHistogram) aggregation;
            for (B bucket : histogram.buckets) {
                List<B> bucketList = bucketsByKey.get(bucket.key);
                if (bucketList == null) {
                    bucketList = new ArrayList<>(aggregations.size());
                    bucketsByKey.put(bucket.key, bucketList);
                }
                bucketList.add(bucket);
            }
        }

        List<B> reducedBuckets = new ArrayList<>((int) bucketsByKey.size());
        for (LongObjectPagedHashMap.Cursor<List<B>> cursor : bucketsByKey) {
            List<B> sameTermBuckets = cursor.value;
            B bucket = sameTermBuckets.get(0).reduce(sameTermBuckets, reduceContext);
            if (bucket.getDocCount() >= minDocCount) {
                reducedBuckets.add(bucket);
            }
        }
        bucketsByKey.close();

        // adding empty buckets in needed
        if (minDocCount == 0) {
            CollectionUtil.introSort(reducedBuckets, order.asc ? InternalOrder.KEY_ASC.comparator() : InternalOrder.KEY_DESC.comparator());
            List<B> list = order.asc ? reducedBuckets : Lists.reverse(reducedBuckets);
            B lastBucket = null;
            ExtendedBounds bounds = emptyBucketInfo.bounds;
            ListIterator<B> iter = list.listIterator();

            // first adding all the empty buckets *before* the actual data (based on th extended_bounds.min the user requested)
            if (bounds != null) {
                B firstBucket = iter.hasNext() ? list.get(iter.nextIndex()) : null;
                if (firstBucket == null) {
                    if (bounds.min != null && bounds.max != null) {
                        long key = bounds.min;
                        long max = bounds.max;
                        while (key <= max) {
                            iter.add(createBucket(key, 0, emptyBucketInfo.subAggregations, keyed, formatter));
                            key = emptyBucketInfo.rounding.nextRoundingValue(key);
                        }
                    }
                } else {
                    if (bounds.min != null) {
                        long key = bounds.min;
                        if (key < firstBucket.key) {
                            while (key < firstBucket.key) {
                                iter.add(createBucket(key, 0, emptyBucketInfo.subAggregations, keyed, formatter));
                                key = emptyBucketInfo.rounding.nextRoundingValue(key);
                            }
                        }
                    }
                }
            }

            // now adding the empty buckets within the actual data,
            // e.g. if the data series is [1,2,3,7] there're 3 empty buckets that will be created for 4,5,6
            while (iter.hasNext()) {
                B nextBucket = list.get(iter.nextIndex());
                if (lastBucket != null) {
                    long key = emptyBucketInfo.rounding.nextRoundingValue(lastBucket.key);
                    while (key < nextBucket.key) {
                        iter.add(createBucket(key, 0, emptyBucketInfo.subAggregations, keyed, formatter));
                        key = emptyBucketInfo.rounding.nextRoundingValue(key);
                    }
                    assert key == nextBucket.key;
                }
                lastBucket = iter.next();
            }

            // finally, adding the empty buckets *after* the actual data (based on the extended_bounds.max requested by the user)
            if (bounds != null && lastBucket != null && bounds.max != null && bounds.max > lastBucket.key) {
                long key = emptyBucketInfo.rounding.nextRoundingValue(lastBucket.key);
                long max = bounds.max;
                while (key <= max) {
                    iter.add(createBucket(key, 0, emptyBucketInfo.subAggregations, keyed, formatter));
                    key = emptyBucketInfo.rounding.nextRoundingValue(key);
                }
            }

            if (order != InternalOrder.KEY_ASC && order != InternalOrder.KEY_DESC) {
                CollectionUtil.introSort(reducedBuckets, order.comparator());
            }

        } else {
            CollectionUtil.introSort(reducedBuckets, order.comparator());
        }

        return getFactory().create(getName(), reducedBuckets, order, minDocCount, emptyBucketInfo, formatter, keyed, getMetaData());
    }

    protected B createBucket(long key, long docCount, InternalAggregations aggregations, boolean keyed, @Nullable ValueFormatter formatter) {
        return (B) new InternalHistogram.Bucket(key, docCount, keyed, formatter, aggregations);
    }

    protected B createEmptyBucket(boolean keyed, @Nullable ValueFormatter formatter) {
        return (B) new InternalHistogram.Bucket(keyed, formatter);
    }

    @Override
    protected void doReadFrom(StreamInput in) throws IOException {
        order = InternalOrder.Streams.readOrder(in);
        minDocCount = in.readVLong();
        if (minDocCount == 0) {
            emptyBucketInfo = EmptyBucketInfo.readFrom(in);
        }
        formatter = ValueFormatterStreams.readOptional(in);
        keyed = in.readBoolean();
        int size = in.readVInt();
        List<B> buckets = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            B bucket = createEmptyBucket(keyed, formatter);
            bucket.readFrom(in);
            buckets.add(bucket);
        }
        this.buckets = buckets;
        this.bucketsMap = null;
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        InternalOrder.Streams.writeOrder(order, out);
        out.writeVLong(minDocCount);
        if (minDocCount == 0) {
            EmptyBucketInfo.writeTo(emptyBucketInfo, out);
        }
        ValueFormatterStreams.writeOptional(formatter, out);
        out.writeBoolean(keyed);
        out.writeVInt(buckets.size());
        for (B bucket : buckets) {
            bucket.writeTo(out);
        }
    }

    @Override
    public XContentBuilder doXContentBody(XContentBuilder builder, Params params) throws IOException {
        if (keyed) {
            builder.startObject(CommonFields.BUCKETS);
        } else {
            builder.startArray(CommonFields.BUCKETS);
        }
        for (B bucket : buckets) {
            bucket.toXContent(builder, params);
        }
        if (keyed) {
            builder.endObject();
        } else {
            builder.endArray();
        }
        return builder;
    }

}
