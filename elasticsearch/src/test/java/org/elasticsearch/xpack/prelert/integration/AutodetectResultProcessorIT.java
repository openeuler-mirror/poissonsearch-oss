/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.integration;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.elasticsearch.xpack.prelert.job.AnalysisConfig;
import org.elasticsearch.xpack.prelert.job.Detector;
import org.elasticsearch.xpack.prelert.job.Job;
import org.elasticsearch.xpack.prelert.job.ModelSizeStats;
import org.elasticsearch.xpack.prelert.job.ModelSnapshot;
import org.elasticsearch.xpack.prelert.job.persistence.BucketsQueryBuilder;
import org.elasticsearch.xpack.prelert.job.persistence.InfluencersQueryBuilder;
import org.elasticsearch.xpack.prelert.job.persistence.JobProvider;
import org.elasticsearch.xpack.prelert.job.persistence.JobResultsPersister;
import org.elasticsearch.xpack.prelert.job.persistence.QueryPage;
import org.elasticsearch.xpack.prelert.job.persistence.RecordsQueryBuilder;
import org.elasticsearch.xpack.prelert.job.process.autodetect.output.AutoDetectResultProcessor;
import org.elasticsearch.xpack.prelert.job.process.autodetect.output.AutodetectResultsParser;
import org.elasticsearch.xpack.prelert.job.process.autodetect.output.FlushAcknowledgement;
import org.elasticsearch.xpack.prelert.job.process.normalizer.Renormalizer;
import org.elasticsearch.xpack.prelert.job.process.normalizer.noop.NoOpRenormalizer;
import org.elasticsearch.xpack.prelert.job.quantiles.Quantiles;
import org.elasticsearch.xpack.prelert.job.results.AnomalyRecord;
import org.elasticsearch.xpack.prelert.job.results.AnomalyRecordTests;
import org.elasticsearch.xpack.prelert.job.results.Bucket;
import org.elasticsearch.xpack.prelert.job.results.BucketTests;
import org.elasticsearch.xpack.prelert.job.results.CategoryDefinition;
import org.elasticsearch.xpack.prelert.job.results.CategoryDefinitionTests;
import org.elasticsearch.xpack.prelert.job.results.Influencer;
import org.elasticsearch.xpack.prelert.job.results.InfluencerTests;
import org.elasticsearch.xpack.prelert.job.results.ModelDebugOutput;
import org.elasticsearch.xpack.prelert.job.results.ModelDebugOutputTests;
import org.junit.Before;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class AutodetectResultProcessorIT extends ESSingleNodeTestCase {
    private static final String JOB_ID = "foo";

    private Renormalizer renormalizer;
    private JobResultsPersister jobResultsPersister;
    private AutodetectResultsParser autodetectResultsParser;
    private JobProvider jobProvider;

    @Before
    private void createComponents() {
        renormalizer = new NoOpRenormalizer();
        jobResultsPersister = new JobResultsPersister(nodeSettings(), client());
        ParseFieldMatcher matcher = new ParseFieldMatcher(nodeSettings());
        autodetectResultsParser = new AutodetectResultsParser(nodeSettings(), () -> matcher);
        jobProvider = new JobProvider(client(), 1, matcher);
    }

    public void testProcessResults() throws Exception {
        createJob();

        AutoDetectResultProcessor resultProcessor =
                new AutoDetectResultProcessor(renormalizer, jobResultsPersister, autodetectResultsParser);

        PipedOutputStream outputStream = new PipedOutputStream();
        PipedInputStream inputStream = new PipedInputStream(outputStream);

        Bucket bucket = createBucket(false);
        assertNotNull(bucket);
        List<AnomalyRecord> records = createRecords(false);
        List<Influencer> influencers = createInfluencers(false);
        CategoryDefinition categoryDefinition = createCategoryDefinition();
        ModelDebugOutput modelDebugOutput = createModelDebugOutput();
        ModelSizeStats modelSizeStats = createModelSizeStats();
        ModelSnapshot modelSnapshot = createModelSnapshot();
        Quantiles quantiles = createQuantiles();

        // Add the bucket last as the bucket result triggers persistence
        ResultsBuilder resultBuilder = new ResultsBuilder()
                .start()
                .addRecords(records)
                .addInfluencers(influencers)
                .addCategoryDefinition(categoryDefinition)
                .addModelDebugOutput(modelDebugOutput)
                .addModelSizeStats(modelSizeStats)
                .addModelSnapshot(modelSnapshot)
                .addQuantiles(quantiles)
                .addBucket(bucket)
                .end();

        new Thread(() -> {
            try {
                writeResults(resultBuilder.build(), outputStream);
            } catch (IOException e) {
            }
        }).start();

        resultProcessor.process(JOB_ID, inputStream, false);
        jobResultsPersister.commitResultWrites(JOB_ID);

        BucketsQueryBuilder.BucketsQuery bucketsQuery = new BucketsQueryBuilder().includeInterim(true).build();
        QueryPage<Bucket> persistedBucket = getBucketQueryPage(bucketsQuery);
        assertEquals(1, persistedBucket.count());
        // Records are not persisted to Elasticsearch as an array within the bucket
        // documents, so remove them from the expected bucket before comparing
        bucket.setRecords(Collections.emptyList());
        assertEquals(bucket, persistedBucket.results().get(0));

        QueryPage<AnomalyRecord> persistedRecords = jobProvider.records(JOB_ID, new RecordsQueryBuilder().includeInterim(true).build());
        assertResultsAreSame(records, persistedRecords);

        QueryPage<Influencer> persistedInfluencers =
                jobProvider.influencers(JOB_ID, new InfluencersQueryBuilder().includeInterim(true).build());
        assertResultsAreSame(influencers, persistedInfluencers);

        QueryPage<CategoryDefinition> persistedDefinition = getCategoryDefinition(Long.toString(categoryDefinition.getCategoryId()));
        assertEquals(1, persistedDefinition.count());
        assertEquals(categoryDefinition, persistedDefinition.results().get(0));

        QueryPage<ModelDebugOutput> persistedModelDebugOutput = jobProvider.modelDebugOutput(JOB_ID, 0, 100);
        assertEquals(1, persistedModelDebugOutput.count());
        assertEquals(modelDebugOutput, persistedModelDebugOutput.results().get(0));

        ModelSizeStats persistedModelSizeStats = getModelSizeStats();
        assertEquals(modelSizeStats, persistedModelSizeStats);

        QueryPage<ModelSnapshot> persistedModelSnapshot = jobProvider.modelSnapshots(JOB_ID, 0, 100);
        assertEquals(1, persistedModelSnapshot.count());
        assertEquals(modelSnapshot, persistedModelSnapshot.results().get(0));

        Optional<Quantiles> persistedQuantiles = jobProvider.getQuantiles(JOB_ID);
        assertTrue(persistedQuantiles.isPresent());
        assertEquals(quantiles, persistedQuantiles.get());
    }

    public void testDeleteInterimResults() throws Exception {
        createJob();

        AutoDetectResultProcessor resultProcessor =
                new AutoDetectResultProcessor(renormalizer, jobResultsPersister, autodetectResultsParser);

        PipedOutputStream outputStream = new PipedOutputStream();
        PipedInputStream inputStream = new PipedInputStream(outputStream);

        Bucket nonInterimBucket = createBucket(false);
        Bucket interimBucket = createBucket(true);

        ResultsBuilder resultBuilder = new ResultsBuilder()
                .start()
                .addRecords(createRecords(true))
                .addInfluencers(createInfluencers(true))
                .addBucket(interimBucket)  // this will persist the interim results
                .addFlushAcknowledgement(createFlushAcknowledgement())
                .addBucket(nonInterimBucket) // and this will delete the interim results
                .end();

        new Thread(() -> {
            try {
                writeResults(resultBuilder.build(), outputStream);
            } catch (IOException e) {
            }
        }).start();

        resultProcessor.process(JOB_ID, inputStream, false);
        jobResultsPersister.commitResultWrites(JOB_ID);

        QueryPage<Bucket> persistedBucket = getBucketQueryPage(new BucketsQueryBuilder().includeInterim(true).build());
        assertEquals(1, persistedBucket.count());
        // Records are not persisted to Elasticsearch as an array within the bucket
        // documents, so remove them from the expected bucket before comparing
        nonInterimBucket.setRecords(Collections.emptyList());
        assertEquals(nonInterimBucket, persistedBucket.results().get(0));

        QueryPage<Influencer> persistedInfluencers = jobProvider.influencers(JOB_ID, new InfluencersQueryBuilder().build());
        assertEquals(0, persistedInfluencers.count());

        QueryPage<AnomalyRecord> persistedRecords = jobProvider.records(JOB_ID, new RecordsQueryBuilder().includeInterim(true).build());
        assertEquals(0, persistedRecords.count());
    }

    public void testMultipleFlushesBetweenPersisting() throws Exception {
        createJob();

        AutoDetectResultProcessor resultProcessor =
                new AutoDetectResultProcessor(renormalizer, jobResultsPersister, autodetectResultsParser);

        PipedOutputStream outputStream = new PipedOutputStream();
        PipedInputStream inputStream = new PipedInputStream(outputStream);

        Bucket finalBucket = createBucket(true);
        List<AnomalyRecord> finalAnomalyRecords = createRecords(true);

        ResultsBuilder resultBuilder = new ResultsBuilder()
                .start()
                .addRecords(createRecords(true))
                .addInfluencers(createInfluencers(true))
                .addBucket(createBucket(true))  // this will persist the interim results
                .addFlushAcknowledgement(createFlushAcknowledgement())
                .addRecords(createRecords(true))
                .addBucket(createBucket(true)) // and this will delete the interim results and persist the new interim bucket & records
                .addFlushAcknowledgement(createFlushAcknowledgement())
                .addRecords(finalAnomalyRecords)
                .addBucket(finalBucket) // this deletes the previous interim and persists final bucket & records
                .end();

        new Thread(() -> {
            try {
                writeResults(resultBuilder.build(), outputStream);
            } catch (IOException e) {
            }
        }).start();

        resultProcessor.process(JOB_ID, inputStream, false);
        jobResultsPersister.commitResultWrites(JOB_ID);

        QueryPage<Bucket> persistedBucket = getBucketQueryPage(new BucketsQueryBuilder().includeInterim(true).build());
        assertEquals(1, persistedBucket.count());
        // Records are not persisted to Elasticsearch as an array within the bucket
        // documents, so remove them from the expected bucket before comparing
        finalBucket.setRecords(Collections.emptyList());
        assertEquals(finalBucket, persistedBucket.results().get(0));

        QueryPage<AnomalyRecord> persistedRecords = jobProvider.records(JOB_ID, new RecordsQueryBuilder().includeInterim(true).build());
        assertResultsAreSame(finalAnomalyRecords, persistedRecords);
    }

    public void testEndOfStreamTriggersPersisting() throws Exception {
        createJob();

        AutoDetectResultProcessor resultProcessor =
                new AutoDetectResultProcessor(renormalizer, jobResultsPersister, autodetectResultsParser);

        PipedOutputStream outputStream = new PipedOutputStream();
        PipedInputStream inputStream = new PipedInputStream(outputStream);

        Bucket bucket = createBucket(false);
        List<AnomalyRecord> firstSetOfRecords = createRecords(false);
        List<AnomalyRecord> secondSetOfRecords = createRecords(false);

        ResultsBuilder resultBuilder = new ResultsBuilder()
                .start()
                .addRecords(firstSetOfRecords)
                .addBucket(bucket)  // bucket triggers persistence
                .addRecords(secondSetOfRecords)
                .end();  // end of stream should persist the second bunch of records

        new Thread(() -> {
            try {
                writeResults(resultBuilder.build(), outputStream);
            } catch (IOException e) {
            }
        }).start();

        resultProcessor.process(JOB_ID, inputStream, false);
        jobResultsPersister.commitResultWrites(JOB_ID);

        QueryPage<Bucket> persistedBucket = getBucketQueryPage(new BucketsQueryBuilder().includeInterim(true).build());
        assertEquals(1, persistedBucket.count());

        QueryPage<AnomalyRecord> persistedRecords = jobProvider.records(JOB_ID,
                new RecordsQueryBuilder().size(200).includeInterim(true).build());

        List<AnomalyRecord> allRecords = new ArrayList<>(firstSetOfRecords);
        allRecords.addAll(secondSetOfRecords);
        assertResultsAreSame(allRecords, persistedRecords);
    }

    private void writeResults(XContentBuilder builder, OutputStream out) throws IOException {
        builder.bytes().writeTo(out);
    }

    private void createJob() {
        Detector detector = new Detector.Builder("avg", "metric_field").build();
        Job.Builder jobBuilder = new Job.Builder(JOB_ID);
        jobBuilder.setAnalysisConfig(new AnalysisConfig.Builder(Collections.singletonList(detector)));

        jobProvider.createJobResultIndex(jobBuilder.build(), new ActionListener<Boolean>() {
            @Override
            public void onResponse(Boolean aBoolean) {
            }

            @Override
            public void onFailure(Exception e) {
            }
        });
    }

    private Bucket createBucket(boolean isInterim) {
        Bucket bucket = new BucketTests().createTestInstance(JOB_ID);
        bucket.setInterim(isInterim);
        return bucket;
    }

    private List<AnomalyRecord> createRecords(boolean isInterim) {
        List<AnomalyRecord> records = new ArrayList<>();

        int count = randomIntBetween(0, 100);
        AnomalyRecordTests anomalyRecordGenerator = new AnomalyRecordTests();
        for (int i=0; i<count; i++) {
            AnomalyRecord r = anomalyRecordGenerator.createTestInstance(JOB_ID, i);
            r.setInterim(isInterim);
            records.add(r);
        }
        return records;
    }

    private List<Influencer> createInfluencers(boolean isInterim) {
        List<Influencer> influencers = new ArrayList<>();

        int count = randomIntBetween(0, 100);
        InfluencerTests influencerGenerator = new InfluencerTests();
        for (int i=0; i<count; i++) {
            Influencer influencer = influencerGenerator.createTestInstance(JOB_ID);
            influencer.setInterim(isInterim);
            influencers.add(influencer);
        }
        return influencers;
    }

    private CategoryDefinition createCategoryDefinition() {
        return new CategoryDefinitionTests().createTestInstance(JOB_ID);
    }

    private ModelDebugOutput createModelDebugOutput() {
        return new ModelDebugOutputTests().createTestInstance(JOB_ID);
    }

    private ModelSizeStats createModelSizeStats() {
        ModelSizeStats.Builder builder = new ModelSizeStats.Builder(JOB_ID);
        builder.setId(randomAsciiOfLength(20));
        builder.setTimestamp(new Date(randomPositiveLong()));
        builder.setLogTime(new Date(randomPositiveLong()));
        builder.setBucketAllocationFailuresCount(randomPositiveLong());
        builder.setModelBytes(randomPositiveLong());
        builder.setTotalByFieldCount(randomPositiveLong());
        builder.setTotalOverFieldCount(randomPositiveLong());
        builder.setTotalPartitionFieldCount(randomPositiveLong());
        builder.setMemoryStatus(randomFrom(EnumSet.allOf(ModelSizeStats.MemoryStatus.class)));
        return builder.build();
    }

    private ModelSnapshot createModelSnapshot() {
        ModelSnapshot snapshot = new ModelSnapshot(JOB_ID);
        snapshot.setSnapshotId(randomAsciiOfLength(12));
        return snapshot;
    }

    private Quantiles createQuantiles() {
        return new Quantiles(JOB_ID, new Date(randomPositiveLong()), randomAsciiOfLength(100));
    }

    private FlushAcknowledgement createFlushAcknowledgement() {
        return new FlushAcknowledgement(randomAsciiOfLength(5));
    }

    private class ResultsBuilder {
        private XContentBuilder contentBuilder;

        private ResultsBuilder() throws IOException {
            contentBuilder = XContentFactory.jsonBuilder();
        }

        ResultsBuilder start() throws IOException {
            contentBuilder.startArray();
            return this;
        }

        ResultsBuilder addBucket(Bucket bucket) throws IOException {
            contentBuilder.startObject().field(Bucket.RESULT_TYPE_FIELD.getPreferredName(), bucket).endObject();
            return this;
        }

        ResultsBuilder addRecords(List<AnomalyRecord> records) throws IOException {
            contentBuilder.startObject().field(AnomalyRecord.RESULTS_FIELD.getPreferredName(), records).endObject();
            return this;
        }

        ResultsBuilder addInfluencers(List<Influencer> influencers) throws IOException {
            contentBuilder.startObject().field(Influencer.RESULTS_FIELD.getPreferredName(), influencers).endObject();
            return this;
        }

        ResultsBuilder addCategoryDefinition(CategoryDefinition definition) throws IOException {
            contentBuilder.startObject().field(CategoryDefinition.TYPE.getPreferredName(), definition).endObject();
            return this;
        }

        ResultsBuilder addModelDebugOutput(ModelDebugOutput modelDebugOutput) throws IOException {
            contentBuilder.startObject().field(ModelDebugOutput.RESULTS_FIELD.getPreferredName(), modelDebugOutput).endObject();
            return this;
        }

        ResultsBuilder addModelSizeStats(ModelSizeStats modelSizeStats) throws IOException {
            contentBuilder.startObject().field(ModelSizeStats.RESULT_TYPE_FIELD.getPreferredName(), modelSizeStats).endObject();
            return this;
        }

        ResultsBuilder addModelSnapshot(ModelSnapshot modelSnapshot) throws IOException {
            contentBuilder.startObject().field(ModelSnapshot.TYPE.getPreferredName(), modelSnapshot).endObject();
            return this;
        }

        ResultsBuilder addQuantiles(Quantiles quantiles) throws IOException {
            contentBuilder.startObject().field(Quantiles.TYPE.getPreferredName(), quantiles).endObject();
            return this;
        }

        ResultsBuilder addFlushAcknowledgement(FlushAcknowledgement flushAcknowledgement) throws IOException {
            contentBuilder.startObject().field(FlushAcknowledgement.TYPE.getPreferredName(), flushAcknowledgement).endObject();
            return this;
        }


        ResultsBuilder end() throws IOException {
            contentBuilder.endArray();
            return this;
        }

        XContentBuilder build() throws IOException {
            XContentBuilder result = contentBuilder;
            contentBuilder = XContentFactory.jsonBuilder();
            return result;
        }
    }


    private <T extends ToXContent & Writeable> void assertResultsAreSame(List<T> expected, QueryPage<T> actual) {
        assertEquals(expected.size(), actual.count());
        assertEquals(actual.results().size(), actual.count());
        Set<T> expectedSet = new HashSet<>(expected);
        expectedSet.removeAll(actual.results());
        assertEquals(0, expectedSet.size());
    }

    private QueryPage<Bucket> getBucketQueryPage(BucketsQueryBuilder.BucketsQuery bucketsQuery) throws Exception {
        AtomicReference<Exception> errorHolder = new AtomicReference<>();
        AtomicReference<QueryPage<Bucket>> resultHolder = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        jobProvider.buckets(JOB_ID, bucketsQuery, r -> {
            resultHolder.set(r);
            latch.countDown();
        }, e -> {
            errorHolder.set(e);
            latch.countDown();
        });
        latch.await();
        if (errorHolder.get() != null) {
            throw errorHolder.get();
        }
        return resultHolder.get();
    }

    private QueryPage<CategoryDefinition> getCategoryDefinition(String categoryId) throws Exception {
        AtomicReference<Exception> errorHolder = new AtomicReference<>();
        AtomicReference<QueryPage<CategoryDefinition>> resultHolder = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        jobProvider.categoryDefinitions(JOB_ID, categoryId, null, null, r -> {
            resultHolder.set(r);
            latch.countDown();
        }, e -> {
            errorHolder.set(e);
            latch.countDown();
        });
        latch.await();
        if (errorHolder.get() != null) {
            throw errorHolder.get();
        }
        return resultHolder.get();
    }

    private ModelSizeStats getModelSizeStats() throws Exception {
        AtomicReference<Exception> errorHolder = new AtomicReference<>();
        AtomicReference<ModelSizeStats> resultHolder = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        jobProvider.modelSizeStats(JOB_ID, modelSizeStats -> {
            resultHolder.set(modelSizeStats);
            latch.countDown();
        }, e -> {
            errorHolder.set(e);
            latch.countDown();
        });
        latch.await();
        if (errorHolder.get() != null) {
            throw errorHolder.get();
        }
        return resultHolder.get();
    }
}
