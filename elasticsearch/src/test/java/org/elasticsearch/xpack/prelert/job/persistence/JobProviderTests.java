/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.job.persistence;

import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHitField;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.prelert.action.DeleteJobAction;
import org.elasticsearch.xpack.prelert.job.AnalysisLimits;
import org.elasticsearch.xpack.prelert.job.CategorizerState;
import org.elasticsearch.xpack.prelert.job.DataCounts;
import org.elasticsearch.xpack.prelert.job.Job;
import org.elasticsearch.xpack.prelert.job.ModelSnapshot;
import org.elasticsearch.xpack.prelert.job.ModelState;
import org.elasticsearch.xpack.prelert.job.audit.AuditActivity;
import org.elasticsearch.xpack.prelert.job.audit.AuditMessage;
import org.elasticsearch.xpack.prelert.job.persistence.InfluencersQueryBuilder.InfluencersQuery;
import org.elasticsearch.xpack.prelert.job.quantiles.Quantiles;
import org.elasticsearch.xpack.prelert.job.results.AnomalyRecord;
import org.elasticsearch.xpack.prelert.job.results.Bucket;
import org.elasticsearch.xpack.prelert.job.results.CategoryDefinition;
import org.elasticsearch.xpack.prelert.job.results.Influencer;
import org.elasticsearch.xpack.prelert.job.results.PerPartitionMaxProbabilities;
import org.elasticsearch.xpack.prelert.job.results.Result;
import org.elasticsearch.xpack.prelert.job.usage.Usage;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.elasticsearch.xpack.prelert.job.JobTests.buildJobBuilder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class JobProviderTests extends ESTestCase {
    private static final String CLUSTER_NAME = "myCluster";
    private static final String JOB_ID = "foo";
    private static final String STATE_INDEX_NAME = ".ml-state";

    @Captor
    private ArgumentCaptor<Map<String, Object>> mapCaptor;

    public void testGetQuantiles_GivenNoQuantilesForJob() throws Exception {
        GetResponse getResponse = createGetResponse(false, null);

        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME).addClusterStatusYellowResponse()
                .prepareGet(STATE_INDEX_NAME, Quantiles.TYPE.getPreferredName(), Quantiles.quantilesId(JOB_ID), getResponse);

        JobProvider provider = createProvider(clientBuilder.build());

        Optional<Quantiles> quantiles = provider.getQuantiles(JOB_ID);

        assertFalse(quantiles.isPresent());
    }

    public void testGetQuantiles_GivenQuantilesHaveNonEmptyState() throws Exception {
        Map<String, Object> source = new HashMap<>();
        source.put(Job.ID.getPreferredName(), "foo");
        source.put(Quantiles.TIMESTAMP.getPreferredName(), 0L);
        source.put(Quantiles.QUANTILE_STATE.getPreferredName(), "state");
        GetResponse getResponse = createGetResponse(true, source);

        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME).addClusterStatusYellowResponse()
                .prepareGet(STATE_INDEX_NAME, Quantiles.TYPE.getPreferredName(), Quantiles.quantilesId(JOB_ID), getResponse);

        JobProvider provider = createProvider(clientBuilder.build());

        Optional<Quantiles> quantiles = provider.getQuantiles(JOB_ID);

        assertTrue(quantiles.isPresent());
        assertEquals("state", quantiles.get().getQuantileState());
    }

    public void testGetQuantiles_GivenQuantilesHaveEmptyState() throws Exception {
        Map<String, Object> source = new HashMap<>();
        source.put(Job.ID.getPreferredName(), "foo");
        source.put(Quantiles.TIMESTAMP.getPreferredName(), new Date(0L).getTime());
        source.put(Quantiles.QUANTILE_STATE.getPreferredName(), "");
        GetResponse getResponse = createGetResponse(true, source);

        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME).addClusterStatusYellowResponse()
                .prepareGet(STATE_INDEX_NAME, Quantiles.TYPE.getPreferredName(), Quantiles.quantilesId("foo"), getResponse);

        JobProvider provider = createProvider(clientBuilder.build());

        Optional<Quantiles> quantiles = provider.getQuantiles(JOB_ID);

        assertTrue(quantiles.isPresent());
        assertEquals("", quantiles.get().getQuantileState());
    }

    public void testCreateUsageMetering() throws InterruptedException, ExecutionException {
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME).addClusterStatusYellowResponse()
                .addIndicesExistsResponse(JobProvider.PRELERT_USAGE_INDEX, false)
                .prepareCreate(JobProvider.PRELERT_USAGE_INDEX)
                .addClusterStatusYellowResponse(JobProvider.PRELERT_USAGE_INDEX);
        Client client = clientBuilder.build();
        JobProvider provider = createProvider(client);
        provider.createUsageMeteringIndex((result, error) -> logger.info("result={}", result));
        clientBuilder.verifyIndexCreated(JobProvider.PRELERT_USAGE_INDEX);
    }

    public void testMlResultsIndexSettings() {
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME);
        JobProvider provider = createProvider(clientBuilder.build());
        Settings settings = provider.mlResultsIndexSettings().build();

        assertEquals("1", settings.get("index.number_of_shards"));
        assertEquals("0", settings.get("index.number_of_replicas"));
        assertEquals("async", settings.get("index.translog.durability"));
        assertEquals("true", settings.get("index.mapper.dynamic"));
        assertEquals("all_field_values", settings.get("index.query.default_field"));
    }

    public void testCreateJobResultsIndex() {
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME);
        ArgumentCaptor<CreateIndexRequest> captor = ArgumentCaptor.forClass(CreateIndexRequest.class);
        clientBuilder.createIndexRequest(AnomalyDetectorsIndex.jobResultsIndexName("foo"), captor);

        Job.Builder job = buildJobBuilder("foo");
        JobProvider provider = createProvider(clientBuilder.build());

        provider.createJobResultIndex(job.build(), new ActionListener<Boolean>() {
            @Override
            public void onResponse(Boolean aBoolean) {
                CreateIndexRequest request = captor.getValue();
                assertNotNull(request);
                assertEquals(provider.mlResultsIndexSettings().build(), request.settings());
                assertTrue(request.mappings().containsKey(Result.TYPE.getPreferredName()));
                assertTrue(request.mappings().containsKey(CategoryDefinition.TYPE.getPreferredName()));
                assertTrue(request.mappings().containsKey(DataCounts.TYPE.getPreferredName()));
                assertTrue(request.mappings().containsKey(Usage.TYPE));
                assertTrue(request.mappings().containsKey(AuditMessage.TYPE.getPreferredName()));
                assertTrue(request.mappings().containsKey(AuditActivity.TYPE.getPreferredName()));
                assertTrue(request.mappings().containsKey(ModelSnapshot.TYPE.getPreferredName()));
                assertEquals(7, request.mappings().size());
            }

            @Override
            public void onFailure(Exception e) {
                fail(e.toString());
            }
        });
    }

    public void testCreateJobRelatedIndicies_createsAliasIfIndexNameIsSet() {
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME);
        ArgumentCaptor<CreateIndexRequest> captor = ArgumentCaptor.forClass(CreateIndexRequest.class);
        clientBuilder.createIndexRequest(AnomalyDetectorsIndex.jobResultsIndexName("foo"), captor);
        clientBuilder.prepareAlias(AnomalyDetectorsIndex.jobResultsIndexName("bar"), AnomalyDetectorsIndex.jobResultsIndexName("foo"));

        Job.Builder job = buildJobBuilder("foo");
        job.setIndexName("bar");
        Client client = clientBuilder.build();
        JobProvider provider = createProvider(client);

        provider.createJobResultIndex(job.build(), new ActionListener<Boolean>() {
            @Override
            public void onResponse(Boolean aBoolean) {
                verify(client.admin().indices(), times(1)).prepareAliases();
            }

            @Override
            public void onFailure(Exception e) {
                fail(e.toString());
            }
        });
    }


    public void testCreateJobRelatedIndicies_doesntCreateAliasIfIndexNameIsSameAsJobId() {
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME);
        ArgumentCaptor<CreateIndexRequest> captor = ArgumentCaptor.forClass(CreateIndexRequest.class);
        clientBuilder.createIndexRequest(AnomalyDetectorsIndex.jobResultsIndexName("foo"), captor);

        Job.Builder job = buildJobBuilder("foo");
        job.setIndexName("foo");
        Client client = clientBuilder.build();
        JobProvider provider = createProvider(client);

        provider.createJobResultIndex(job.build(), new ActionListener<Boolean>() {
            @Override
            public void onResponse(Boolean aBoolean) {
                verify(client.admin().indices(), never()).prepareAliases();
            }

            @Override
            public void onFailure(Exception e) {
                fail(e.toString());
            }
        });
    }

    public void testMlStateIndexSettings() {
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME);
        JobProvider provider = createProvider(clientBuilder.build());
        Settings settings = provider.mlResultsIndexSettings().build();

        assertEquals("1", settings.get("index.number_of_shards"));
        assertEquals("0", settings.get("index.number_of_replicas"));
        assertEquals("async", settings.get("index.translog.durability"));
    }

    public void testCreateJobStateIndex() {
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME);
        ArgumentCaptor<CreateIndexRequest> captor = ArgumentCaptor.forClass(CreateIndexRequest.class);
        clientBuilder.createIndexRequest(AnomalyDetectorsIndex.jobStateIndexName(), captor);

        Job.Builder job = buildJobBuilder("foo");
        JobProvider provider = createProvider(clientBuilder.build());

        provider.createJobStateIndex((result, error) -> {
                assertTrue(result);
                CreateIndexRequest request = captor.getValue();
                assertNotNull(request);
                assertEquals(provider.mlStateIndexSettings().build(), request.settings());
                assertTrue(request.mappings().containsKey(CategorizerState.TYPE));
                assertTrue(request.mappings().containsKey(Quantiles.TYPE.getPreferredName()));
                assertTrue(request.mappings().containsKey(ModelState.TYPE.getPreferredName()));
                assertEquals(3, request.mappings().size());
            });
    }

    public void testCreateJob() throws InterruptedException, ExecutionException {
        Job.Builder job = buildJobBuilder("marscapone");
        job.setDescription("This is a very cheesy job");
        AnalysisLimits limits = new AnalysisLimits(9878695309134L, null);
        job.setAnalysisLimits(limits);

        ArgumentCaptor<CreateIndexRequest> captor = ArgumentCaptor.forClass(CreateIndexRequest.class);
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME)
                .createIndexRequest(AnomalyDetectorsIndex.jobResultsIndexName(job.getId()), captor);

        Client client = clientBuilder.build();
        JobProvider provider = createProvider(client);
        AtomicReference<Boolean> resultHolder = new AtomicReference<>();
        provider.createJobResultIndex(job.build(), new ActionListener<Boolean>() {
            @Override
            public void onResponse(Boolean aBoolean) {
                resultHolder.set(aBoolean);
            }

            @Override
            public void onFailure(Exception e) {

            }
        });
        assertNotNull(resultHolder.get());
        assertTrue(resultHolder.get());
    }

    public void testDeleteJob() throws InterruptedException, ExecutionException, IOException {
        @SuppressWarnings("unchecked")
        ActionListener<DeleteJobAction.Response> actionListener = mock(ActionListener.class);
        String jobId = "ThisIsMyJob";
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME).addClusterStatusYellowResponse();
        Client client = clientBuilder.build();
        JobProvider provider = createProvider(client);
        clientBuilder.resetIndices();
        clientBuilder.addIndicesExistsResponse(AnomalyDetectorsIndex.jobResultsIndexName(jobId), true)
                .addIndicesDeleteResponse(AnomalyDetectorsIndex.jobResultsIndexName(jobId), true,
                false, actionListener);
        clientBuilder.build();

        provider.deleteJobRelatedIndices(jobId, actionListener);

        ArgumentCaptor<DeleteJobAction.Response> responseCaptor = ArgumentCaptor.forClass(DeleteJobAction.Response.class);
        verify(actionListener).onResponse(responseCaptor.capture());
        assertTrue(responseCaptor.getValue().isAcknowledged());
    }

    public void testDeleteJob_InvalidIndex() throws InterruptedException, ExecutionException, IOException {
        @SuppressWarnings("unchecked")
        ActionListener<DeleteJobAction.Response> actionListener = mock(ActionListener.class);
        String jobId = "ThisIsMyJob";
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME).addClusterStatusYellowResponse();
        Client client = clientBuilder.build();
        JobProvider provider = createProvider(client);
        clientBuilder.resetIndices();
        clientBuilder.addIndicesExistsResponse(AnomalyDetectorsIndex.jobResultsIndexName(jobId), true)
                .addIndicesDeleteResponse(AnomalyDetectorsIndex.jobResultsIndexName(jobId), true,
                true, actionListener);
        clientBuilder.build();

        provider.deleteJobRelatedIndices(jobId, actionListener);

        ArgumentCaptor<Exception> exceptionCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(actionListener).onFailure(exceptionCaptor.capture());
        assertThat(exceptionCaptor.getValue(), instanceOf(InterruptedException.class));
    }

    public void testBuckets_OneBucketNoInterim()
            throws InterruptedException, ExecutionException, IOException {
        String jobId = "TestJobIdentification";
        Date now = new Date();
        List<Map<String, Object>> source = new ArrayList<>();

        Map<String, Object> map = new HashMap<>();
        map.put("job_id", "foo");
        map.put("timestamp", now.getTime());
        map.put("bucket_span", 22);
        source.add(map);

        ArgumentCaptor<QueryBuilder> queryBuilder = ArgumentCaptor.forClass(QueryBuilder.class);
        SearchResponse response = createSearchResponse(true, source);
        int from = 0;
        int size = 10;
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME).addClusterStatusYellowResponse()
                .prepareSearch(AnomalyDetectorsIndex.jobResultsIndexName(jobId),
                        Result.TYPE.getPreferredName(), from, size, response, queryBuilder);

        Client client = clientBuilder.build();
        JobProvider provider = createProvider(client);

        BucketsQueryBuilder bq = new BucketsQueryBuilder().from(from).size(size).anomalyScoreThreshold(0.0)
                .normalizedProbabilityThreshold(1.0);

        QueryPage<Bucket> buckets = provider.buckets(jobId, bq.build());
        assertEquals(1L, buckets.count());
        QueryBuilder query = queryBuilder.getValue();
        String queryString = query.toString();
        assertTrue(
                queryString.matches("(?s).*max_normalized_probability[^}]*from. : 1\\.0.*must_not[^}]*term[^}]*is_interim.*value. : .true" +
                        ".*"));
    }

    public void testBuckets_OneBucketInterim()
            throws InterruptedException, ExecutionException, IOException {
        String jobId = "TestJobIdentification";
        Date now = new Date();
        List<Map<String, Object>> source = new ArrayList<>();

        Map<String, Object> map = new HashMap<>();
        map.put("job_id", "foo");
        map.put("timestamp", now.getTime());
        map.put("bucket_span", 22);
        source.add(map);

        ArgumentCaptor<QueryBuilder> queryBuilder = ArgumentCaptor.forClass(QueryBuilder.class);
        SearchResponse response = createSearchResponse(true, source);
        int from = 99;
        int size = 17;
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME).addClusterStatusYellowResponse()
                .prepareSearch(AnomalyDetectorsIndex.jobResultsIndexName(jobId),
                        Result.TYPE.getPreferredName(), from, size, response, queryBuilder);

        Client client = clientBuilder.build();
        JobProvider provider = createProvider(client);

        BucketsQueryBuilder bq = new BucketsQueryBuilder().from(from).size(size).anomalyScoreThreshold(5.1)
                .normalizedProbabilityThreshold(10.9).includeInterim(true);

        QueryPage<Bucket> buckets = provider.buckets(jobId, bq.build());
        assertEquals(1L, buckets.count());
        QueryBuilder query = queryBuilder.getValue();
        String queryString = query.toString();
        assertTrue(queryString.matches("(?s).*max_normalized_probability[^}]*from. : 10\\.9.*"));
        assertTrue(queryString.matches("(?s).*anomaly_score[^}]*from. : 5\\.1.*"));
        assertFalse(queryString.matches("(?s).*is_interim.*"));
    }

    public void testBuckets_UsingBuilder()
            throws InterruptedException, ExecutionException, IOException {
        String jobId = "TestJobIdentification";
        Date now = new Date();
        List<Map<String, Object>> source = new ArrayList<>();

        Map<String, Object> map = new HashMap<>();
        map.put("job_id", "foo");
        map.put("timestamp", now.getTime());
        map.put("bucket_span", 22);
        source.add(map);

        ArgumentCaptor<QueryBuilder> queryBuilder = ArgumentCaptor.forClass(QueryBuilder.class);
        SearchResponse response = createSearchResponse(true, source);
        int from = 99;
        int size = 17;
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME).addClusterStatusYellowResponse()
                .prepareSearch(AnomalyDetectorsIndex.jobResultsIndexName(jobId),
                        Result.TYPE.getPreferredName(), from, size, response, queryBuilder);

        Client client = clientBuilder.build();
        JobProvider provider = createProvider(client);

        BucketsQueryBuilder bq = new BucketsQueryBuilder();
        bq.from(from);
        bq.size(size);
        bq.anomalyScoreThreshold(5.1);
        bq.normalizedProbabilityThreshold(10.9);
        bq.includeInterim(true);

        QueryPage<Bucket> buckets = provider.buckets(jobId, bq.build());
        assertEquals(1L, buckets.count());
        QueryBuilder query = queryBuilder.getValue();
        String queryString = query.toString();
        assertTrue(queryString.matches("(?s).*max_normalized_probability[^}]*from. : 10\\.9.*"));
        assertTrue(queryString.matches("(?s).*anomaly_score[^}]*from. : 5\\.1.*"));
        assertFalse(queryString.matches("(?s).*is_interim.*"));
    }

    public void testBucket_NoBucketNoExpandNoInterim()
            throws InterruptedException, ExecutionException, IOException {
        String jobId = "TestJobIdentification";
        Long timestamp = 98765432123456789L;
        Date now = new Date();
        List<Map<String, Object>> source = new ArrayList<>();

        ArgumentCaptor<QueryBuilder> queryBuilder = ArgumentCaptor.forClass(QueryBuilder.class);
        SearchResponse response = createSearchResponse(false, source);
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME).addClusterStatusYellowResponse()
                .prepareSearch(AnomalyDetectorsIndex.jobResultsIndexName(jobId),
                        Result.TYPE.getPreferredName(), 0, 0, response, queryBuilder);

        Client client = clientBuilder.build();
        JobProvider provider = createProvider(client);

        BucketQueryBuilder bq = new BucketQueryBuilder(Long.toString(timestamp));

        expectThrows(ResourceNotFoundException.class,
                () -> provider.bucket(jobId, bq.build()));
    }

    public void testBucket_OneBucketNoExpandNoInterim()
            throws InterruptedException, ExecutionException, IOException {
        String jobId = "TestJobIdentification";
        Date now = new Date();
        List<Map<String, Object>> source = new ArrayList<>();

        Map<String, Object> map = new HashMap<>();
        map.put("job_id", "foo");
        map.put("timestamp", now.getTime());
        map.put("bucket_span", 22);
        source.add(map);

        ArgumentCaptor<QueryBuilder> queryBuilder = ArgumentCaptor.forClass(QueryBuilder.class);
        SearchResponse response = createSearchResponse(true, source);
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME).addClusterStatusYellowResponse()
                .prepareSearch(AnomalyDetectorsIndex.jobResultsIndexName(jobId),
                        Result.TYPE.getPreferredName(), 0, 0, response, queryBuilder);

        Client client = clientBuilder.build();
        JobProvider provider = createProvider(client);

        BucketQueryBuilder bq = new BucketQueryBuilder(Long.toString(now.getTime()));

        QueryPage<Bucket> bucketHolder = provider.bucket(jobId, bq.build());
        assertThat(bucketHolder.count(), equalTo(1L));
        Bucket b = bucketHolder.results().get(0);
        assertEquals(now, b.getTimestamp());
    }

    public void testBucket_OneBucketNoExpandInterim()
            throws InterruptedException, ExecutionException, IOException {
        String jobId = "TestJobIdentification";
        Date now = new Date();
        List<Map<String, Object>> source = new ArrayList<>();

        Map<String, Object> map = new HashMap<>();
        map.put("job_id", "foo");
        map.put("timestamp", now.getTime());
        map.put("bucket_span", 22);
        map.put("is_interim", true);
        source.add(map);

        ArgumentCaptor<QueryBuilder> queryBuilder = ArgumentCaptor.forClass(QueryBuilder.class);
        SearchResponse response = createSearchResponse(true, source);
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME).addClusterStatusYellowResponse()
                .prepareSearch(AnomalyDetectorsIndex.jobResultsIndexName(jobId),
                        Result.TYPE.getPreferredName(), 0, 0, response, queryBuilder);

        Client client = clientBuilder.build();
        JobProvider provider = createProvider(client);

        BucketQueryBuilder bq = new BucketQueryBuilder(Long.toString(now.getTime()));

        expectThrows(ResourceNotFoundException.class,
                () -> provider.bucket(jobId, bq.build()));
    }

    public void testRecords() throws InterruptedException, ExecutionException, IOException {
        String jobId = "TestJobIdentification";
        Date now = new Date();
        List<Map<String, Object>> source = new ArrayList<>();

        Map<String, Object> recordMap1 = new HashMap<>();
        recordMap1.put("job_id", "foo");
        recordMap1.put("typical", 22.4);
        recordMap1.put("actual", 33.3);
        recordMap1.put("timestamp", now.getTime());
        recordMap1.put("function", "irritable");
        recordMap1.put("bucket_span", 22);
        recordMap1.put("sequence_num", 1);
        Map<String, Object> recordMap2 = new HashMap<>();
        recordMap2.put("job_id", "foo");
        recordMap2.put("typical", 1122.4);
        recordMap2.put("actual", 933.3);
        recordMap2.put("timestamp", now.getTime());
        recordMap2.put("function", "irrascible");
        recordMap2.put("bucket_span", 22);
        recordMap2.put("sequence_num", 2);
        source.add(recordMap1);
        source.add(recordMap2);

        int from = 14;
        int size = 2;
        String sortfield = "minefield";
        ArgumentCaptor<QueryBuilder> queryBuilder = ArgumentCaptor.forClass(QueryBuilder.class);
        SearchResponse response = createSearchResponse(true, source);
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME).addClusterStatusYellowResponse()
                .prepareSearch(AnomalyDetectorsIndex.jobResultsIndexName(jobId),
                        Result.TYPE.getPreferredName(), from, size, response, queryBuilder);

        Client client = clientBuilder.build();
        JobProvider provider = createProvider(client);

        RecordsQueryBuilder rqb = new RecordsQueryBuilder().from(from).size(size).epochStart(String.valueOf(now.getTime()))
                .epochEnd(String.valueOf(now.getTime())).includeInterim(true).sortField(sortfield).anomalyScoreThreshold(11.1)
                .normalizedProbability(2.2);

        QueryPage<AnomalyRecord> recordPage = provider.records(jobId, rqb.build());
        assertEquals(2L, recordPage.count());
        List<AnomalyRecord> records = recordPage.results();
        assertEquals(22.4, records.get(0).getTypical().get(0), 0.000001);
        assertEquals(33.3, records.get(0).getActual().get(0), 0.000001);
        assertEquals("irritable", records.get(0).getFunction());
        assertEquals(1122.4, records.get(1).getTypical().get(0), 0.000001);
        assertEquals(933.3, records.get(1).getActual().get(0), 0.000001);
        assertEquals("irrascible", records.get(1).getFunction());
    }

    public void testRecords_UsingBuilder()
            throws InterruptedException, ExecutionException, IOException {
        String jobId = "TestJobIdentification";
        Date now = new Date();
        List<Map<String, Object>> source = new ArrayList<>();

        Map<String, Object> recordMap1 = new HashMap<>();
        recordMap1.put("job_id", "foo");
        recordMap1.put("typical", 22.4);
        recordMap1.put("actual", 33.3);
        recordMap1.put("timestamp", now.getTime());
        recordMap1.put("function", "irritable");
        recordMap1.put("bucket_span", 22);
        recordMap1.put("sequence_num", 1);
        Map<String, Object> recordMap2 = new HashMap<>();
        recordMap2.put("job_id", "foo");
        recordMap2.put("typical", 1122.4);
        recordMap2.put("actual", 933.3);
        recordMap2.put("timestamp", now.getTime());
        recordMap2.put("function", "irrascible");
        recordMap2.put("bucket_span", 22);
        recordMap2.put("sequence_num", 2);
        source.add(recordMap1);
        source.add(recordMap2);

        int from = 14;
        int size = 2;
        String sortfield = "minefield";
        ArgumentCaptor<QueryBuilder> queryBuilder = ArgumentCaptor.forClass(QueryBuilder.class);
        SearchResponse response = createSearchResponse(true, source);
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME).addClusterStatusYellowResponse()
                .prepareSearch(AnomalyDetectorsIndex.jobResultsIndexName(jobId),
                        Result.TYPE.getPreferredName(), from, size, response, queryBuilder);

        Client client = clientBuilder.build();
        JobProvider provider = createProvider(client);

        RecordsQueryBuilder rqb = new RecordsQueryBuilder();
        rqb.from(from);
        rqb.size(size);
        rqb.epochStart(String.valueOf(now.getTime()));
        rqb.epochEnd(String.valueOf(now.getTime()));
        rqb.includeInterim(true);
        rqb.sortField(sortfield);
        rqb.anomalyScoreThreshold(11.1);
        rqb.normalizedProbability(2.2);

        QueryPage<AnomalyRecord> recordPage = provider.records(jobId, rqb.build());
        assertEquals(2L, recordPage.count());
        List<AnomalyRecord> records = recordPage.results();
        assertEquals(22.4, records.get(0).getTypical().get(0), 0.000001);
        assertEquals(33.3, records.get(0).getActual().get(0), 0.000001);
        assertEquals("irritable", records.get(0).getFunction());
        assertEquals(1122.4, records.get(1).getTypical().get(0), 0.000001);
        assertEquals(933.3, records.get(1).getActual().get(0), 0.000001);
        assertEquals("irrascible", records.get(1).getFunction());
    }

    public void testBucketRecords() throws InterruptedException, ExecutionException, IOException {
        String jobId = "TestJobIdentification";
        Date now = new Date();
        Bucket bucket = mock(Bucket.class);
        when(bucket.getTimestamp()).thenReturn(now);

        List<Map<String, Object>> source = new ArrayList<>();
        Map<String, Object> recordMap1 = new HashMap<>();
        recordMap1.put("job_id", "foo");
        recordMap1.put("typical", 22.4);
        recordMap1.put("actual", 33.3);
        recordMap1.put("timestamp", now.getTime());
        recordMap1.put("function", "irritable");
        recordMap1.put("bucket_span", 22);
        recordMap1.put("sequence_num", 1);
        Map<String, Object> recordMap2 = new HashMap<>();
        recordMap2.put("job_id", "foo");
        recordMap2.put("typical", 1122.4);
        recordMap2.put("actual", 933.3);
        recordMap2.put("timestamp", now.getTime());
        recordMap2.put("function", "irrascible");
        recordMap2.put("bucket_span", 22);
        recordMap2.put("sequence_num", 2);
        source.add(recordMap1);
        source.add(recordMap2);

        int from = 14;
        int size = 2;
        String sortfield = "minefield";
        ArgumentCaptor<QueryBuilder> queryBuilder = ArgumentCaptor.forClass(QueryBuilder.class);
        SearchResponse response = createSearchResponse(true, source);
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME).addClusterStatusYellowResponse()
                .prepareSearch(AnomalyDetectorsIndex.jobResultsIndexName(jobId),
                        Result.TYPE.getPreferredName(), from, size, response, queryBuilder);

        Client client = clientBuilder.build();
        JobProvider provider = createProvider(client);

        QueryPage<AnomalyRecord> recordPage = provider.bucketRecords(jobId, bucket, from, size, true, sortfield, true, "");

        assertEquals(2L, recordPage.count());
        List<AnomalyRecord> records = recordPage.results();
        assertEquals(22.4, records.get(0).getTypical().get(0), 0.000001);
        assertEquals(33.3, records.get(0).getActual().get(0), 0.000001);
        assertEquals("irritable", records.get(0).getFunction());
        assertEquals(1122.4, records.get(1).getTypical().get(0), 0.000001);
        assertEquals(933.3, records.get(1).getActual().get(0), 0.000001);
        assertEquals("irrascible", records.get(1).getFunction());
    }

    public void testexpandBucket() throws InterruptedException, ExecutionException, IOException {
        String jobId = "TestJobIdentification";
        Date now = new Date();
        Bucket bucket = new Bucket("foo", now, 22);

        List<Map<String, Object>> source = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            Map<String, Object> recordMap = new HashMap<>();
            recordMap.put("job_id", "foo");
            recordMap.put("typical", 22.4 + i);
            recordMap.put("actual", 33.3 + i);
            recordMap.put("timestamp", now.getTime());
            recordMap.put("function", "irritable");
            recordMap.put("bucket_span", 22);
            recordMap.put("sequence_num", i + 1);
            source.add(recordMap);
        }

        ArgumentCaptor<QueryBuilder> queryBuilder = ArgumentCaptor.forClass(QueryBuilder.class);
        SearchResponse response = createSearchResponse(true, source);
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME).addClusterStatusYellowResponse()
                .prepareSearchAnySize(AnomalyDetectorsIndex.jobResultsIndexName(jobId),
                        Result.TYPE.getPreferredName(), response, queryBuilder);

        Client client = clientBuilder.build();
        JobProvider provider = createProvider(client);

        int records = provider.expandBucket(jobId, false, bucket);
        assertEquals(400L, records);
    }

    public void testexpandBucket_WithManyRecords()
            throws InterruptedException, ExecutionException, IOException {
        String jobId = "TestJobIdentification";
        Date now = new Date();
        Bucket bucket = new Bucket("foo", now, 22);

        List<Map<String, Object>> source = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            Map<String, Object> recordMap = new HashMap<>();
            recordMap.put("job_id", "foo");
            recordMap.put("typical", 22.4 + i);
            recordMap.put("actual", 33.3 + i);
            recordMap.put("timestamp", now.getTime());
            recordMap.put("function", "irritable");
            recordMap.put("bucket_span", 22);
            recordMap.put("sequence_num", i + 1);
            source.add(recordMap);
        }

        ArgumentCaptor<QueryBuilder> queryBuilder = ArgumentCaptor.forClass(QueryBuilder.class);
        SearchResponse response = createSearchResponse(true, source);
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME).addClusterStatusYellowResponse()
                .prepareSearchAnySize(AnomalyDetectorsIndex.jobResultsIndexName(jobId),
                        Result.TYPE.getPreferredName(), response, queryBuilder);

        Client client = clientBuilder.build();
        JobProvider provider = createProvider(client);

        int records = provider.expandBucket(jobId, false, bucket);
        // This is not realistic, but is an artifact of the fact that the mock
        // query
        // returns all the records, not a subset
        assertEquals(1200L, records);
    }

    public void testCategoryDefinitions()
            throws InterruptedException, ExecutionException, IOException {
        String jobId = "TestJobIdentification";
        String terms = "the terms and conditions are not valid here";
        List<Map<String, Object>> source = new ArrayList<>();

        Map<String, Object> map = new HashMap<>();
        map.put("job_id", "foo");
        map.put("category_id", String.valueOf(map.hashCode()));
        map.put("terms", terms);

        source.add(map);

        ArgumentCaptor<QueryBuilder> queryBuilder = ArgumentCaptor.forClass(QueryBuilder.class);
        SearchResponse response = createSearchResponse(true, source);
        int from = 0;
        int size = 10;
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME).addClusterStatusYellowResponse()
                .prepareSearch(AnomalyDetectorsIndex.jobResultsIndexName(jobId),
                        CategoryDefinition.TYPE.getPreferredName(), from, size, response, queryBuilder);

        Client client = clientBuilder.build();
        JobProvider provider = createProvider(client);
        QueryPage<CategoryDefinition> categoryDefinitions = provider.categoryDefinitions(jobId, from, size);
        assertEquals(1L, categoryDefinitions.count());
        assertEquals(terms, categoryDefinitions.results().get(0).getTerms());
    }

    public void testCategoryDefinition()
            throws InterruptedException, ExecutionException, IOException {
        String jobId = "TestJobIdentification";
        String terms = "the terms and conditions are not valid here";

        Map<String, Object> source = new HashMap<>();
        String categoryId = String.valueOf(source.hashCode());
        source.put("job_id", "foo");
        source.put("category_id", categoryId);
        source.put("terms", terms);

        GetResponse getResponse = createGetResponse(true, source);

        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME).addClusterStatusYellowResponse()
                .prepareGet(AnomalyDetectorsIndex.jobResultsIndexName(jobId),
                        CategoryDefinition.TYPE.getPreferredName(), categoryId, getResponse);

        Client client = clientBuilder.build();
        JobProvider provider = createProvider(client);
        QueryPage<CategoryDefinition> categoryDefinitions = provider.categoryDefinition(jobId, categoryId);
        assertEquals(1L, categoryDefinitions.count());
        assertEquals(terms, categoryDefinitions.results().get(0).getTerms());
    }

    public void testInfluencers_NoInterim()
            throws InterruptedException, ExecutionException, IOException {
        String jobId = "TestJobIdentificationForInfluencers";
        Date now = new Date();
        List<Map<String, Object>> source = new ArrayList<>();

        Map<String, Object> recordMap1 = new HashMap<>();
        recordMap1.put("job_id", "foo");
        recordMap1.put("probability", 0.555);
        recordMap1.put("influencer_field_name", "Builder");
        recordMap1.put("timestamp", now.getTime());
        recordMap1.put("influencer_field_value", "Bob");
        recordMap1.put("initial_anomaly_score", 22.2);
        recordMap1.put("anomaly_score", 22.6);
        recordMap1.put("bucket_span", 123);
        recordMap1.put("sequence_num", 1);
        Map<String, Object> recordMap2 = new HashMap<>();
        recordMap2.put("job_id", "foo");
        recordMap2.put("probability", 0.99);
        recordMap2.put("influencer_field_name", "Builder");
        recordMap2.put("timestamp", now.getTime());
        recordMap2.put("influencer_field_value", "James");
        recordMap2.put("initial_anomaly_score", 5.0);
        recordMap2.put("anomaly_score", 5.0);
        recordMap2.put("bucket_span", 123);
        recordMap2.put("sequence_num", 2);
        source.add(recordMap1);
        source.add(recordMap2);

        int from = 4;
        int size = 3;
        ArgumentCaptor<QueryBuilder> queryBuilder = ArgumentCaptor.forClass(QueryBuilder.class);
        SearchResponse response = createSearchResponse(true, source);
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME).addClusterStatusYellowResponse()
                .prepareSearch(AnomalyDetectorsIndex.jobResultsIndexName(jobId), Result.TYPE.getPreferredName(),
                        from, size, response, queryBuilder);

        Client client = clientBuilder.build();
        JobProvider provider = createProvider(client);

        InfluencersQuery query = new InfluencersQueryBuilder().from(from).size(size).includeInterim(false).build();
        QueryPage<Influencer> page = provider.influencers(jobId, query);
        assertEquals(2L, page.count());

        String queryString = queryBuilder.getValue().toString();
        assertTrue(queryString.matches("(?s).*must_not[^}]*term[^}]*is_interim.*value. : .true.*"));

        List<Influencer> records = page.results();
        assertEquals("foo", records.get(0).getJobId());
        assertEquals("Bob", records.get(0).getInfluencerFieldValue());
        assertEquals("Builder", records.get(0).getInfluencerFieldName());
        assertEquals(now, records.get(0).getTimestamp());
        assertEquals(0.555, records.get(0).getProbability(), 0.00001);
        assertEquals(22.6, records.get(0).getAnomalyScore(), 0.00001);
        assertEquals(22.2, records.get(0).getInitialAnomalyScore(), 0.00001);

        assertEquals("James", records.get(1).getInfluencerFieldValue());
        assertEquals("Builder", records.get(1).getInfluencerFieldName());
        assertEquals(now, records.get(1).getTimestamp());
        assertEquals(0.99, records.get(1).getProbability(), 0.00001);
        assertEquals(5.0, records.get(1).getAnomalyScore(), 0.00001);
        assertEquals(5.0, records.get(1).getInitialAnomalyScore(), 0.00001);
    }

    public void testInfluencers_WithInterim()
            throws InterruptedException, ExecutionException, IOException {
        String jobId = "TestJobIdentificationForInfluencers";
        Date now = new Date();
        List<Map<String, Object>> source = new ArrayList<>();

        Map<String, Object> recordMap1 = new HashMap<>();
        recordMap1.put("job_id", "foo");
        recordMap1.put("probability", 0.555);
        recordMap1.put("influencer_field_name", "Builder");
        recordMap1.put("timestamp", now.getTime());
        recordMap1.put("influencer_field_value", "Bob");
        recordMap1.put("initial_anomaly_score", 22.2);
        recordMap1.put("anomaly_score", 22.6);
        recordMap1.put("bucket_span", 123);
        recordMap1.put("sequence_num", 1);
        Map<String, Object> recordMap2 = new HashMap<>();
        recordMap2.put("job_id", "foo");
        recordMap2.put("probability", 0.99);
        recordMap2.put("influencer_field_name", "Builder");
        recordMap2.put("timestamp", now.getTime());
        recordMap2.put("influencer_field_value", "James");
        recordMap2.put("initial_anomaly_score", 5.0);
        recordMap2.put("anomaly_score", 5.0);
        recordMap2.put("bucket_span", 123);
        recordMap2.put("sequence_num", 2);
        source.add(recordMap1);
        source.add(recordMap2);

        int from = 4;
        int size = 3;
        ArgumentCaptor<QueryBuilder> queryBuilder = ArgumentCaptor.forClass(QueryBuilder.class);
        SearchResponse response = createSearchResponse(true, source);
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME).addClusterStatusYellowResponse()
                .prepareSearch(AnomalyDetectorsIndex.jobResultsIndexName(jobId), Result.TYPE.getPreferredName(), from, size, response,
                        queryBuilder);

        Client client = clientBuilder.build();
        JobProvider provider = createProvider(client);

        InfluencersQuery query = new InfluencersQueryBuilder().from(from).size(size).start("0").end("0").sortField("sort")
                .sortDescending(true).anomalyScoreThreshold(0.0).includeInterim(true).build();
        QueryPage<Influencer> page = provider.influencers(jobId, query);
        assertEquals(2L, page.count());

        String queryString = queryBuilder.getValue().toString();
        assertFalse(queryString.matches("(?s).*isInterim.*"));

        List<Influencer> records = page.results();
        assertEquals("Bob", records.get(0).getInfluencerFieldValue());
        assertEquals("Builder", records.get(0).getInfluencerFieldName());
        assertEquals(now, records.get(0).getTimestamp());
        assertEquals(0.555, records.get(0).getProbability(), 0.00001);
        assertEquals(22.6, records.get(0).getAnomalyScore(), 0.00001);
        assertEquals(22.2, records.get(0).getInitialAnomalyScore(), 0.00001);

        assertEquals("James", records.get(1).getInfluencerFieldValue());
        assertEquals("Builder", records.get(1).getInfluencerFieldName());
        assertEquals(now, records.get(1).getTimestamp());
        assertEquals(0.99, records.get(1).getProbability(), 0.00001);
        assertEquals(5.0, records.get(1).getAnomalyScore(), 0.00001);
        assertEquals(5.0, records.get(1).getInitialAnomalyScore(), 0.00001);
    }

    public void testInfluencer() throws InterruptedException, ExecutionException, IOException {
        String jobId = "TestJobIdentificationForInfluencers";
        String influencerId = "ThisIsAnInfluencerId";

        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME).addClusterStatusYellowResponse();

        Client client = clientBuilder.build();
        JobProvider provider = createProvider(client);

        try {
            provider.influencer(jobId, influencerId);
            assertTrue(false);
        } catch (IllegalStateException e) {
        }
    }

    public void testModelSnapshots() throws InterruptedException, ExecutionException, IOException {
        String jobId = "TestJobIdentificationForInfluencers";
        Date now = new Date();
        List<Map<String, Object>> source = new ArrayList<>();

        Map<String, Object> recordMap1 = new HashMap<>();
        recordMap1.put("job_id", "foo");
        recordMap1.put("description", "snapshot1");
        recordMap1.put("restore_priority", 1);
        recordMap1.put("timestamp", now.getTime());
        recordMap1.put("snapshot_doc_count", 5);
        recordMap1.put("latest_record_time_stamp", now.getTime());
        recordMap1.put("latest_result_time_stamp", now.getTime());
        Map<String, Object> recordMap2 = new HashMap<>();
        recordMap2.put("job_id", "foo");
        recordMap2.put("description", "snapshot2");
        recordMap2.put("restore_priority", 999);
        recordMap2.put("timestamp", now.getTime());
        recordMap2.put("snapshot_doc_count", 6);
        recordMap2.put("latest_record_time_stamp", now.getTime());
        recordMap2.put("latest_result_time_stamp", now.getTime());
        source.add(recordMap1);
        source.add(recordMap2);

        int from = 4;
        int size = 3;
        ArgumentCaptor<QueryBuilder> queryBuilder = ArgumentCaptor.forClass(QueryBuilder.class);
        SearchResponse response = createSearchResponse(true, source);
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME).addClusterStatusYellowResponse()
                .prepareSearch(AnomalyDetectorsIndex.jobResultsIndexName(jobId),
                        ModelSnapshot.TYPE.getPreferredName(), from, size, response, queryBuilder);

        Client client = clientBuilder.build();
        JobProvider provider = createProvider(client);

        QueryPage<ModelSnapshot> page = provider.modelSnapshots(jobId, from, size);
        assertEquals(2L, page.count());
        List<ModelSnapshot> snapshots = page.results();

        assertEquals("foo", snapshots.get(0).getJobId());
        assertEquals(now, snapshots.get(0).getTimestamp());
        assertEquals(now, snapshots.get(0).getLatestRecordTimeStamp());
        assertEquals(now, snapshots.get(0).getLatestResultTimeStamp());
        assertEquals("snapshot1", snapshots.get(0).getDescription());
        assertEquals(1L, snapshots.get(0).getRestorePriority());
        assertEquals(5, snapshots.get(0).getSnapshotDocCount());

        assertEquals(now, snapshots.get(1).getTimestamp());
        assertEquals(now, snapshots.get(1).getLatestRecordTimeStamp());
        assertEquals(now, snapshots.get(1).getLatestResultTimeStamp());
        assertEquals("snapshot2", snapshots.get(1).getDescription());
        assertEquals(999L, snapshots.get(1).getRestorePriority());
        assertEquals(6, snapshots.get(1).getSnapshotDocCount());
    }

    public void testModelSnapshots_WithDescription()
            throws InterruptedException, ExecutionException, IOException {
        String jobId = "TestJobIdentificationForInfluencers";
        Date now = new Date();
        List<Map<String, Object>> source = new ArrayList<>();

        Map<String, Object> recordMap1 = new HashMap<>();
        recordMap1.put("job_id", "foo");
        recordMap1.put("description", "snapshot1");
        recordMap1.put("restore_priority", 1);
        recordMap1.put("timestamp", now.getTime());
        recordMap1.put("snapshot_doc_count", 5);
        recordMap1.put("latest_record_time_stamp", now.getTime());
        recordMap1.put("latest_result_time_stamp", now.getTime());
        Map<String, Object> recordMap2 = new HashMap<>();
        recordMap2.put("job_id", "foo");
        recordMap2.put("description", "snapshot2");
        recordMap2.put("restore_priority", 999);
        recordMap2.put("timestamp", now.getTime());
        recordMap2.put("snapshot_doc_count", 6);
        recordMap2.put("latest_record_time_stamp", now.getTime());
        recordMap2.put("latest_result_time_stamp", now.getTime());
        source.add(recordMap1);
        source.add(recordMap2);

        int from = 4;
        int size = 3;
        ArgumentCaptor<QueryBuilder> queryBuilder = ArgumentCaptor.forClass(QueryBuilder.class);
        SearchResponse response = createSearchResponse(true, source);
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME).addClusterStatusYellowResponse()
                .prepareSearch(AnomalyDetectorsIndex.jobResultsIndexName(jobId),
                        ModelSnapshot.TYPE.getPreferredName(), from, size, response, queryBuilder);

        Client client = clientBuilder.build();
        JobProvider provider = createProvider(client);

        QueryPage<ModelSnapshot> page = provider.modelSnapshots(jobId, from, size, null, null, "sortfield", true, "snappyId",
                "description1");
        assertEquals(2L, page.count());
        List<ModelSnapshot> snapshots = page.results();

        assertEquals(now, snapshots.get(0).getTimestamp());
        assertEquals(now, snapshots.get(0).getLatestRecordTimeStamp());
        assertEquals(now, snapshots.get(0).getLatestResultTimeStamp());
        assertEquals("snapshot1", snapshots.get(0).getDescription());
        assertEquals(1L, snapshots.get(0).getRestorePriority());
        assertEquals(5, snapshots.get(0).getSnapshotDocCount());

        assertEquals(now, snapshots.get(1).getTimestamp());
        assertEquals(now, snapshots.get(1).getLatestRecordTimeStamp());
        assertEquals(now, snapshots.get(1).getLatestResultTimeStamp());
        assertEquals("snapshot2", snapshots.get(1).getDescription());
        assertEquals(999L, snapshots.get(1).getRestorePriority());
        assertEquals(6, snapshots.get(1).getSnapshotDocCount());

        String queryString = queryBuilder.getValue().toString();
        assertTrue(queryString.matches("(?s).*snapshot_id.*value. : .snappyId.*description.*value. : .description1.*"));
    }

    public void testMergePartitionScoresIntoBucket() throws InterruptedException, ExecutionException {
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME);

        JobProvider provider = createProvider(clientBuilder.build());

        List<PerPartitionMaxProbabilities> partitionMaxProbs = new ArrayList<>();

        List<AnomalyRecord> records = new ArrayList<>();
        records.add(createAnomalyRecord("partitionValue1", new Date(2), 1.0));
        records.add(createAnomalyRecord("partitionValue2", new Date(2), 4.0));
        partitionMaxProbs.add(new PerPartitionMaxProbabilities(records));

        records.clear();
        records.add(createAnomalyRecord("partitionValue1", new Date(3), 2.0));
        records.add(createAnomalyRecord("partitionValue2", new Date(3), 1.0));
        partitionMaxProbs.add(new PerPartitionMaxProbabilities(records));

        records.clear();
        records.add(createAnomalyRecord("partitionValue1", new Date(5), 3.0));
        records.add(createAnomalyRecord("partitionValue2", new Date(5), 2.0));
        partitionMaxProbs.add(new PerPartitionMaxProbabilities(records));

        List<Bucket> buckets = new ArrayList<>();
        buckets.add(createBucketAtEpochTime(1));
        buckets.add(createBucketAtEpochTime(2));
        buckets.add(createBucketAtEpochTime(3));
        buckets.add(createBucketAtEpochTime(4));
        buckets.add(createBucketAtEpochTime(5));
        buckets.add(createBucketAtEpochTime(6));

        provider.mergePartitionScoresIntoBucket(partitionMaxProbs, buckets, "partitionValue1");
        assertEquals(0.0, buckets.get(0).getMaxNormalizedProbability(), 0.001);
        assertEquals(1.0, buckets.get(1).getMaxNormalizedProbability(), 0.001);
        assertEquals(2.0, buckets.get(2).getMaxNormalizedProbability(), 0.001);
        assertEquals(0.0, buckets.get(3).getMaxNormalizedProbability(), 0.001);
        assertEquals(3.0, buckets.get(4).getMaxNormalizedProbability(), 0.001);
        assertEquals(0.0, buckets.get(5).getMaxNormalizedProbability(), 0.001);

        provider.mergePartitionScoresIntoBucket(partitionMaxProbs, buckets, "partitionValue2");
        assertEquals(0.0, buckets.get(0).getMaxNormalizedProbability(), 0.001);
        assertEquals(4.0, buckets.get(1).getMaxNormalizedProbability(), 0.001);
        assertEquals(1.0, buckets.get(2).getMaxNormalizedProbability(), 0.001);
        assertEquals(0.0, buckets.get(3).getMaxNormalizedProbability(), 0.001);
        assertEquals(2.0, buckets.get(4).getMaxNormalizedProbability(), 0.001);
        assertEquals(0.0, buckets.get(5).getMaxNormalizedProbability(), 0.001);
    }

    private AnomalyRecord createAnomalyRecord(String partitionFieldValue, Date timestamp, double normalizedProbability) {
        AnomalyRecord record = new AnomalyRecord("foo", timestamp, 600, 42);
        record.setPartitionFieldValue(partitionFieldValue);
        record.setNormalizedProbability(normalizedProbability);
        return record;
    }

    public void testMergePartitionScoresIntoBucket_WithEmptyScoresList() throws InterruptedException, ExecutionException {
        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME);

        JobProvider provider = createProvider(clientBuilder.build());

        List<PerPartitionMaxProbabilities> scores = new ArrayList<>();

        List<Bucket> buckets = new ArrayList<>();
        buckets.add(createBucketAtEpochTime(1));
        buckets.add(createBucketAtEpochTime(2));
        buckets.add(createBucketAtEpochTime(3));
        buckets.add(createBucketAtEpochTime(4));

        provider.mergePartitionScoresIntoBucket(scores, buckets, "partitionValue");
        assertEquals(0.0, buckets.get(0).getMaxNormalizedProbability(), 0.001);
        assertEquals(0.0, buckets.get(1).getMaxNormalizedProbability(), 0.001);
        assertEquals(0.0, buckets.get(2).getMaxNormalizedProbability(), 0.001);
        assertEquals(0.0, buckets.get(3).getMaxNormalizedProbability(), 0.001);
    }

    public void testRestoreStateToStream() throws Exception {
        Map<String, Object> categorizerState = new HashMap<>();
        categorizerState.put("catName", "catVal");
        GetResponse categorizerStateGetResponse1 = createGetResponse(true, categorizerState);
        GetResponse categorizerStateGetResponse2 = createGetResponse(false, null);
        Map<String, Object> modelState = new HashMap<>();
        modelState.put("modName", "modVal1");
        GetResponse modelStateGetResponse1 = createGetResponse(true, modelState);
        modelState.put("modName", "modVal2");
        GetResponse modelStateGetResponse2 = createGetResponse(true, modelState);

        MockClientBuilder clientBuilder = new MockClientBuilder(CLUSTER_NAME).addClusterStatusYellowResponse()
                .prepareGet(AnomalyDetectorsIndex.jobStateIndexName(), CategorizerState.TYPE, JOB_ID + "_1", categorizerStateGetResponse1)
                .prepareGet(AnomalyDetectorsIndex.jobStateIndexName(), CategorizerState.TYPE, JOB_ID + "_2", categorizerStateGetResponse2)
                .prepareGet(AnomalyDetectorsIndex.jobStateIndexName(), ModelState.TYPE.getPreferredName(), "123_1", modelStateGetResponse1)
                .prepareGet(AnomalyDetectorsIndex.jobStateIndexName(), ModelState.TYPE.getPreferredName(), "123_2", modelStateGetResponse2);

        JobProvider provider = createProvider(clientBuilder.build());

        ModelSnapshot modelSnapshot = new ModelSnapshot(randomAsciiOfLengthBetween(1, 20));
        modelSnapshot.setSnapshotId("123");
        modelSnapshot.setSnapshotDocCount(2);

        ByteArrayOutputStream stream = new ByteArrayOutputStream();

        provider.restoreStateToStream(JOB_ID, modelSnapshot, stream);

        String[] restoreData = stream.toString(StandardCharsets.UTF_8.name()).split("\0");
        assertEquals(3, restoreData.length);
        assertEquals("{\"catName\":\"catVal\"}", restoreData[0]);
        assertEquals("{\"modName\":\"modVal1\"}", restoreData[1]);
        assertEquals("{\"modName\":\"modVal2\"}", restoreData[2]);
    }

    private Bucket createBucketAtEpochTime(long epoch) {
        Bucket b = new Bucket("foo", new Date(epoch), 123);
        b.setMaxNormalizedProbability(10.0);
        return b;
    }

    private JobProvider createProvider(Client client) {
        return new JobProvider(client, 0, ParseFieldMatcher.STRICT);
    }

    private static GetResponse createGetResponse(boolean exists, Map<String, Object> source) throws IOException {
        GetResponse getResponse = mock(GetResponse.class);
        when(getResponse.isExists()).thenReturn(exists);
        when(getResponse.getSourceAsBytesRef()).thenReturn(XContentFactory.jsonBuilder().map(source).bytes());
        return getResponse;
    }

    private static SearchResponse createSearchResponse(boolean exists, List<Map<String, Object>> source) throws IOException {
        SearchResponse response = mock(SearchResponse.class);
        SearchHits hits = mock(SearchHits.class);
        List<SearchHit> list = new ArrayList<>();

        for (Map<String, Object> map : source) {
            SearchHit hit = mock(SearchHit.class);
            Map<String, Object> _source = new HashMap<>(map);
            when(hit.getSourceRef()).thenReturn(XContentFactory.jsonBuilder().map(_source).bytes());
            when(hit.getId()).thenReturn(String.valueOf(map.hashCode()));
            doAnswer(invocation -> {
                String field = (String) invocation.getArguments()[0];
                SearchHitField shf = mock(SearchHitField.class);
                when(shf.getValue()).thenReturn(map.get(field));
                return shf;
            }).when(hit).field(any(String.class));
            list.add(hit);
        }
        when(response.getHits()).thenReturn(hits);
        when(hits.getHits()).thenReturn(list.toArray(new SearchHit[0]));
        when(hits.getTotalHits()).thenReturn((long) source.size());

        doAnswer(invocation -> {
            Integer idx = (Integer) invocation.getArguments()[0];
            return list.get(idx);
        }).when(hits).getAt(any(Integer.class));

        return response;
    }
}
