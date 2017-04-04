/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.datafeed.extractor.aggregation;

import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.client.Client;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.AggregatorFactories;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.test.ESTestCase;
import org.junit.Before;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.elasticsearch.xpack.ml.datafeed.extractor.aggregation.AggregationTestUtils.Term;
import static org.elasticsearch.xpack.ml.datafeed.extractor.aggregation.AggregationTestUtils.createHistogramBucket;
import static org.elasticsearch.xpack.ml.datafeed.extractor.aggregation.AggregationTestUtils.createMax;
import static org.elasticsearch.xpack.ml.datafeed.extractor.aggregation.AggregationTestUtils.createTerms;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AggregationDataExtractorTests extends ESTestCase {

    private Client client;
    private List<SearchRequestBuilder> capturedSearchRequests;
    private String jobId;
    private String timeField;
    private List<String> types;
    private List<String> indexes;
    private QueryBuilder query;
    private AggregatorFactories.Builder aggs;

    private class TestDataExtractor extends AggregationDataExtractor {

        private SearchResponse nextResponse;

        TestDataExtractor(long start, long end) {
            super(client, createContext(start, end));
        }

        @Override
        protected SearchResponse executeSearchRequest(SearchRequestBuilder searchRequestBuilder) {
            capturedSearchRequests.add(searchRequestBuilder);
            return nextResponse;
        }

        void setNextResponse(SearchResponse searchResponse) {
            nextResponse = searchResponse;
        }
    }

    @Before
    public void setUpTests() {
        client = mock(Client.class);
        capturedSearchRequests = new ArrayList<>();
        jobId = "test-job";
        timeField = "time";
        indexes = Arrays.asList("index-1", "index-2");
        types = Arrays.asList("type-1", "type-2");
        query = QueryBuilders.matchAllQuery();
        aggs = new AggregatorFactories.Builder()
                .addAggregator(AggregationBuilders.histogram("time").field("time").subAggregation(
                        AggregationBuilders.terms("airline").field("airline").subAggregation(
                                AggregationBuilders.avg("responsetime").field("responsetime"))));
    }

    public void testExtraction() throws IOException {
        List<Histogram.Bucket> histogramBuckets = Arrays.asList(
            createHistogramBucket(1000L, 3, Arrays.asList(
                    createMax("time", 1999),
                    createTerms("airline", new Term("a", 1, "responsetime", 11.0), new Term("b", 2, "responsetime", 12.0)))),
            createHistogramBucket(2000L, 0, Arrays.asList()),
            createHistogramBucket(3000L, 7, Arrays.asList(
                    createMax("time", 3999),
                    createTerms("airline", new Term("c", 4, "responsetime", 31.0), new Term("b", 3, "responsetime", 32.0))))
        );

        TestDataExtractor extractor = new TestDataExtractor(1000L, 4000L);

        SearchResponse response = createSearchResponse("time", histogramBuckets);
        extractor.setNextResponse(response);

        assertThat(extractor.hasNext(), is(true));
        Optional<InputStream> stream = extractor.next();
        assertThat(stream.isPresent(), is(true));
        String expectedStream = "{\"time\":1999,\"airline\":\"a\",\"responsetime\":11.0,\"doc_count\":1} "
                + "{\"time\":1999,\"airline\":\"b\",\"responsetime\":12.0,\"doc_count\":2} "
                + "{\"time\":3999,\"airline\":\"c\",\"responsetime\":31.0,\"doc_count\":4} "
                + "{\"time\":3999,\"airline\":\"b\",\"responsetime\":32.0,\"doc_count\":3}";
        assertThat(asString(stream.get()), equalTo(expectedStream));
        assertThat(extractor.hasNext(), is(false));
        assertThat(capturedSearchRequests.size(), equalTo(1));

        String searchRequest = capturedSearchRequests.get(0).toString().replaceAll("\\s", "");
        assertThat(searchRequest, containsString("\"size\":0"));
        assertThat(searchRequest, containsString("\"query\":{\"bool\":{\"filter\":[{\"match_all\":{\"boost\":1.0}}," +
                "{\"range\":{\"time\":{\"from\":1000,\"to\":4000,\"include_lower\":true,\"include_upper\":false," +
                "\"format\":\"epoch_millis\",\"boost\":1.0}}}]"));
        assertThat(searchRequest,
                stringContainsInOrder(Arrays.asList("aggregations", "histogram", "time", "terms", "airline", "avg", "responsetime")));
    }

    public void testExtractionGivenMultipleBatches() throws IOException {
        // Each bucket is 2 key-value pairs, thus 1200 buckets will be 2400
        // key-value pairs. They should be processed in 3 batches.
        int buckets = 1200;
        List<Histogram.Bucket> histogramBuckets = new ArrayList<>(buckets);
        long timestamp = 1000;
        for (int i = 0; i < buckets; i++) {
            histogramBuckets.add(createHistogramBucket(timestamp, 3, Arrays.asList(createMax("time", timestamp))));
            timestamp += 1000L;
        }

        TestDataExtractor extractor = new TestDataExtractor(1000L, timestamp + 1);

        SearchResponse response = createSearchResponse("time", histogramBuckets);
        extractor.setNextResponse(response);

        assertThat(extractor.hasNext(), is(true));
        assertThat(countMatches('{', asString(extractor.next().get())), equalTo(500L));
        assertThat(extractor.hasNext(), is(true));
        assertThat(countMatches('{', asString(extractor.next().get())), equalTo(500L));
        assertThat(extractor.hasNext(), is(true));
        assertThat(countMatches('{', asString(extractor.next().get())), equalTo(200L));
        assertThat(extractor.hasNext(), is(false));

        assertThat(capturedSearchRequests.size(), equalTo(1));
    }

    public void testExtractionGivenResponseHasNullAggs() throws IOException {
        TestDataExtractor extractor = new TestDataExtractor(1000L, 2000L);

        SearchResponse response = createSearchResponse(null);
        extractor.setNextResponse(response);

        assertThat(extractor.hasNext(), is(true));
        assertThat(extractor.next().isPresent(), is(false));
        assertThat(extractor.hasNext(), is(false));

        assertThat(capturedSearchRequests.size(), equalTo(1));
    }

    public void testExtractionGivenResponseHasEmptyAggs() throws IOException {
        TestDataExtractor extractor = new TestDataExtractor(1000L, 2000L);

        Aggregations emptyAggs = mock(Aggregations.class);
        when(emptyAggs.asList()).thenReturn(Collections.emptyList());
        SearchResponse response = createSearchResponse(emptyAggs);
        extractor.setNextResponse(response);

        assertThat(extractor.hasNext(), is(true));
        assertThat(extractor.next().isPresent(), is(false));
        assertThat(extractor.hasNext(), is(false));

        assertThat(capturedSearchRequests.size(), equalTo(1));
    }

    public void testExtractionGivenResponseHasInvalidTopLevelAgg() throws IOException {
        TestDataExtractor extractor = new TestDataExtractor(1000L, 2000L);

        Terms termsAgg = mock(Terms.class);
        Aggregations emptyAggs = mock(Aggregations.class);
        when(emptyAggs.asList()).thenReturn(Collections.singletonList(termsAgg));
        SearchResponse response = createSearchResponse(emptyAggs);
        extractor.setNextResponse(response);

        assertThat(extractor.hasNext(), is(true));
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> extractor.next());
        assertThat(e.getMessage(), containsString("Top level aggregation should be [histogram]"));
    }

    public void testExtractionGivenResponseHasMultipleTopLevelAggs() throws IOException {
        TestDataExtractor extractor = new TestDataExtractor(1000L, 2000L);

        Histogram histogram1 = mock(Histogram.class);
        when(histogram1.getName()).thenReturn("hist_1");
        Histogram histogram2 = mock(Histogram.class);
        when(histogram2.getName()).thenReturn("hist_2");

        Aggregations emptyAggs = mock(Aggregations.class);
        when(emptyAggs.asList()).thenReturn(Arrays.asList(histogram1, histogram2));
        SearchResponse response = createSearchResponse(emptyAggs);
        extractor.setNextResponse(response);

        assertThat(extractor.hasNext(), is(true));
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> extractor.next());
        assertThat(e.getMessage(), containsString("Multiple top level aggregations not supported; found: [hist_1, hist_2]"));
    }

    public void testExtractionGivenCancelBeforeNext() throws IOException {
        TestDataExtractor extractor = new TestDataExtractor(1000L, 4000L);
        SearchResponse response = createSearchResponse("time", Collections.emptyList());
        extractor.setNextResponse(response);

        extractor.cancel();
        assertThat(extractor.hasNext(), is(false));
    }

    public void testExtractionGivenCancelHalfWay() throws IOException {
        int buckets = 1200;
        List<Histogram.Bucket> histogramBuckets = new ArrayList<>(buckets);
        long timestamp = 1000;
        for (int i = 0; i < buckets; i++) {
            histogramBuckets.add(createHistogramBucket(timestamp, 3, Arrays.asList(createMax("time", timestamp))));
            timestamp += 1000L;
        }

        TestDataExtractor extractor = new TestDataExtractor(1000L, timestamp + 1);

        SearchResponse response = createSearchResponse("time", histogramBuckets);
        extractor.setNextResponse(response);

        assertThat(extractor.hasNext(), is(true));
        assertThat(countMatches('{', asString(extractor.next().get())), equalTo(500L));
        assertThat(extractor.hasNext(), is(true));
        assertThat(countMatches('{', asString(extractor.next().get())), equalTo(500L));
        assertThat(extractor.hasNext(), is(true));

        extractor.cancel();

        assertThat(extractor.hasNext(), is(false));
        assertThat(extractor.isCancelled(), is(true));

        assertThat(capturedSearchRequests.size(), equalTo(1));
    }

    public void testExtractionGivenSearchResponseHasError() throws IOException {
        TestDataExtractor extractor = new TestDataExtractor(1000L, 2000L);
        extractor.setNextResponse(createErrorResponse());

        assertThat(extractor.hasNext(), is(true));
        expectThrows(IOException.class, () -> extractor.next());
    }

    public void testExtractionGivenSearchResponseHasShardFailures() throws IOException {
        TestDataExtractor extractor = new TestDataExtractor(1000L, 2000L);
        extractor.setNextResponse(createResponseWithShardFailures());

        assertThat(extractor.hasNext(), is(true));
        IOException e = expectThrows(IOException.class, () -> extractor.next());
    }

    public void testExtractionGivenInitSearchResponseEncounteredUnavailableShards() throws IOException {
        TestDataExtractor extractor = new TestDataExtractor(1000L, 2000L);
        extractor.setNextResponse(createResponseWithUnavailableShards(2));

        assertThat(extractor.hasNext(), is(true));
        IOException e = expectThrows(IOException.class, () -> extractor.next());
        assertThat(e.getMessage(), equalTo("[" + jobId + "] Search request encountered [2] unavailable shards"));
    }

    private AggregationDataExtractorContext createContext(long start, long end) {
        return new AggregationDataExtractorContext(jobId, timeField, indexes, types, query, aggs, start, end, true);
    }

    private SearchResponse createSearchResponse(String histogramName, List<Histogram.Bucket> histogramBuckets) {
        Histogram histogram = mock(Histogram.class);
        when(histogram.getName()).thenReturn(histogramName);
        when(histogram.getBuckets()).thenReturn(histogramBuckets);

        Aggregations searchAggs = mock(Aggregations.class);
        when(searchAggs.asList()).thenReturn(Arrays.asList(histogram));
        return createSearchResponse(searchAggs);
    }

    private SearchResponse createSearchResponse(Aggregations aggregations) {
        SearchResponse searchResponse = mock(SearchResponse.class);
        when(searchResponse.status()).thenReturn(RestStatus.OK);
        when(searchResponse.getScrollId()).thenReturn(randomAlphaOfLength(1000));
        when(searchResponse.getAggregations()).thenReturn(aggregations);
        return searchResponse;
    }

    private SearchResponse createErrorResponse() {
        SearchResponse searchResponse = mock(SearchResponse.class);
        when(searchResponse.status()).thenReturn(RestStatus.INTERNAL_SERVER_ERROR);
        return searchResponse;
    }

    private SearchResponse createResponseWithShardFailures() {
        SearchResponse searchResponse = mock(SearchResponse.class);
        when(searchResponse.status()).thenReturn(RestStatus.OK);
        when(searchResponse.getShardFailures()).thenReturn(
                new ShardSearchFailure[] { new ShardSearchFailure(new RuntimeException("shard failed"))});
        return searchResponse;
    }

    private SearchResponse createResponseWithUnavailableShards(int unavailableShards) {
        SearchResponse searchResponse = mock(SearchResponse.class);
        when(searchResponse.status()).thenReturn(RestStatus.OK);
        when(searchResponse.getSuccessfulShards()).thenReturn(3);
        when(searchResponse.getTotalShards()).thenReturn(3 + unavailableShards);
        return searchResponse;
    }

    private static String asString(InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private static long countMatches(char c, String text) {
        return text.chars().filter(current -> current == c).count();
    }
}
