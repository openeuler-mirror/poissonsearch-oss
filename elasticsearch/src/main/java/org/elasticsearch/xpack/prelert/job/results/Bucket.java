/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.job.results;

import org.elasticsearch.action.support.ToXContentToBytes;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.ParseFieldMatcherSupplier;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.ObjectParser.ValueType;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser.Token;
import org.elasticsearch.xpack.prelert.job.Job;
import org.elasticsearch.xpack.prelert.utils.ExceptionsHelper;
import org.elasticsearch.xpack.prelert.utils.time.TimeUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bucket Result POJO
 */
public class Bucket extends ToXContentToBytes implements Writeable {
    /*
     * Field Names
     */
    public static final ParseField JOB_ID = Job.ID;

    public static final ParseField TIMESTAMP = new ParseField("timestamp");
    public static final ParseField ANOMALY_SCORE = new ParseField("anomaly_score");
    public static final ParseField INITIAL_ANOMALY_SCORE = new ParseField("initial_anomaly_score");
    public static final ParseField MAX_NORMALIZED_PROBABILITY = new ParseField("max_normalized_probability");
    public static final ParseField IS_INTERIM = new ParseField("is_interim");
    public static final ParseField RECORD_COUNT = new ParseField("record_count");
    public static final ParseField EVENT_COUNT = new ParseField("event_count");
    public static final ParseField RECORDS = new ParseField("records");
    public static final ParseField BUCKET_INFLUENCERS = new ParseField("bucket_influencers");
    public static final ParseField BUCKET_SPAN = new ParseField("bucket_span");
    public static final ParseField PROCESSING_TIME_MS = new ParseField("processing_time_ms");
    public static final ParseField PARTITION_SCORES = new ParseField("partition_scores");

    // Used for QueryPage
    public static final ParseField RESULTS_FIELD = new ParseField("buckets");

    /**
     * Result type
     */
    public static final String RESULT_TYPE_VALUE = "bucket";
    public static final ParseField RESULT_TYPE_FIELD = new ParseField(RESULT_TYPE_VALUE);

    public static final ConstructingObjectParser<Bucket, ParseFieldMatcherSupplier> PARSER =
            new ConstructingObjectParser<>(RESULT_TYPE_VALUE, a -> new Bucket((String) a[0], (Date) a[1], (long) a[2]));

    static {
        PARSER.declareString(ConstructingObjectParser.constructorArg(), JOB_ID);
        PARSER.declareField(ConstructingObjectParser.constructorArg(), p -> {
            if (p.currentToken() == Token.VALUE_NUMBER) {
                return new Date(p.longValue());
            } else if (p.currentToken() == Token.VALUE_STRING) {
                return new Date(TimeUtils.dateStringToEpoch(p.text()));
            }
            throw new IllegalArgumentException("unexpected token [" + p.currentToken() + "] for [" + TIMESTAMP.getPreferredName() + "]");
        }, TIMESTAMP, ValueType.VALUE);
        PARSER.declareLong(ConstructingObjectParser.constructorArg(), BUCKET_SPAN);
        PARSER.declareDouble(Bucket::setAnomalyScore, ANOMALY_SCORE);
        PARSER.declareDouble(Bucket::setInitialAnomalyScore, INITIAL_ANOMALY_SCORE);
        PARSER.declareDouble(Bucket::setMaxNormalizedProbability, MAX_NORMALIZED_PROBABILITY);
        PARSER.declareBoolean(Bucket::setInterim, IS_INTERIM);
        PARSER.declareInt(Bucket::setRecordCount, RECORD_COUNT);
        PARSER.declareLong(Bucket::setEventCount, EVENT_COUNT);
        PARSER.declareObjectArray(Bucket::setRecords, AnomalyRecord.PARSER, RECORDS);
        PARSER.declareObjectArray(Bucket::setBucketInfluencers, BucketInfluencer.PARSER, BUCKET_INFLUENCERS);
        PARSER.declareLong(Bucket::setProcessingTimeMs, PROCESSING_TIME_MS);
        PARSER.declareObjectArray(Bucket::setPartitionScores, PartitionScore.PARSER, PARTITION_SCORES);
        PARSER.declareString((bucket, s) -> {}, Result.RESULT_TYPE);
    }

    private final String jobId;
    private final Date timestamp;
    private final long bucketSpan;
    private double anomalyScore;
    private double initialAnomalyScore;
    private double maxNormalizedProbability;
    private int recordCount;
    private List<AnomalyRecord> records = Collections.emptyList();
    private long eventCount;
    private boolean isInterim;
    private boolean hadBigNormalizedUpdate;
    private List<BucketInfluencer> bucketInfluencers = new ArrayList<>();
    private long processingTimeMs;
    private Map<String, Double> perPartitionMaxProbability = Collections.emptyMap();
    private List<PartitionScore> partitionScores = Collections.emptyList();

