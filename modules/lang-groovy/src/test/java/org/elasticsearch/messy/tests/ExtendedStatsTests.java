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
package org.elasticsearch.messy.tests;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptService.ScriptType;
import org.elasticsearch.script.groovy.GroovyPlugin;
import org.elasticsearch.search.aggregations.bucket.global.Global;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram;
import org.elasticsearch.search.aggregations.bucket.missing.Missing;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.metrics.AbstractNumericTestCase;
import org.elasticsearch.search.aggregations.metrics.stats.extended.ExtendedStats;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.search.aggregations.AggregationBuilders.extendedStats;
import static org.elasticsearch.search.aggregations.AggregationBuilders.global;
import static org.elasticsearch.search.aggregations.AggregationBuilders.histogram;
import static org.elasticsearch.search.aggregations.AggregationBuilders.missing;
import static org.elasticsearch.search.aggregations.AggregationBuilders.terms;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;

/**
 *
 */
public class ExtendedStatsTests extends AbstractNumericTestCase {
    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Collections.singleton(GroovyPlugin.class);
    }

    private static double stdDev(int... vals) {
        return Math.sqrt(variance(vals));
    }

    private static double variance(int... vals) {
        double sum = 0;
        double sumOfSqrs = 0;
        for (int val : vals) {
            sum += val;
            sumOfSqrs += val * val;
        }
        return (sumOfSqrs - ((sum * sum) / vals.length)) / vals.length;
    }

    @Override
    public void testEmptyAggregation() throws Exception {
        SearchResponse searchResponse = client().prepareSearch("empty_bucket_idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        histogram("histo").field("value").interval(1L).minDocCount(0).subAggregation(extendedStats("stats").field("value")))
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(2L));
        Histogram histo = searchResponse.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        Histogram.Bucket bucket = histo.getBuckets().get(1);
        assertThat(bucket, notNullValue());

        ExtendedStats stats = bucket.getAggregations().get("stats");
        assertThat(stats, notNullValue());
        assertThat(stats.getName(), equalTo("stats"));
        assertThat(stats.getSumOfSquares(), equalTo(0.0));
        assertThat(stats.getCount(), equalTo(0L));
        assertThat(stats.getSum(), equalTo(0.0));
        assertThat(stats.getMin(), equalTo(Double.POSITIVE_INFINITY));
        assertThat(stats.getMax(), equalTo(Double.NEGATIVE_INFINITY));
        assertThat(Double.isNaN(stats.getStdDeviation()), is(true));
        assertThat(Double.isNaN(stats.getAvg()), is(true));
        assertThat(Double.isNaN(stats.getStdDeviationBound(ExtendedStats.Bounds.UPPER)), is(true));
        assertThat(Double.isNaN(stats.getStdDeviationBound(ExtendedStats.Bounds.LOWER)), is(true));
    }

    @Override
    public void testUnmapped() throws Exception {
        SearchResponse searchResponse = client().prepareSearch("idx_unmapped")
                .setQuery(matchAllQuery())
                .addAggregation(extendedStats("stats").field("value"))
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(0L));

        ExtendedStats stats = searchResponse.getAggregations().get("stats");
        assertThat(stats, notNullValue());
        assertThat(stats.getName(), equalTo("stats"));
        assertThat(stats.getAvg(), equalTo(Double.NaN));
        assertThat(stats.getMin(), equalTo(Double.POSITIVE_INFINITY));
        assertThat(stats.getMax(), equalTo(Double.NEGATIVE_INFINITY));
        assertThat(stats.getSum(), equalTo(0.0));
        assertThat(stats.getCount(), equalTo(0L));
        assertThat(stats.getSumOfSquares(), equalTo(0.0));
        assertThat(stats.getVariance(), equalTo(Double.NaN));
        assertThat(stats.getStdDeviation(), equalTo(Double.NaN));
        assertThat(Double.isNaN(stats.getStdDeviationBound(ExtendedStats.Bounds.UPPER)), is(true));
        assertThat(Double.isNaN(stats.getStdDeviationBound(ExtendedStats.Bounds.LOWER)), is(true));
    }

    @Override
    public void testSingleValuedField() throws Exception {
        double sigma = randomDouble() * randomIntBetween(1, 10);
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(extendedStats("stats").field("value").sigma(sigma))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        ExtendedStats stats = searchResponse.getAggregations().get("stats");
        assertThat(stats, notNullValue());
        assertThat(stats.getName(), equalTo("stats"));
        assertThat(stats.getAvg(), equalTo((double) (1+2+3+4+5+6+7+8+9+10) / 10));
        assertThat(stats.getMin(), equalTo(1.0));
        assertThat(stats.getMax(), equalTo(10.0));
        assertThat(stats.getSum(), equalTo((double) 1+2+3+4+5+6+7+8+9+10));
        assertThat(stats.getCount(), equalTo(10L));
        assertThat(stats.getSumOfSquares(), equalTo((double) 1+4+9+16+25+36+49+64+81+100));
        assertThat(stats.getVariance(), equalTo(variance(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)));
        assertThat(stats.getStdDeviation(), equalTo(stdDev(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)));
        checkUpperLowerBounds(stats, sigma);
    }

    public void testSingleValuedFieldDefaultSigma() throws Exception {
        // Same as previous test, but uses a default value for sigma

        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(extendedStats("stats").field("value"))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        ExtendedStats stats = searchResponse.getAggregations().get("stats");
        assertThat(stats, notNullValue());
        assertThat(stats.getName(), equalTo("stats"));
        assertThat(stats.getAvg(), equalTo((double) (1+2+3+4+5+6+7+8+9+10) / 10));
        assertThat(stats.getMin(), equalTo(1.0));
        assertThat(stats.getMax(), equalTo(10.0));
        assertThat(stats.getSum(), equalTo((double) 1+2+3+4+5+6+7+8+9+10));
        assertThat(stats.getCount(), equalTo(10L));
        assertThat(stats.getSumOfSquares(), equalTo((double) 1+4+9+16+25+36+49+64+81+100));
        assertThat(stats.getVariance(), equalTo(variance(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)));
        assertThat(stats.getStdDeviation(), equalTo(stdDev(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)));
        checkUpperLowerBounds(stats, 2);
    }

    public void testSingleValuedField_WithFormatter() throws Exception {
        double sigma = randomDouble() * randomIntBetween(1, 10);
        SearchResponse searchResponse = client().prepareSearch("idx").setQuery(matchAllQuery())
                .addAggregation(extendedStats("stats").format("0000.0").field("value").sigma(sigma)).execute().actionGet();

        assertHitCount(searchResponse, 10);

        ExtendedStats stats = searchResponse.getAggregations().get("stats");
        assertThat(stats, notNullValue());
        assertThat(stats.getName(), equalTo("stats"));
        assertThat(stats.getAvg(), equalTo((double) (1 + 2 + 3 + 4 + 5 + 6 + 7 + 8 + 9 + 10) / 10));
        assertThat(stats.getAvgAsString(), equalTo("0005.5"));
        assertThat(stats.getMin(), equalTo(1.0));
        assertThat(stats.getMinAsString(), equalTo("0001.0"));
        assertThat(stats.getMax(), equalTo(10.0));
        assertThat(stats.getMaxAsString(), equalTo("0010.0"));
        assertThat(stats.getSum(), equalTo((double) 1 + 2 + 3 + 4 + 5 + 6 + 7 + 8 + 9 + 10));
        assertThat(stats.getSumAsString(), equalTo("0055.0"));
        assertThat(stats.getCount(), equalTo(10L));
        assertThat(stats.getCountAsString(), equalTo("0010.0"));
        assertThat(stats.getSumOfSquares(), equalTo((double) 1 + 4 + 9 + 16 + 25 + 36 + 49 + 64 + 81 + 100));
        assertThat(stats.getSumOfSquaresAsString(), equalTo("0385.0"));
        assertThat(stats.getVariance(), equalTo(variance(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)));
        assertThat(stats.getVarianceAsString(), equalTo("0008.2"));
        assertThat(stats.getStdDeviation(), equalTo(stdDev(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)));
        assertThat(stats.getStdDeviationAsString(), equalTo("0002.9"));
        checkUpperLowerBounds(stats, sigma);
    }

    @Override
    public void testSingleValuedFieldGetProperty() throws Exception {
        SearchResponse searchResponse = client().prepareSearch("idx").setQuery(matchAllQuery())
                .addAggregation(global("global").subAggregation(extendedStats("stats").field("value"))).execute().actionGet();

        assertHitCount(searchResponse, 10);

        Global global = searchResponse.getAggregations().get("global");
        assertThat(global, notNullValue());
        assertThat(global.getName(), equalTo("global"));
        assertThat(global.getDocCount(), equalTo(10L));
        assertThat(global.getAggregations(), notNullValue());
        assertThat(global.getAggregations().asMap().size(), equalTo(1));

        ExtendedStats stats = global.getAggregations().get("stats");
        assertThat(stats, notNullValue());
        assertThat(stats.getName(), equalTo("stats"));
        ExtendedStats statsFromProperty = (ExtendedStats) global.getProperty("stats");
        assertThat(statsFromProperty, notNullValue());
        assertThat(statsFromProperty, sameInstance(stats));
        double expectedAvgValue = (double) (1 + 2 + 3 + 4 + 5 + 6 + 7 + 8 + 9 + 10) / 10;
        assertThat(stats.getAvg(), equalTo(expectedAvgValue));
        assertThat((double) global.getProperty("stats.avg"), equalTo(expectedAvgValue));
        double expectedMinValue = 1.0;
        assertThat(stats.getMin(), equalTo(expectedMinValue));
        assertThat((double) global.getProperty("stats.min"), equalTo(expectedMinValue));
        double expectedMaxValue = 10.0;
        assertThat(stats.getMax(), equalTo(expectedMaxValue));
        assertThat((double) global.getProperty("stats.max"), equalTo(expectedMaxValue));
        double expectedSumValue = 1 + 2 + 3 + 4 + 5 + 6 + 7 + 8 + 9 + 10;
        assertThat(stats.getSum(), equalTo(expectedSumValue));
        assertThat((double) global.getProperty("stats.sum"), equalTo(expectedSumValue));
        long expectedCountValue = 10;
        assertThat(stats.getCount(), equalTo(expectedCountValue));
        assertThat((double) global.getProperty("stats.count"), equalTo((double) expectedCountValue));
        double expectedSumOfSquaresValue = (double) 1 + 4 + 9 + 16 + 25 + 36 + 49 + 64 + 81 + 100;
        assertThat(stats.getSumOfSquares(), equalTo(expectedSumOfSquaresValue));
        assertThat((double) global.getProperty("stats.sum_of_squares"), equalTo(expectedSumOfSquaresValue));
        double expectedVarianceValue = variance(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(stats.getVariance(), equalTo(expectedVarianceValue));
        assertThat((double) global.getProperty("stats.variance"), equalTo(expectedVarianceValue));
        double expectedStdDevValue = stdDev(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(stats.getStdDeviation(), equalTo(expectedStdDevValue));
        assertThat((double) global.getProperty("stats.std_deviation"), equalTo(expectedStdDevValue));
    }

    @Override
    public void testSingleValuedFieldPartiallyUnmapped() throws Exception {
        double sigma = randomDouble() * randomIntBetween(1, 10);
        SearchResponse searchResponse = client().prepareSearch("idx", "idx_unmapped")
                .setQuery(matchAllQuery())
                .addAggregation(extendedStats("stats").field("value").sigma(sigma))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        ExtendedStats stats = searchResponse.getAggregations().get("stats");
        assertThat(stats, notNullValue());
        assertThat(stats.getName(), equalTo("stats"));
        assertThat(stats.getAvg(), equalTo((double) (1+2+3+4+5+6+7+8+9+10) / 10));
        assertThat(stats.getMin(), equalTo(1.0));
        assertThat(stats.getMax(), equalTo(10.0));
        assertThat(stats.getSum(), equalTo((double) 1+2+3+4+5+6+7+8+9+10));
        assertThat(stats.getCount(), equalTo(10L));
        assertThat(stats.getSumOfSquares(), equalTo((double) 1+4+9+16+25+36+49+64+81+100));
        assertThat(stats.getVariance(), equalTo(variance(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)));
        assertThat(stats.getStdDeviation(), equalTo(stdDev(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)));
        checkUpperLowerBounds(stats, sigma);
    }

    @Override
    public void testSingleValuedFieldWithValueScript() throws Exception {
        double sigma = randomDouble() * randomIntBetween(1, 10);
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(extendedStats("stats").field("value").script(new Script("_value + 1")).sigma(sigma))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        ExtendedStats stats = searchResponse.getAggregations().get("stats");
        assertThat(stats, notNullValue());
        assertThat(stats.getName(), equalTo("stats"));
        assertThat(stats.getAvg(), equalTo((double) (2+3+4+5+6+7+8+9+10+11) / 10));
        assertThat(stats.getMin(), equalTo(2.0));
        assertThat(stats.getMax(), equalTo(11.0));
        assertThat(stats.getSum(), equalTo((double) 2+3+4+5+6+7+8+9+10+11));
        assertThat(stats.getCount(), equalTo(10L));
        assertThat(stats.getSumOfSquares(), equalTo((double) 4+9+16+25+36+49+64+81+100+121));
        assertThat(stats.getVariance(), equalTo(variance(2, 3, 4, 5, 6, 7, 8, 9, 10, 11)));
        assertThat(stats.getStdDeviation(), equalTo(stdDev(2, 3, 4, 5, 6, 7, 8, 9, 10, 11)));
        checkUpperLowerBounds(stats, sigma);
    }

    @Override
    public void testSingleValuedFieldWithValueScriptWithParams() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("inc", 1);
        double sigma = randomDouble() * randomIntBetween(1, 10);
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        extendedStats("stats").field("value").script(new Script("_value + inc", ScriptType.INLINE, null, params))
                                .sigma(sigma))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        ExtendedStats stats = searchResponse.getAggregations().get("stats");
        assertThat(stats, notNullValue());
        assertThat(stats.getName(), equalTo("stats"));
        assertThat(stats.getAvg(), equalTo((double) (2+3+4+5+6+7+8+9+10+11) / 10));
        assertThat(stats.getMin(), equalTo(2.0));
        assertThat(stats.getMax(), equalTo(11.0));
        assertThat(stats.getSum(), equalTo((double) 2+3+4+5+6+7+8+9+10+11));
        assertThat(stats.getCount(), equalTo(10L));
        assertThat(stats.getSumOfSquares(), equalTo((double) 4+9+16+25+36+49+64+81+100+121));
        assertThat(stats.getVariance(), equalTo(variance(2, 3, 4, 5, 6, 7, 8, 9, 10, 11)));
        assertThat(stats.getStdDeviation(), equalTo(stdDev(2, 3, 4, 5, 6, 7, 8, 9, 10, 11)));
        checkUpperLowerBounds(stats, sigma);
    }

    @Override
    public void testMultiValuedField() throws Exception {
        double sigma = randomDouble() * randomIntBetween(1, 10);
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(extendedStats("stats").field("values").sigma(sigma))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        ExtendedStats stats = searchResponse.getAggregations().get("stats");
        assertThat(stats, notNullValue());
        assertThat(stats.getName(), equalTo("stats"));
        assertThat(stats.getAvg(), equalTo((double) (2+3+4+5+6+7+8+9+10+11+3+4+5+6+7+8+9+10+11+12) / 20));
        assertThat(stats.getMin(), equalTo(2.0));
        assertThat(stats.getMax(), equalTo(12.0));
        assertThat(stats.getSum(), equalTo((double) 2+3+4+5+6+7+8+9+10+11+3+4+5+6+7+8+9+10+11+12));
        assertThat(stats.getCount(), equalTo(20L));
        assertThat(stats.getSumOfSquares(), equalTo((double) 4+9+16+25+36+49+64+81+100+121+9+16+25+36+49+64+81+100+121+144));
        assertThat(stats.getVariance(), equalTo(variance(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)));
        assertThat(stats.getStdDeviation(), equalTo(stdDev(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)));
        checkUpperLowerBounds(stats, sigma);
    }

    @Override
    public void testMultiValuedFieldWithValueScript() throws Exception {
        double sigma = randomDouble() * randomIntBetween(1, 10);
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(extendedStats("stats").field("values").script(new Script("_value - 1")).sigma(sigma))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        ExtendedStats stats = searchResponse.getAggregations().get("stats");
        assertThat(stats, notNullValue());
        assertThat(stats.getName(), equalTo("stats"));
        assertThat(stats.getAvg(), equalTo((double) (1+2+3+4+5+6+7+8+9+10+2+3+4+5+6+7+8+9+10+11) / 20));
        assertThat(stats.getMin(), equalTo(1.0));
        assertThat(stats.getMax(), equalTo(11.0));
        assertThat(stats.getSum(), equalTo((double) 1+2+3+4+5+6+7+8+9+10+2+3+4+5+6+7+8+9+10+11));
        assertThat(stats.getCount(), equalTo(20L));
        assertThat(stats.getSumOfSquares(), equalTo((double) 1+4+9+16+25+36+49+64+81+100+4+9+16+25+36+49+64+81+100+121));
        assertThat(stats.getVariance(), equalTo(variance(1, 2, 3, 4, 5, 6, 7, 8 ,9, 10, 2, 3, 4, 5, 6, 7, 8 ,9, 10, 11)));
        assertThat(stats.getStdDeviation(), equalTo(stdDev(1, 2, 3, 4, 5, 6, 7, 8 ,9, 10, 2, 3, 4, 5, 6, 7, 8 ,9, 10, 11)));
        checkUpperLowerBounds(stats, sigma);
    }

    @Override
    public void testMultiValuedFieldWithValueScriptWithParams() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("dec", 1);
        double sigma = randomDouble() * randomIntBetween(1, 10);
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        extendedStats("stats").field("values").script(new Script("_value - dec", ScriptType.INLINE, null, params))
                                .sigma(sigma))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        ExtendedStats stats = searchResponse.getAggregations().get("stats");
        assertThat(stats, notNullValue());
        assertThat(stats.getName(), equalTo("stats"));
        assertThat(stats.getAvg(), equalTo((double) (1+2+3+4+5+6+7+8+9+10+2+3+4+5+6+7+8+9+10+11) / 20));
        assertThat(stats.getMin(), equalTo(1.0));
        assertThat(stats.getMax(), equalTo(11.0));
        assertThat(stats.getSum(), equalTo((double) 1+2+3+4+5+6+7+8+9+10+2+3+4+5+6+7+8+9+10+11));
        assertThat(stats.getCount(), equalTo(20L));
        assertThat(stats.getSumOfSquares(), equalTo((double) 1+4+9+16+25+36+49+64+81+100+4+9+16+25+36+49+64+81+100+121));
        assertThat(stats.getVariance(), equalTo(variance(1, 2, 3, 4, 5, 6, 7, 8 ,9, 10, 2, 3, 4, 5, 6, 7, 8 ,9, 10, 11)));
        assertThat(stats.getStdDeviation(), equalTo(stdDev(1, 2, 3, 4, 5, 6, 7, 8 ,9, 10, 2, 3, 4, 5, 6, 7, 8 ,9, 10, 11)));
        checkUpperLowerBounds(stats, sigma);
    }

    @Override
    public void testScriptSingleValued() throws Exception {
        double sigma = randomDouble() * randomIntBetween(1, 10);
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(extendedStats("stats").script(new Script("doc['value'].value")).sigma(sigma))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        ExtendedStats stats = searchResponse.getAggregations().get("stats");
        assertThat(stats, notNullValue());
        assertThat(stats.getName(), equalTo("stats"));
        assertThat(stats.getAvg(), equalTo((double) (1+2+3+4+5+6+7+8+9+10) / 10));
        assertThat(stats.getMin(), equalTo(1.0));
        assertThat(stats.getMax(), equalTo(10.0));
        assertThat(stats.getSum(), equalTo((double) 1+2+3+4+5+6+7+8+9+10));
        assertThat(stats.getCount(), equalTo(10L));
        assertThat(stats.getSumOfSquares(), equalTo((double) 1+4+9+16+25+36+49+64+81+100));
        assertThat(stats.getVariance(), equalTo(variance(1, 2, 3, 4, 5, 6, 7, 8 ,9, 10)));
        assertThat(stats.getStdDeviation(), equalTo(stdDev(1, 2, 3, 4, 5, 6, 7, 8 ,9, 10)));
        checkUpperLowerBounds(stats, sigma);
    }

    @Override
    public void testScriptSingleValuedWithParams() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("inc", 1);
        double sigma = randomDouble() * randomIntBetween(1, 10);
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        extendedStats("stats").script(new Script("doc['value'].value + inc", ScriptType.INLINE, null, params)).sigma(sigma))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        ExtendedStats stats = searchResponse.getAggregations().get("stats");
        assertThat(stats, notNullValue());
        assertThat(stats.getName(), equalTo("stats"));
        assertThat(stats.getAvg(), equalTo((double) (2+3+4+5+6+7+8+9+10+11) / 10));
        assertThat(stats.getMin(), equalTo(2.0));
        assertThat(stats.getMax(), equalTo(11.0));
        assertThat(stats.getSum(), equalTo((double) 2+3+4+5+6+7+8+9+10+11));
        assertThat(stats.getCount(), equalTo(10L));
        assertThat(stats.getSumOfSquares(), equalTo((double) 4+9+16+25+36+49+64+81+100+121));
        assertThat(stats.getVariance(), equalTo(variance(2, 3, 4, 5, 6, 7, 8 ,9, 10, 11)));
        assertThat(stats.getStdDeviation(), equalTo(stdDev(2, 3, 4, 5, 6, 7, 8 ,9, 10, 11)));
        checkUpperLowerBounds(stats, sigma);
    }

    @Override
    public void testScriptMultiValued() throws Exception {
        double sigma = randomDouble() * randomIntBetween(1, 10);
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(extendedStats("stats").script(new Script("doc['values'].values")).sigma(sigma))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        ExtendedStats stats = searchResponse.getAggregations().get("stats");
        assertThat(stats, notNullValue());
        assertThat(stats.getName(), equalTo("stats"));
        assertThat(stats.getAvg(), equalTo((double) (2+3+4+5+6+7+8+9+10+11+3+4+5+6+7+8+9+10+11+12) / 20));
        assertThat(stats.getMin(), equalTo(2.0));
        assertThat(stats.getMax(), equalTo(12.0));
        assertThat(stats.getSum(), equalTo((double) 2+3+4+5+6+7+8+9+10+11+3+4+5+6+7+8+9+10+11+12));
        assertThat(stats.getCount(), equalTo(20L));
        assertThat(stats.getSumOfSquares(), equalTo((double) 4+9+16+25+36+49+64+81+100+121+9+16+25+36+49+64+81+100+121+144));
        assertThat(stats.getVariance(), equalTo(variance(2, 3, 4, 5, 6, 7, 8 ,9, 10, 11, 3, 4, 5, 6, 7, 8 ,9, 10, 11, 12)));
        assertThat(stats.getStdDeviation(), equalTo(stdDev(2, 3, 4, 5, 6, 7, 8 ,9, 10, 11, 3, 4, 5, 6, 7, 8 ,9, 10, 11, 12)));
        checkUpperLowerBounds(stats, sigma);
    }

    @Override
    public void testScriptMultiValuedWithParams() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("dec", 1);
        double sigma = randomDouble() * randomIntBetween(1, 10);
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        extendedStats("stats").script(
                                new Script("[ doc['value'].value, doc['value'].value - dec ]", ScriptType.INLINE, null, params))
                                .sigma(sigma))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        ExtendedStats stats = searchResponse.getAggregations().get("stats");
        assertThat(stats, notNullValue());
        assertThat(stats.getName(), equalTo("stats"));
        assertThat(stats.getAvg(), equalTo((double) (1+2+3+4+5+6+7+8+9+10+0+1+2+3+4+5+6+7+8+9) / 20));
        assertThat(stats.getMin(), equalTo(0.0));
        assertThat(stats.getMax(), equalTo(10.0));
        assertThat(stats.getSum(), equalTo((double) 1+2+3+4+5+6+7+8+9+10+0+1+2+3+4+5+6+7+8+9));
        assertThat(stats.getCount(), equalTo(20L));
        assertThat(stats.getSumOfSquares(), equalTo((double) 1+4+9+16+25+36+49+64+81+100+0+1+4+9+16+25+36+49+64+81));
        assertThat(stats.getVariance(), equalTo(variance(1, 2, 3, 4, 5, 6, 7, 8 ,9, 10, 0, 1, 2, 3, 4, 5, 6, 7, 8 ,9)));
        assertThat(stats.getStdDeviation(), equalTo(stdDev(1, 2, 3, 4, 5, 6, 7, 8 ,9, 10, 0, 1, 2, 3, 4, 5, 6, 7, 8 ,9)));
        checkUpperLowerBounds(stats, sigma);
    }

    public void testEmptySubAggregation() {
        SearchResponse searchResponse = client().prepareSearch("idx")
            .setQuery(matchAllQuery())
            .addAggregation(terms("value").field("value")
                .subAggregation(missing("values").field("values")
                    .subAggregation(extendedStats("stats").field("value"))))
            .execute().actionGet();

        assertHitCount(searchResponse, 10);

        Terms terms = searchResponse.getAggregations().get("value");
        assertThat(terms, notNullValue());
        assertThat(terms.getBuckets().size(), equalTo(10));

        for (Terms.Bucket bucket : terms.getBuckets()) {
            assertThat(bucket.getDocCount(), equalTo(1L));

            Missing missing = bucket.getAggregations().get("values");
            assertThat(missing, notNullValue());
            assertThat(missing.getDocCount(), equalTo(0L));

            ExtendedStats stats = missing.getAggregations().get("stats");
            assertThat(stats, notNullValue());
            assertThat(stats.getName(), equalTo("stats"));
            assertThat(stats.getSumOfSquares(), equalTo(0.0));
            assertThat(stats.getCount(), equalTo(0L));
            assertThat(stats.getSum(), equalTo(0.0));
            assertThat(stats.getMin(), equalTo(Double.POSITIVE_INFINITY));
            assertThat(stats.getMax(), equalTo(Double.NEGATIVE_INFINITY));
            assertThat(Double.isNaN(stats.getStdDeviation()), is(true));
            assertThat(Double.isNaN(stats.getAvg()), is(true));
            assertThat(Double.isNaN(stats.getStdDeviationBound(ExtendedStats.Bounds.UPPER)), is(true));
            assertThat(Double.isNaN(stats.getStdDeviationBound(ExtendedStats.Bounds.LOWER)), is(true));
        }
    }


    private void assertShardExecutionState(SearchResponse response, int expectedFailures) throws Exception {
        ShardSearchFailure[] failures = response.getShardFailures();
        if (failures.length != expectedFailures) {
            for (ShardSearchFailure failure : failures) {
                logger.error("Shard Failure: {}", failure);
            }
            fail("Unexpected shard failures!");
        }
        assertThat("Not all shards are initialized", response.getSuccessfulShards(), equalTo(response.getTotalShards()));
    }

    private void checkUpperLowerBounds(ExtendedStats stats, double sigma) {
        assertThat(stats.getStdDeviationBound(ExtendedStats.Bounds.UPPER), equalTo(stats.getAvg() + (stats.getStdDeviation() * sigma)));
        assertThat(stats.getStdDeviationBound(ExtendedStats.Bounds.LOWER), equalTo(stats.getAvg() - (stats.getStdDeviation() * sigma)));
    }

}
