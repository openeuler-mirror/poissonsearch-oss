/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.job.persistence;

import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.xpack.prelert.job.ModelSizeStats;
import org.elasticsearch.xpack.prelert.job.ModelSnapshot;
import org.elasticsearch.xpack.prelert.job.quantiles.Quantiles;
import org.elasticsearch.xpack.prelert.job.results.AnomalyRecord;
import org.elasticsearch.xpack.prelert.job.results.Bucket;
import org.elasticsearch.xpack.prelert.job.results.BucketInfluencer;
import org.elasticsearch.xpack.prelert.job.results.CategoryDefinition;
import org.elasticsearch.xpack.prelert.job.results.Influencer;
import org.elasticsearch.xpack.prelert.job.results.ModelDebugOutput;
import org.elasticsearch.xpack.prelert.job.results.PerPartitionMaxProbabilities;
import org.elasticsearch.xpack.prelert.job.results.Result;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;

/**
 * Persists result types, Quantiles etc to Elasticsearch<br>
 * <h2>Bucket</h2> Bucket result. The anomaly score of the bucket may not match the summed
 * score of all the records as all the records may not have been outputted for the
 * bucket. Contains bucket influencers that are persisted both with the bucket
 * and separately.
 * <b>Anomaly Record</b> Each record was generated by a detector which can be identified via
 * the detectorIndex field.
 * <b>Influencers</b>
 * <b>Quantiles</b> may contain model quantiles used in normalization and are
 * stored in documents of type {@link Quantiles#TYPE} <br>
 * <b>ModelSizeStats</b> This is stored in a flat structure <br>
 * <b>ModelSnapShot</b> This is stored in a flat structure <br>
 *
 * @see org.elasticsearch.xpack.prelert.job.persistence.ElasticsearchMappings
 */
public class JobResultsPersister extends AbstractComponent {

    private final Client client;


    public JobResultsPersister(Settings settings, Client client) {
        super(settings);
        this.client = client;
    }

    public Builder bulkPersisterBuilder(String jobId) {
        return new Builder(jobId);
    }

    public class Builder {
        private BulkRequestBuilder bulkRequest;
        private final String jobId;
        private final String indexName;

        private Builder (String jobId) {
            this.jobId = Objects.requireNonNull(jobId);
            indexName = AnomalyDetectorsIndex.getJobIndexName(jobId);
            bulkRequest = client.prepareBulk();
        }

        /**
         * Persist the result bucket and its bucket influencers
         * Buckets are persisted with a consistent ID
         *
         * @param bucket The bucket to persist
         * @return this
         */
        public Builder persistBucket(Bucket bucket) {
            try {
                XContentBuilder content = toXContentBuilder(bucket);
                logger.trace("[{}] ES API CALL: index result type {} to index {} at epoch {}",
                        jobId, Bucket.RESULT_TYPE_VALUE, indexName, bucket.getEpoch());

                bulkRequest.add(client.prepareIndex(indexName, Result.TYPE.getPreferredName(), bucket.getId()).setSource(content));

                persistBucketInfluencersStandalone(jobId, bucket.getId(), bucket.getBucketInfluencers());
            } catch (IOException e) {
                logger.error(new ParameterizedMessage("[{}] Error serialising bucket", new Object[] {jobId}, e));
            }

            return this;
        }

        private void persistBucketInfluencersStandalone(String jobId, String bucketId, List<BucketInfluencer> bucketInfluencers)
                throws IOException {
            if (bucketInfluencers != null && bucketInfluencers.isEmpty() == false) {
                for (BucketInfluencer bucketInfluencer : bucketInfluencers) {
                    XContentBuilder content = serialiseBucketInfluencerStandalone(bucketInfluencer);
                    // Need consistent IDs to ensure overwriting on renormalisation
                    String id = bucketInfluencer.getId();
                    logger.trace("[{}] ES BULK ACTION: index result type {} to index {} with ID {}",
                            jobId, BucketInfluencer.RESULT_TYPE_VALUE, indexName, id);
                    bulkRequest.add(client.prepareIndex(indexName, Result.TYPE.getPreferredName(), id).setSource(content));
                }
            }
        }

