/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.job.process.normalizer;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.List;

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.ml.job.AnalysisConfig;
import org.elasticsearch.xpack.ml.job.Detector;
import org.elasticsearch.xpack.ml.job.Job;
import org.elasticsearch.xpack.ml.job.persistence.BatchedDocumentsIterator;
import org.elasticsearch.xpack.ml.job.persistence.ElasticsearchBatchedResultsIterator;
import org.elasticsearch.xpack.ml.job.persistence.JobProvider;
import org.elasticsearch.xpack.ml.job.persistence.JobRenormalizedResultsPersister;
import org.elasticsearch.xpack.ml.job.persistence.MockBatchedDocumentsIterator;
import org.elasticsearch.xpack.ml.job.results.AnomalyRecord;
import org.elasticsearch.xpack.ml.job.results.Bucket;
import org.elasticsearch.xpack.ml.job.results.BucketInfluencer;
import org.elasticsearch.xpack.ml.job.results.Influencer;
import org.junit.Before;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static org.elasticsearch.mock.orig.Mockito.doAnswer;
import static org.elasticsearch.mock.orig.Mockito.never;
import static org.elasticsearch.mock.orig.Mockito.times;
import static org.elasticsearch.mock.orig.Mockito.mock;
import static org.elasticsearch.mock.orig.Mockito.verify;
import static org.elasticsearch.mock.orig.Mockito.when;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyList;
import static org.mockito.Matchers.anyListOf;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;

public class ScoresUpdaterTests extends ESTestCase {
    private static final String JOB_ID = "foo";
    private static final String QUANTILES_STATE = "someState";
    private static final long DEFAULT_BUCKET_SPAN = 3600;
    private static final long DEFAULT_START_TIME = 0;

    private JobProvider jobProvider = mock(JobProvider.class);
    private JobRenormalizedResultsPersister jobRenormalizedResultsPersister = mock(JobRenormalizedResultsPersister.class);
    private Normalizer normalizer = mock(Normalizer.class);
    private NormalizerFactory normalizerFactory = mock(NormalizerFactory.class);

    private Job job;
    private ScoresUpdater scoresUpdater;

    private Bucket generateBucket(Date timestamp) throws IOException {
        return new Bucket(JOB_ID, timestamp, DEFAULT_BUCKET_SPAN);
    }

    @Before
    @SuppressWarnings("unchecked")
    public void setUpMocks() throws IOException {
        MockitoAnnotations.initMocks(this);

        Job.Builder jobBuilder = new Job.Builder(JOB_ID);
        jobBuilder.setRenormalizationWindowDays(1L);
        List<Detector> detectors = new ArrayList<>();
        detectors.add(mock(Detector.class));
        AnalysisConfig.Builder configBuilder = new AnalysisConfig.Builder(detectors);
        configBuilder.setBucketSpan(DEFAULT_BUCKET_SPAN);
        jobBuilder.setAnalysisConfig(configBuilder);

        job = jobBuilder.build();

        scoresUpdater = new ScoresUpdater(job, jobProvider, jobRenormalizedResultsPersister, normalizerFactory);

        givenProviderReturnsNoBuckets();
        givenProviderReturnsNoInfluencers();
        givenNormalizerFactoryReturnsMock();
        givenNormalizerRaisesBigChangeFlag();
    }

    public void testUpdate_GivenBucketWithZeroScoreAndNoRecords() throws IOException {
        Bucket bucket = generateBucket(new Date(0));
        bucket.setAnomalyScore(0.0);
        bucket.addBucketInfluencer(createTimeBucketInfluencer(bucket.getTimestamp(), 0.7, 0.0));
        Deque<Bucket> buckets = new ArrayDeque<>();
        buckets.add(bucket);
        givenProviderReturnsBuckets(buckets);

        scoresUpdater.update(QUANTILES_STATE, 3600, 0, false);

        verifyNormalizerWasInvoked(0);
        verifyNothingWasUpdated();
    }

    public void testUpdate_GivenTwoBucketsOnlyOneUpdated() throws IOException {
        Bucket bucket = generateBucket(new Date(0));
        bucket.setAnomalyScore(30.0);
        bucket.addBucketInfluencer(createTimeBucketInfluencer(bucket.getTimestamp(), 0.04, 30.0));
        Deque<Bucket> buckets = new ArrayDeque<>();
        buckets.add(bucket);
        bucket = generateBucket(new Date(1000));
        bucket.setAnomalyScore(0.0);

        givenProviderReturnsBuckets(buckets);
        givenProviderReturnsRecords(new ArrayDeque<>());

        scoresUpdater.update(QUANTILES_STATE, 3600, 0, false);

        verifyNormalizerWasInvoked(1);
        verify(jobRenormalizedResultsPersister, times(1)).updateBucket(any());
    }