    public Bucket(String jobId, Date timestamp, long bucketSpan) {
        this.jobId = jobId;
        this.timestamp = ExceptionsHelper.requireNonNull(timestamp, TIMESTAMP.getPreferredName());
        this.bucketSpan = bucketSpan;
    }

    @SuppressWarnings("unchecked")
    public Bucket(StreamInput in) throws IOException {
        jobId = in.readString();
        timestamp = new Date(in.readLong());
        anomalyScore = in.readDouble();
        bucketSpan = in.readLong();
        initialAnomalyScore = in.readDouble();
        maxNormalizedProbability = in.readDouble();
        recordCount = in.readInt();
        records = in.readList(AnomalyRecord::new);
        eventCount = in.readLong();
        isInterim = in.readBoolean();
        bucketInfluencers = in.readList(BucketInfluencer::new);
        processingTimeMs = in.readLong();
        perPartitionMaxProbability = (Map<String, Double>) in.readGenericValue();
        partitionScores = in.readList(PartitionScore::new);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(jobId);
        out.writeLong(timestamp.getTime());
        out.writeDouble(anomalyScore);
        out.writeLong(bucketSpan);
        out.writeDouble(initialAnomalyScore);
        out.writeDouble(maxNormalizedProbability);
        out.writeInt(recordCount);
        out.writeList(records);
        out.writeLong(eventCount);
        out.writeBoolean(isInterim);
        out.writeList(bucketInfluencers);
        out.writeLong(processingTimeMs);
        out.writeGenericValue(perPartitionMaxProbability);
        out.writeList(partitionScores);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(JOB_ID.getPreferredName(), jobId);
        builder.field(TIMESTAMP.getPreferredName(), timestamp.getTime());
        builder.field(ANOMALY_SCORE.getPreferredName(), anomalyScore);
        builder.field(BUCKET_SPAN.getPreferredName(), bucketSpan);
        builder.field(INITIAL_ANOMALY_SCORE.getPreferredName(), initialAnomalyScore);
        builder.field(MAX_NORMALIZED_PROBABILITY.getPreferredName(), maxNormalizedProbability);
        builder.field(RECORD_COUNT.getPreferredName(), recordCount);
        if (records != null && !records.isEmpty()) {
            builder.field(RECORDS.getPreferredName(), records);
        }
        builder.field(EVENT_COUNT.getPreferredName(), eventCount);
        builder.field(IS_INTERIM.getPreferredName(), isInterim);
        builder.field(BUCKET_INFLUENCERS.getPreferredName(), bucketInfluencers);
        builder.field(PROCESSING_TIME_MS.getPreferredName(), processingTimeMs);
        builder.field(PARTITION_SCORES.getPreferredName(), partitionScores);
        builder.field(Result.RESULT_TYPE.getPreferredName(), RESULT_TYPE_VALUE);
        builder.endObject();
        return builder;
    }


    public String getJobId() {
        return jobId;
    }

    public String getId() {
        return jobId + "_" + timestamp.getTime() + "_" + bucketSpan;
    }