        /**
         * Persist a list of anomaly records
         *
         * @param records the records to persist
         * @return this
         */
        public Builder persistRecords(List<AnomalyRecord> records) {

            try {
                for (AnomalyRecord record : records) {
                    XContentBuilder content = toXContentBuilder(record);
                    logger.trace("[{}] ES BULK ACTION: index result type {} to index {} with ID {}",
                            jobId, AnomalyRecord.RESULT_TYPE_VALUE, indexName, record.getId());
                    bulkRequest.add(
                            client.prepareIndex(indexName, Result.TYPE.getPreferredName(), record.getId()).setSource(content));
                }
            } catch (IOException e) {
                logger.error(new ParameterizedMessage("[{}] Error serialising records", new Object [] {jobId}, e));
            }

            return this;
        }

        /**
         * Persist a list of influencers optionally using each influencer's ID or
         * an auto generated ID
         *
         * @param influencers the influencers to persist
         * @return this
         */
        public Builder persistInfluencers(List<Influencer> influencers) {
            try {
                for (Influencer influencer : influencers) {
                    XContentBuilder content = toXContentBuilder(influencer);
                    logger.trace("[{}] ES BULK ACTION: index result type {} to index {} with ID {}",
                            jobId, Influencer.RESULT_TYPE_VALUE, indexName, influencer.getId());
                    bulkRequest.add(
                            client.prepareIndex(indexName, Result.TYPE.getPreferredName(), influencer.getId()).setSource(content));
                }
            } catch (IOException e) {
                logger.error(new ParameterizedMessage("[{}] Error serialising influencers", new Object[] {jobId}, e));
            }

            return this;
        }

        /**
         * Persist {@link PerPartitionMaxProbabilities}
         *
         * @param partitionProbabilities The probabilities to persist
         * @return this
         */
        public Builder persistPerPartitionMaxProbabilities(PerPartitionMaxProbabilities partitionProbabilities) {
            try {
                XContentBuilder builder = toXContentBuilder(partitionProbabilities);
                logger.trace("[{}] ES API CALL: index result type {} to index {} at timestamp {} with ID {}",
                        jobId, PerPartitionMaxProbabilities.RESULT_TYPE_VALUE, indexName, partitionProbabilities.getTimestamp(),
                        partitionProbabilities.getId());
                bulkRequest.add(client.prepareIndex(indexName, Result.TYPE.getPreferredName(), partitionProbabilities.getId())
                        .setSource(builder));
            } catch (IOException e) {
                logger.error(new ParameterizedMessage("[{}] error serialising bucket per partition max normalized scores",
                        new Object[]{jobId}, e));
            }

            return this;
        }

        /**
         * Execute the bulk action
         */
        public void executeRequest() {
            logger.trace("[{}] ES API CALL: bulk request with {} actions", jobId, bulkRequest.numberOfActions());
            BulkResponse addRecordsResponse = bulkRequest.execute().actionGet();
            if (addRecordsResponse.hasFailures()) {
                logger.error("[{}] Bulk index of results has errors: {}", jobId, addRecordsResponse.buildFailureMessage());
            }
        }
    }

    /**
     * Persist the category definition
     *
     * @param category The category to be persisted
     */
    public void persistCategoryDefinition(CategoryDefinition category) {
        Persistable persistable = new Persistable(category.getJobId(), category, CategoryDefinition.TYPE.getPreferredName(),
                String.valueOf(category.getCategoryId()));
        persistable.persist();
        // Don't commit as we expect masses of these updates and they're not
        // read again by this process
    }

    /**
     * Persist the quantiles
     */
    public void persistQuantiles(Quantiles quantiles) {
        Persistable persistable = new Persistable(quantiles.getJobId(), quantiles, Quantiles.TYPE.getPreferredName(),
                Quantiles.QUANTILES_ID);
        if (persistable.persist()) {
            // Refresh the index when persisting quantiles so that previously
            // persisted results will be available for searching.  Do this using the
            // indices API rather than the index API (used to write the quantiles
            // above), because this will refresh all shards rather than just the
            // shard that the quantiles document itself was written to.
            commitWrites(quantiles.getJobId());
        }
    }

    /**
     * Persist a model snapshot description
     */
    public void persistModelSnapshot(ModelSnapshot modelSnapshot) {
        Persistable persistable = new Persistable(modelSnapshot.getJobId(), modelSnapshot, ModelSnapshot.TYPE.getPreferredName(),
                modelSnapshot.getSnapshotId());
        persistable.persist();
    }

