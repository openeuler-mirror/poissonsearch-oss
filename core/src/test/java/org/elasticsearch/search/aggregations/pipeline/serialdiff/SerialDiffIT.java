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

package org.elasticsearch.search.aggregations.pipeline.serialdiff;

import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchPhaseExecutionException;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.collect.EvictingQueue;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram;
import org.elasticsearch.search.aggregations.bucket.histogram.InternalHistogram;
import org.elasticsearch.search.aggregations.pipeline.BucketHelpers;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregationHelperTests;
import org.elasticsearch.search.aggregations.pipeline.SimpleValue;
import org.elasticsearch.search.aggregations.support.ValuesSource;
import org.elasticsearch.search.aggregations.support.ValuesSourceAggregatorFactory;
import org.elasticsearch.test.ESIntegTestCase;
import org.hamcrest.Matchers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.search.aggregations.AggregationBuilders.avg;
import static org.elasticsearch.search.aggregations.AggregationBuilders.histogram;
import static org.elasticsearch.search.aggregations.AggregationBuilders.max;
import static org.elasticsearch.search.aggregations.AggregationBuilders.min;
import static org.elasticsearch.search.aggregations.pipeline.PipelineAggregatorBuilders.diff;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertSearchResponse;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;

@ESIntegTestCase.SuiteScopeTestCase
public class SerialDiffIT extends ESIntegTestCase {
    private static final String INTERVAL_FIELD = "l_value";
    private static final String VALUE_FIELD = "v_value";

    static int interval;
    static int numBuckets;
    static int lag;
    static BucketHelpers.GapPolicy gapPolicy;
    static ValuesSourceAggregatorFactory<? extends ValuesSource, ? extends ValuesSourceAggregatorFactory<?, ?>> metric;
    static List<PipelineAggregationHelperTests.MockBucket> mockHisto;

    static Map<String, ArrayList<Double>> testValues;

    enum MetricTarget {
        VALUE ("value"), COUNT("count");

        private final String name;

        MetricTarget(String s) {
            name = s;
        }

        @Override
        public String toString(){
            return name;
        }
    }

    private ValuesSourceAggregatorFactory<? extends ValuesSource, ? extends ValuesSourceAggregatorFactory<?, ?>> randomMetric(String name, String field) {
        int rand = randomIntBetween(0,3);

        switch (rand) {
            case 0:
                return min(name).field(field);
            case 2:
                return max(name).field(field);
            case 3:
                return avg(name).field(field);
            default:
                return avg(name).field(field);
        }
    }

    private void assertValidIterators(Iterator expectedBucketIter, Iterator expectedCountsIter, Iterator expectedValuesIter) {
        if (!expectedBucketIter.hasNext()) {
            fail("`expectedBucketIter` iterator ended before `actual` iterator, size mismatch");
        }
        if (!expectedCountsIter.hasNext()) {
            fail("`expectedCountsIter` iterator ended before `actual` iterator, size mismatch");
        }
        if (!expectedValuesIter.hasNext()) {
            fail("`expectedValuesIter` iterator ended before `actual` iterator, size mismatch");
        }
    }

    private void assertBucketContents(Histogram.Bucket actual, Double expectedCount, Double expectedValue) {
        // This is a gap bucket
        SimpleValue countDiff = actual.getAggregations().get("diff_counts");
        if (expectedCount == null) {
            assertThat("[_count] diff is not null", countDiff, nullValue());
        } else {
            assertThat("[_count] diff is null", countDiff, notNullValue());
            assertThat("[_count] diff does not match expected [" + countDiff.value() + " vs " + expectedCount + "]",
                    countDiff.value(), closeTo(expectedCount, 0.1));
        }

        // This is a gap bucket
        SimpleValue valuesDiff = actual.getAggregations().get("diff_values");
        if (expectedValue == null) {
            assertThat("[value] diff is not null", valuesDiff, Matchers.nullValue());
        } else {
            assertThat("[value] diff is null", valuesDiff, notNullValue());
            assertThat("[value] diff does not match expected [" + valuesDiff.value() + " vs " + expectedValue + "]",
                    valuesDiff.value(), closeTo(expectedValue, 0.1));
        }
    }


    @Override
    public void setupSuiteScopeCluster() throws Exception {
        createIndex("idx");
        createIndex("idx_unmapped");
        List<IndexRequestBuilder> builders = new ArrayList<>();


        interval = 5;
        numBuckets = randomIntBetween(10, 80);
        lag = randomIntBetween(1, numBuckets / 2);

        gapPolicy = randomBoolean() ? BucketHelpers.GapPolicy.SKIP : BucketHelpers.GapPolicy.INSERT_ZEROS;
        metric = randomMetric("the_metric", VALUE_FIELD);
        mockHisto = PipelineAggregationHelperTests.generateHistogram(interval, numBuckets, randomDouble(), randomDouble());

        testValues = new HashMap<>(8);

        for (MetricTarget target : MetricTarget.values()) {
            setupExpected(target);
        }

        for (PipelineAggregationHelperTests.MockBucket mockBucket : mockHisto) {
            for (double value : mockBucket.docValues) {
                builders.add(client().prepareIndex("idx", "type").setSource(jsonBuilder().startObject()
                        .field(INTERVAL_FIELD, mockBucket.key)
                        .field(VALUE_FIELD, value).endObject()));
            }
        }

        indexRandom(true, builders);
        ensureSearchable();
    }