    /**
     * Timestamp expressed in seconds since the epoch (rather than Java's
     * convention of milliseconds).
     */
    public long getEpoch() {
        return timestamp.getTime() / 1000;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    /**
     * Bucketspan expressed in seconds
     */
    public long getBucketSpan() {
        return bucketSpan;
    }

    public double getAnomalyScore() {
        return anomalyScore;
    }

    public void setAnomalyScore(double anomalyScore) {
        this.anomalyScore = anomalyScore;
    }

    public double getInitialAnomalyScore() {
        return initialAnomalyScore;
    }

    public void setInitialAnomalyScore(double influenceScore) {
        this.initialAnomalyScore = influenceScore;
    }

    public double getMaxNormalizedProbability() {
        return maxNormalizedProbability;
    }

    public void setMaxNormalizedProbability(double maxNormalizedProbability) {
        this.maxNormalizedProbability = maxNormalizedProbability;
    }

    public int getRecordCount() {
        return recordCount;
    }

    public void setRecordCount(int recordCount) {
        this.recordCount = recordCount;
    }

    /**
     * Get all the anomaly records associated with this bucket.
     * The records are not part of the bucket document. They will
     * only be present when the bucket was retrieved and expanded
     * to contain the associated records.
     *
     * @return <code>null</code> or the anomaly records for the bucket
     * if the bucket was expanded.
     */
    @Nullable
    public List<AnomalyRecord> getRecords() {
        return records;
    }

    public void setRecords(List<AnomalyRecord> records) {
        this.records = records;
    }

    /**
     * The number of records (events) actually processed in this bucket.
     */
    public long getEventCount() {
        return eventCount;
    }

    public void setEventCount(long value) {
        eventCount = value;
    }

    public boolean isInterim() {
        return isInterim;
    }

    public void setInterim(boolean isInterim) {
        this.isInterim = isInterim;
    }

    public long getProcessingTimeMs() {
        return processingTimeMs;
    }

    public void setProcessingTimeMs(long timeMs) {
        processingTimeMs = timeMs;
    }

    public List<BucketInfluencer> getBucketInfluencers() {
        return bucketInfluencers;
    }

    public void setBucketInfluencers(List<BucketInfluencer> bucketInfluencers) {
        this.bucketInfluencers = bucketInfluencers;
    }

    public void addBucketInfluencer(BucketInfluencer bucketInfluencer) {
        if (bucketInfluencers == null) {
            bucketInfluencers = new ArrayList<>();
        }
        bucketInfluencers.add(bucketInfluencer);
    }

    public List<PartitionScore> getPartitionScores() {
        return partitionScores;
    }

    public void setPartitionScores(List<PartitionScore> scores) {
        partitionScores = scores;
    }

    public Map<String, Double> getPerPartitionMaxProbability() {
        return perPartitionMaxProbability;
    }

    public void setPerPartitionMaxProbability(Map<String, Double> perPartitionMaxProbability) {
        this.perPartitionMaxProbability = perPartitionMaxProbability;
    }

    public double partitionInitialAnomalyScore(String partitionValue) {
        Optional<PartitionScore> first = partitionScores.stream().filter(s -> partitionValue.equals(s.getPartitionFieldValue()))
                .findFirst();

        return first.isPresent() ? first.get().getInitialAnomalyScore() : 0.0;
    }

    public double partitionAnomalyScore(String partitionValue) {
        Optional<PartitionScore> first = partitionScores.stream().filter(s -> partitionValue.equals(s.getPartitionFieldValue()))
                .findFirst();

        return first.isPresent() ? first.get().getAnomalyScore() : 0.0;
    }

    @Override
    public int hashCode() {
        // hadBigNormalizedUpdate is deliberately excluded from the hash
        // as is id, which is generated by the datastore
        return Objects.hash(jobId, timestamp, eventCount, initialAnomalyScore, anomalyScore, maxNormalizedProbability, recordCount, records,
                isInterim, bucketSpan, bucketInfluencers);
    }

    /**
     * Compare all the fields and embedded anomaly records (if any)
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (other instanceof Bucket == false) {
            return false;
        }

        Bucket that = (Bucket) other;

        // hadBigNormalizedUpdate is deliberately excluded from the test
        // as is id, which is generated by the datastore
        return Objects.equals(this.jobId, that.jobId) && Objects.equals(this.timestamp, that.timestamp)
                && (this.eventCount == that.eventCount) && (this.bucketSpan == that.bucketSpan)
                && (this.anomalyScore == that.anomalyScore) && (this.initialAnomalyScore == that.initialAnomalyScore)
                && (this.maxNormalizedProbability == that.maxNormalizedProbability) && (this.recordCount == that.recordCount)
                && Objects.equals(this.records, that.records) && Objects.equals(this.isInterim, that.isInterim)
                && Objects.equals(this.bucketInfluencers, that.bucketInfluencers);
    }

    public boolean hadBigNormalizedUpdate() {
        return hadBigNormalizedUpdate;
    }

    public void resetBigNormalizedUpdateFlag() {
        hadBigNormalizedUpdate = false;
    }

    public void raiseBigNormalizedUpdateFlag() {
        hadBigNormalizedUpdate = true;
    }

    /**
     * This method encapsulated the logic for whether a bucket should be
     * normalized. The decision depends on two factors.
     *
     * The first is whether the bucket has bucket influencers. Since bucket
     * influencers were introduced, every bucket must have at least one bucket
     * influencer. If it does not, it means it is a bucket persisted with an
     * older version and should not be normalized.
     *
     * The second factor has to do with minimising the number of buckets that
     * are sent for normalization. Buckets that have no records and a score of
     * zero should not be normalized as their score will not change and they
     * will just add overhead.
     *
     * @return true if the bucket should be normalized or false otherwise
     */
    public boolean isNormalizable() {
        if (bucketInfluencers == null || bucketInfluencers.isEmpty()) {
            return false;
        }
        return anomalyScore > 0.0 || recordCount > 0;
    }
}