    /**
     * Persist the memory usage data
     */
    public void persistModelSizeStats(ModelSizeStats modelSizeStats) {
        String jobId = modelSizeStats.getJobId();
        logger.trace("[{}] Persisting model size stats, for size {}", jobId, modelSizeStats.getModelBytes());
        Persistable persistable = new Persistable(modelSizeStats.getJobId(), modelSizeStats, Result.TYPE.getPreferredName(),
                ModelSizeStats.RESULT_TYPE_FIELD.getPreferredName());
        persistable.persist();
        persistable = new Persistable(modelSizeStats.getJobId(), modelSizeStats, Result.TYPE.getPreferredName(), null);
        persistable.persist();
        // Don't commit as we expect masses of these updates and they're only
        // for information at the API level
    }

    /**
     * Persist model debug output
     */
    public void persistModelDebugOutput(ModelDebugOutput modelDebugOutput) {
        Persistable persistable = new Persistable(modelDebugOutput.getJobId(), modelDebugOutput, Result.TYPE.getPreferredName(), null);
        persistable.persist();
        // Don't commit as we expect masses of these updates and they're not
        // read again by this process
    }

    /**
     * Persist state sent from the native process
     */
    public void persistBulkState(String jobId, BytesReference bytesRef) {
        try {
            // No validation - assume the native process has formatted the state correctly
            byte[] bytes = bytesRef.toBytesRef().bytes;
            logger.trace("[{}] ES API CALL: bulk index", jobId);
            client.prepareBulk()
            .add(bytes, 0, bytes.length)
            .execute().actionGet();
        } catch (Exception e) {
            logger.error((org.apache.logging.log4j.util.Supplier<?>)
                    () -> new ParameterizedMessage("[{}] Error persisting bulk state", jobId), e);
        }
    }

    /**
     * Delete any existing interim results synchronously
     */
    public void deleteInterimResults(String jobId) {
        JobDataDeleter deleter = new JobDataDeleter(client, jobId, true);
        deleter.deleteInterimResults();
        deleter.commit();
    }

    /**
     * Once all the job data has been written this function will be
     * called to commit the data if the implementing persister requires
     * it.
     *
     * @return True if successful
     */
    public boolean commitWrites(String jobId) {
        String indexName = AnomalyDetectorsIndex.getJobIndexName(jobId);
        // Refresh should wait for Lucene to make the data searchable
        logger.trace("[{}] ES API CALL: refresh index {}", jobId, indexName);
        client.admin().indices().refresh(new RefreshRequest(indexName)).actionGet();
        return true;
    }


    XContentBuilder toXContentBuilder(ToXContent obj) throws IOException {
        XContentBuilder builder = jsonBuilder();
        obj.toXContent(builder, ToXContent.EMPTY_PARAMS);
        return builder;
    }

    private XContentBuilder serialiseBucketInfluencerStandalone(BucketInfluencer bucketInfluencer) throws IOException {
        XContentBuilder builder = jsonBuilder();
        bucketInfluencer.toXContent(builder, ToXContent.EMPTY_PARAMS);
        return builder;
    }

    private class Persistable {

        private final String jobId;
        private final ToXContent object;
        private final String type;
        private final String id;

        Persistable(String jobId, ToXContent object, String type, String id) {
            this.jobId = jobId;
            this.object = object;
            this.type = type;
            this.id = id;
        }

        boolean persist() {
            if (object == null) {
                logger.warn("[{}] No {} to persist for job ", jobId, type);
                return false;
            }

            logCall();

            try {
                String indexName = AnomalyDetectorsIndex.getJobIndexName(jobId);
                client.prepareIndex(indexName, type, id)
                .setSource(toXContentBuilder(object))
                .execute().actionGet();
                return true;
            } catch (IOException e) {
                logger.error(new ParameterizedMessage("[{}] Error writing {}", new Object[]{jobId, type}, e));
                return false;
            }
        }

        private void logCall() {
            String indexName = AnomalyDetectorsIndex.getJobIndexName(jobId);
            if (id != null) {
                logger.trace("[{}] ES API CALL: index type {} to index {} with ID {}", jobId, type, indexName, id);
            } else {
                logger.trace("[{}] ES API CALL: index type {} to index {} with auto-generated ID", jobId, type, indexName);
            }
        }
    }
}