    public void testUpdate_GivenSingleBucketWithAnomalyScoreAndNoRecords() throws IOException {
        Bucket bucket = generateBucket(new Date(0));
        bucket.setAnomalyScore(42.0);
        bucket.addBucketInfluencer(createTimeBucketInfluencer(bucket.getTimestamp(), 0.04, 42.0));
        bucket.setMaxNormalizedProbability(50.0);

        Deque<Bucket> buckets = new ArrayDeque<>();
        buckets.add(bucket);
        givenProviderReturnsBuckets(buckets);
        givenProviderReturnsRecords(new ArrayDeque<>());

        scoresUpdater.update(QUANTILES_STATE, 3600, 0, false);

        verifyNormalizerWasInvoked(1);
        verifyBucketWasUpdated(1);
    }

    public void testUpdate_GivenSingleBucketAndRecords() throws IOException {
        Bucket bucket = generateBucket(new Date(DEFAULT_START_TIME));
        bucket.setAnomalyScore(30.0);
        bucket.addBucketInfluencer(createTimeBucketInfluencer(bucket.getTimestamp(), 0.04, 30.0));
        Deque<AnomalyRecord> records = new ArrayDeque<>();
        AnomalyRecord record1 = createRecord();
        AnomalyRecord record2 = createRecord();
        records.add(record1);
        records.add(record2);

        Deque<Bucket> buckets = new ArrayDeque<>();
        buckets.add(bucket);
        givenProviderReturnsBuckets(buckets);
        givenProviderReturnsRecords(records);

        scoresUpdater.update(QUANTILES_STATE, 3600, 0, false);

        verifyNormalizerWasInvoked(1);
        verify(jobRenormalizedResultsPersister, times(1)).updateBucket(any());
        verify(jobRenormalizedResultsPersister, times(1)).updateResults(any());
        verify(jobRenormalizedResultsPersister, times(2)).executeRequest(anyString());
    }

    public void testUpdate_GivenEnoughBucketsForTwoBatchesButOneNormalization() throws IOException {
        Deque<Bucket> batch1 = new ArrayDeque<>();
        for (int i = 0; i < 10000; ++i) {
            Bucket bucket = generateBucket(new Date(i * 1000));
            bucket.setAnomalyScore(42.0);
            bucket.addBucketInfluencer(createTimeBucketInfluencer(bucket.getTimestamp(), 0.04, 42.0));
            bucket.setMaxNormalizedProbability(50.0);
            batch1.add(bucket);
        }

        Bucket secondBatchBucket = generateBucket(new Date(10000 * 1000));
        secondBatchBucket.addBucketInfluencer(createTimeBucketInfluencer(secondBatchBucket.getTimestamp(), 0.04, 42.0));
        secondBatchBucket.setAnomalyScore(42.0);
        secondBatchBucket.setMaxNormalizedProbability(50.0);
        Deque<Bucket> batch2 = new ArrayDeque<>();
        batch2.add(secondBatchBucket);

        givenProviderReturnsBuckets(batch1, batch2);
        givenProviderReturnsRecords(new ArrayDeque<>());

        scoresUpdater.update(QUANTILES_STATE, 3600, 0, false);

        verifyNormalizerWasInvoked(1);

        // Batch 1 - Just verify first and last were updated as Mockito
        // is forbiddingly slow when tring to verify all 10000
        verifyBucketWasUpdated(10001);
    }

    public void testUpdate_GivenTwoBucketsWithFirstHavingEnoughRecordsToForceSecondNormalization() throws IOException {
        Bucket bucket1 = generateBucket(new Date(0));
        bucket1.setAnomalyScore(42.0);
        bucket1.addBucketInfluencer(createTimeBucketInfluencer(bucket1.getTimestamp(), 0.04, 42.0));
        bucket1.setMaxNormalizedProbability(50.0);
        List<ElasticsearchBatchedResultsIterator.ResultWithIndex<AnomalyRecord>> records = new ArrayList<>();
        Date date = new Date();
        for (int i=0; i<100000; i++) {
            records.add(new ElasticsearchBatchedResultsIterator.ResultWithIndex<>("foo", new AnomalyRecord("foo", date, 1, i)));
        }

        Bucket bucket2 = generateBucket(new Date(10000 * 1000));
        bucket2.addBucketInfluencer(createTimeBucketInfluencer(bucket2.getTimestamp(), 0.04, 42.0));
        bucket2.setAnomalyScore(42.0);
        bucket2.setMaxNormalizedProbability(50.0);

        Deque<Bucket> batch = new ArrayDeque<>();
        batch.add(bucket1);
        batch.add(bucket2);
        givenProviderReturnsBuckets(batch);


        List<Deque<ElasticsearchBatchedResultsIterator.ResultWithIndex<AnomalyRecord>>> recordBatches = new ArrayList<>();
        recordBatches.add(new ArrayDeque<>(records));
        BatchedDocumentsIterator<ElasticsearchBatchedResultsIterator.ResultWithIndex<AnomalyRecord>> recordIter =
                new MockBatchedDocumentsIterator<>(recordBatches);
        when(jobProvider.newBatchedRecordsIterator(JOB_ID)).thenReturn(recordIter);

        scoresUpdater.update(QUANTILES_STATE, 3600, 0, false);

        verifyNormalizerWasInvoked(2);
    }

