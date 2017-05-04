/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.job.results;

import org.elasticsearch.action.support.ToXContentToBytes;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.ObjectParser.ValueType;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser.Token;
import org.elasticsearch.xpack.ml.job.config.Job;
import org.elasticsearch.xpack.ml.utils.ExceptionsHelper;
import org.elasticsearch.xpack.ml.utils.time.TimeUtils;

import java.io.IOException;
import java.util.Date;
import java.util.Objects;

public class Influencer extends ToXContentToBytes implements Writeable {
    /**
     * Result type
     */
    public static final String RESULT_TYPE_VALUE = "influencer";
    public static final ParseField RESULT_TYPE_FIELD = new ParseField(RESULT_TYPE_VALUE);

    /*
     * Field names
     */
    public static final ParseField PROBABILITY = new ParseField("probability");
    public static final ParseField SEQUENCE_NUM = new ParseField("sequence_num");
    public static final ParseField BUCKET_SPAN = new ParseField("bucket_span");
    public static final ParseField INFLUENCER_FIELD_NAME = new ParseField("influencer_field_name");
    public static final ParseField INFLUENCER_FIELD_VALUE = new ParseField("influencer_field_value");
    public static final ParseField INITIAL_INFLUENCER_SCORE = new ParseField("initial_influencer_score");
    public static final ParseField INFLUENCER_SCORE = new ParseField("influencer_score");

    // Used for QueryPage
    public static final ParseField RESULTS_FIELD = new ParseField("influencers");

    public static final ConstructingObjectParser<Influencer, Void> PARSER = new ConstructingObjectParser<>(
            RESULT_TYPE_FIELD.getPreferredName(), true, a -> new Influencer((String) a[0], (String) a[1], (String) a[2],
            (Date) a[3], (long) a[4], (int) a[5]));

    static {
        PARSER.declareString(ConstructingObjectParser.constructorArg(), Job.ID);
        PARSER.declareString(ConstructingObjectParser.constructorArg(), INFLUENCER_FIELD_NAME);
        PARSER.declareString(ConstructingObjectParser.constructorArg(), INFLUENCER_FIELD_VALUE);
        PARSER.declareField(ConstructingObjectParser.constructorArg(), p -> {
            if (p.currentToken() == Token.VALUE_NUMBER) {
                return new Date(p.longValue());
            } else if (p.currentToken() == Token.VALUE_STRING) {
                return new Date(TimeUtils.dateStringToEpoch(p.text()));
            }
            throw new IllegalArgumentException("unexpected token [" + p.currentToken() + "] for ["
                    + Result.TIMESTAMP.getPreferredName() + "]");
        }, Result.TIMESTAMP, ValueType.VALUE);
        PARSER.declareLong(ConstructingObjectParser.constructorArg(), BUCKET_SPAN);
        PARSER.declareInt(ConstructingObjectParser.constructorArg(), SEQUENCE_NUM);
        PARSER.declareString((influencer, s) -> {}, Result.RESULT_TYPE);
        PARSER.declareDouble(Influencer::setProbability, PROBABILITY);
        PARSER.declareDouble(Influencer::setInfluencerScore, INFLUENCER_SCORE);
        PARSER.declareDouble(Influencer::setInitialInfluencerScore, INITIAL_INFLUENCER_SCORE);
        PARSER.declareBoolean(Influencer::setInterim, Result.IS_INTERIM);
    }

    private final String jobId;
    private final Date timestamp;
    private final long bucketSpan;
    private final int sequenceNum;
    private String influenceField;
    private String influenceValue;
    private double probability;
    private double initialInfluencerScore;
    private double influencerScore;
    private boolean isInterim;

    public Influencer(String jobId, String fieldName, String fieldValue, Date timestamp, long bucketSpan, int sequenceNum) {
        this.jobId = jobId;
        influenceField = fieldName;
        influenceValue = fieldValue;
        this.timestamp = ExceptionsHelper.requireNonNull(timestamp, Result.TIMESTAMP.getPreferredName());
        this.bucketSpan = bucketSpan;
        this.sequenceNum = sequenceNum;
    }