    /**
     * @param target    The document field "target", e.g. _count or a field value
     */
    private void setupExpected(MetricTarget target) {
        ArrayList<Double> values = new ArrayList<>(numBuckets);
        EvictingQueue<Double> lagWindow = new EvictingQueue<>(lag);

        int counter = 0;
        for (PipelineAggregationHelperTests.MockBucket mockBucket : mockHisto) {
            Double metricValue;
            double[] docValues = mockBucket.docValues;

            // Gaps only apply to metric values, not doc _counts
            if (mockBucket.count == 0 && target.equals(MetricTarget.VALUE)) {
                // If there was a gap in doc counts and we are ignoring, just skip this bucket
                if (gapPolicy.equals(BucketHelpers.GapPolicy.SKIP)) {
                    metricValue = null;
                } else if (gapPolicy.equals(BucketHelpers.GapPolicy.INSERT_ZEROS)) {
                    // otherwise insert a zero instead of the true value
                    metricValue = 0.0;
                } else {
                    metricValue = PipelineAggregationHelperTests.calculateMetric(docValues, metric);
                }

            } else {
                // If this isn't a gap, or is a _count, just insert the value
                metricValue = target.equals(MetricTarget.VALUE) ? PipelineAggregationHelperTests.calculateMetric(docValues, metric) : mockBucket.count;
            }

            counter += 1;

            // Still under the initial lag period, add nothing and move on
            Double lagValue;
            if (counter <= lag) {
                lagValue = Double.NaN;
            } else {
                lagValue = lagWindow.peek();  // Peek here, because we rely on add'ing to always move the window
            }

            // Normalize null's to NaN
            if (metricValue == null) {
                metricValue = Double.NaN;
            }

            // Both have values, calculate diff and replace the "empty" bucket
            if (!Double.isNaN(metricValue) && !Double.isNaN(lagValue)) {
                double diff = metricValue - lagValue;
                values.add(diff);
            } else {
                values.add(null);   // The tests need null, even though the agg doesn't
            }

            lagWindow.add(metricValue);




        }


        testValues.put(target.toString(), values);
    }

    public void testBasicDiff() {
        SearchResponse response = client()
                .prepareSearch("idx").setTypes("type")
                .addAggregation(
                        histogram("histo").field(INTERVAL_FIELD).interval(interval)
                                .extendedBounds(0L, (long) (interval * (numBuckets - 1)))
                                .subAggregation(metric)
                                .subAggregation(diff("diff_counts")
                                        .lag(lag)
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("_count"))
                                .subAggregation(diff("diff_values")
                                        .lag(lag)
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("the_metric"))
                ).execute().actionGet();

        assertSearchResponse(response);

        InternalHistogram<InternalHistogram.Bucket> histo = response.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends InternalHistogram.Bucket> buckets = histo.getBuckets();
        assertThat("Size of buckets array is not correct.", buckets.size(), equalTo(mockHisto.size()));

        List<Double> expectedCounts = testValues.get(MetricTarget.COUNT.toString());
        List<Double> expectedValues = testValues.get(MetricTarget.VALUE.toString());

        Iterator<? extends Histogram.Bucket> actualIter = buckets.iterator();
        Iterator<PipelineAggregationHelperTests.MockBucket> expectedBucketIter = mockHisto.iterator();
        Iterator<Double> expectedCountsIter = expectedCounts.iterator();
        Iterator<Double> expectedValuesIter = expectedValues.iterator();

        while (actualIter.hasNext()) {
            assertValidIterators(expectedBucketIter, expectedCountsIter, expectedValuesIter);

            Histogram.Bucket actual = actualIter.next();
            PipelineAggregationHelperTests.MockBucket expected = expectedBucketIter.next();
            Double expectedCount = expectedCountsIter.next();
            Double expectedValue = expectedValuesIter.next();

            assertThat("keys do not match", ((Number) actual.getKey()).longValue(), equalTo(expected.key));
            assertThat("doc counts do not match", actual.getDocCount(), equalTo((long)expected.count));

            assertBucketContents(actual, expectedCount, expectedValue);
        }
    }

    public void testInvalidLagSize() {
        try {
            client()
                .prepareSearch("idx").setTypes("type")
                .addAggregation(
                        histogram("histo").field(INTERVAL_FIELD).interval(interval)
                                .extendedBounds(0L, (long) (interval * (numBuckets - 1)))
                                .subAggregation(metric)
                                .subAggregation(diff("diff_counts")
                                        .lag(-1)
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("_count"))
                ).execute().actionGet();
        } catch (SearchPhaseExecutionException e) {
            assertThat(e.getMessage(), is("all shards failed"));
        }
    }
}