    public void testUpdate_GivenInfluencerWithBigChange() throws IOException {
        Influencer influencer = new Influencer(JOB_ID, "n", "v", new Date(DEFAULT_START_TIME), 600, 1);

        Deque<Influencer> influencers = new ArrayDeque<>();
        influencers.add(influencer);
        givenProviderReturnsInfluencers(influencers);

        scoresUpdater.update(QUANTILES_STATE, 3600, 0, false);

        verifyNormalizerWasInvoked(1);
        verify(jobRenormalizedResultsPersister, times(1)).updateResults(any());
        verify(jobRenormalizedResultsPersister, times(1)).executeRequest(anyString());
    }

    public void testDefaultRenormalizationWindowBasedOnTime() throws IOException {
        Bucket bucket = generateBucket(new Date(2509200000L));
        bucket.setAnomalyScore(42.0);
        bucket.addBucketInfluencer(createTimeBucketInfluencer(bucket.getTimestamp(), 0.04, 42.0));
        bucket.setMaxNormalizedProbability(50.0);

        Deque<Bucket> buckets = new ArrayDeque<>();
        buckets.add(bucket);
        givenProviderReturnsBuckets(buckets);
        givenProviderReturnsRecords(new ArrayDeque<>());
        givenProviderReturnsNoInfluencers();

        scoresUpdater.update(QUANTILES_STATE, 2595600000L, 0, false);

        verifyNormalizerWasInvoked(1);
        verifyBucketWasUpdated(1);
    }

    public void testManualRenormalizationWindow() throws IOException {
        Bucket bucket = generateBucket(new Date(3600000));
        bucket.setAnomalyScore(42.0);
        bucket.addBucketInfluencer(createTimeBucketInfluencer(bucket.getTimestamp(), 0.04, 42.0));
        bucket.setMaxNormalizedProbability(50.0);

        Deque<Bucket> buckets = new ArrayDeque<>();
        buckets.add(bucket);
        givenProviderReturnsBuckets(buckets);
        givenProviderReturnsRecords(new ArrayDeque<>());
        givenProviderReturnsNoInfluencers();

        scoresUpdater.update(QUANTILES_STATE, 90000000L, 0, false);

        verifyNormalizerWasInvoked(1);
        verifyBucketWasUpdated(1);
    }

    public void testManualRenormalizationWindow_GivenExtension() throws IOException {

        Bucket bucket = generateBucket(new Date(2700000));
        bucket.setAnomalyScore(42.0);
        bucket.addBucketInfluencer(createTimeBucketInfluencer(bucket.getTimestamp(), 0.04, 42.0));
        bucket.setMaxNormalizedProbability(50.0);

        Deque<Bucket> buckets = new ArrayDeque<>();
        buckets.add(bucket);
        givenProviderReturnsBuckets(buckets);
        givenProviderReturnsRecords(new ArrayDeque<>());
        givenProviderReturnsNoInfluencers();

        scoresUpdater.update(QUANTILES_STATE, 90000000L, 900000, false);

        verifyNormalizerWasInvoked(1);
        verifyBucketWasUpdated(1);
    }

    private BucketInfluencer createTimeBucketInfluencer(Date timestamp, double probability, double anomalyScore) {
        BucketInfluencer influencer = new BucketInfluencer(JOB_ID, timestamp, DEFAULT_BUCKET_SPAN, 1);
        influencer.setProbability(probability);
        influencer.setAnomalyScore(anomalyScore);
        influencer.setInfluencerFieldName(BucketInfluencer.BUCKET_TIME);
        return influencer;
    }

    private static AnomalyRecord createRecord() {
        AnomalyRecord anomalyRecord = mock(AnomalyRecord.class);
        when(anomalyRecord.getId()).thenReturn("someId");
        return anomalyRecord;
    }

    private void givenNormalizerFactoryReturnsMock() {
        when(normalizerFactory.create(JOB_ID)).thenReturn(normalizer);
    }
    private void givenProviderReturnsNoBuckets() {
        givenBuckets(Collections.emptyList());
    }