    public Influencer(StreamInput in) throws IOException {
        jobId = in.readString();
        timestamp = new Date(in.readLong());
        influenceField = in.readString();
        influenceValue = in.readString();
        probability = in.readDouble();
        initialInfluencerScore = in.readDouble();
        influencerScore = in.readDouble();
        isInterim = in.readBoolean();
        bucketSpan = in.readLong();
        sequenceNum = in.readInt();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(jobId);
        out.writeLong(timestamp.getTime());
        out.writeString(influenceField);
        out.writeString(influenceValue);
        out.writeDouble(probability);
        out.writeDouble(initialInfluencerScore);
        out.writeDouble(influencerScore);
        out.writeBoolean(isInterim);
        out.writeLong(bucketSpan);
        out.writeInt(sequenceNum);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(Job.ID.getPreferredName(), jobId);
        builder.field(Result.RESULT_TYPE.getPreferredName(), RESULT_TYPE_VALUE);
        builder.field(INFLUENCER_FIELD_NAME.getPreferredName(), influenceField);
        builder.field(INFLUENCER_FIELD_VALUE.getPreferredName(), influenceValue);
        if (ReservedFieldNames.isValidFieldName(influenceField)) {
            builder.field(influenceField, influenceValue);
        }
        builder.field(INFLUENCER_SCORE.getPreferredName(), influencerScore);
        builder.field(INITIAL_INFLUENCER_SCORE.getPreferredName(), initialInfluencerScore);
        builder.field(PROBABILITY.getPreferredName(), probability);
        builder.field(SEQUENCE_NUM.getPreferredName(), sequenceNum);
        builder.field(BUCKET_SPAN.getPreferredName(), bucketSpan);
        builder.field(Result.IS_INTERIM.getPreferredName(), isInterim);
        builder.dateField(Result.TIMESTAMP.getPreferredName(), Result.TIMESTAMP.getPreferredName() + "_string", timestamp.getTime());
        builder.endObject();
        return builder;
    }

    public String getJobId() {
        return jobId;
    }

    public String getId() {
        return jobId + "_" + timestamp.getTime() + "_" + bucketSpan + "_" + sequenceNum;
    }

    public double getProbability() {
        return probability;
    }

    public void setProbability(double probability) {
        this.probability = probability;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    public String getInfluencerFieldName() {
        return influenceField;
    }

    public String getInfluencerFieldValue() {
        return influenceValue;
    }

    public double getInitialInfluencerScore() {
        return initialInfluencerScore;
    }

    public void setInitialInfluencerScore(double score) {
        initialInfluencerScore = score;
    }

    public double getInfluencerScore() {
        return influencerScore;
    }

    public void setInfluencerScore(double score) {
        influencerScore = score;
    }

    public boolean isInterim() {
        return isInterim;
    }

    public void setInterim(boolean value) {
        isInterim = value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(jobId, timestamp, influenceField, influenceValue, initialInfluencerScore,
                influencerScore, probability, isInterim, bucketSpan, sequenceNum);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj == null) {
            return false;
        }

        if (getClass() != obj.getClass()) {
            return false;
        }

        Influencer other = (Influencer) obj;
        return Objects.equals(jobId, other.jobId) && Objects.equals(timestamp, other.timestamp)
                && Objects.equals(influenceField, other.influenceField)
                && Objects.equals(influenceValue, other.influenceValue)
                && Double.compare(initialInfluencerScore, other.initialInfluencerScore) == 0
                && Double.compare(influencerScore, other.influencerScore) == 0 && Double.compare(probability, other.probability) == 0
                && (isInterim == other.isInterim) && (bucketSpan == other.bucketSpan) && (sequenceNum == other.sequenceNum);
    }
}