    @SuppressWarnings("unchecked")
    private void givenNormalizerRaisesBigChangeFlag() {
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocationOnMock) throws Throwable {
                List<Normalizable> normalizables = (List<Normalizable>) invocationOnMock.getArguments()[2];
                for (Normalizable normalizable : normalizables) {
                    normalizable.raiseBigChangeFlag();
                    for (Normalizable child : normalizable.getChildren()) {
                        child.raiseBigChangeFlag();
                    }
                }
                return null;
            }
        }).when(normalizer).normalize(anyInt(), anyBoolean(), anyList(), anyString());
    }

    private void givenProviderReturnsBuckets(Deque<Bucket> batch1, Deque<Bucket> batch2) {
        List<Deque<Bucket>> batches = new ArrayList<>();
        batches.add(new ArrayDeque<>(batch1));
        batches.add(new ArrayDeque<>(batch2));
        givenBuckets(batches);
    }

    private void givenProviderReturnsBuckets(Deque<Bucket> buckets) {
        List<Deque<Bucket>> batches = new ArrayList<>();
        batches.add(new ArrayDeque<>(buckets));
        givenBuckets(batches);
    }

    private void givenBuckets(List<Deque<Bucket>> batches) {
        List<Deque<ElasticsearchBatchedResultsIterator.ResultWithIndex<Bucket>>> batchesWithIndex = new ArrayList<>();
        for (Deque<Bucket> deque : batches) {
            Deque<ElasticsearchBatchedResultsIterator.ResultWithIndex<Bucket>> queueWithIndex = new ArrayDeque<>();
            for (Bucket bucket : deque) {
                queueWithIndex.add(new ElasticsearchBatchedResultsIterator.ResultWithIndex<>("foo", bucket));
            }
            batchesWithIndex.add(queueWithIndex);
        }

        BatchedDocumentsIterator<ElasticsearchBatchedResultsIterator.ResultWithIndex<Bucket>> bucketIter =
                new MockBatchedDocumentsIterator<>(batchesWithIndex);
        when(jobProvider.newBatchedBucketsIterator(JOB_ID)).thenReturn(bucketIter);
    }

    private void givenProviderReturnsRecords(Deque<AnomalyRecord> records) {
        Deque<ElasticsearchBatchedResultsIterator.ResultWithIndex<AnomalyRecord>> batch = new ArrayDeque<>();
        List<Deque<ElasticsearchBatchedResultsIterator.ResultWithIndex<AnomalyRecord>>> batches = new ArrayList<>();
        for (AnomalyRecord record : records) {
            batch.add(new ElasticsearchBatchedResultsIterator.ResultWithIndex<>("foo", record));
        }
        batches.add(batch);

        BatchedDocumentsIterator<ElasticsearchBatchedResultsIterator.ResultWithIndex<AnomalyRecord>> recordIter =
                new MockBatchedDocumentsIterator<>(batches);
        when(jobProvider.newBatchedRecordsIterator(JOB_ID)).thenReturn(recordIter);
    }

    private void givenProviderReturnsNoInfluencers() {
        givenProviderReturnsInfluencers(new ArrayDeque<>());
    }

    private void givenProviderReturnsInfluencers(Deque<Influencer> influencers) {
        List<Deque<ElasticsearchBatchedResultsIterator.ResultWithIndex<Influencer>>> batches = new ArrayList<>();
        Deque<ElasticsearchBatchedResultsIterator.ResultWithIndex<Influencer>> queue = new ArrayDeque<>();
        for (Influencer inf : influencers) {
            queue.add(new ElasticsearchBatchedResultsIterator.ResultWithIndex<>("foo", inf));
        }
        batches.add(queue);
        BatchedDocumentsIterator<ElasticsearchBatchedResultsIterator.ResultWithIndex<Influencer>> iterator =
                new MockBatchedDocumentsIterator<>(batches);
        when(jobProvider.newBatchedInfluencersIterator(JOB_ID)).thenReturn(iterator);
    }

    private void verifyNormalizerWasInvoked(int times) throws IOException {
        int bucketSpan = job.getAnalysisConfig() == null ? 0 : job.getAnalysisConfig().getBucketSpan().intValue();
        verify(normalizer, times(times)).normalize(
                eq(bucketSpan), eq(false), anyListOf(Normalizable.class),
                eq(QUANTILES_STATE));
    }

    private void verifyNothingWasUpdated() {
        verify(jobRenormalizedResultsPersister, never()).updateBucket(any());
        verify(jobRenormalizedResultsPersister, never()).updateResults(any());
    }

    private void verifyBucketWasUpdated(int bucketCount) {
        verify(jobRenormalizedResultsPersister, times(bucketCount)).updateBucket(any());
    }
}
