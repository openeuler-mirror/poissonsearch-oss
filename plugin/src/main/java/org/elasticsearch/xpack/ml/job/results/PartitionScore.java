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
import org.elasticsearch.common.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Objects;

public class PartitionScore extends ToXContentToBytes implements Writeable {
    public static final ParseField PARTITION_SCORE = new ParseField("partition_score");

    private final String partitionFieldValue;
    private final String partitionFieldName;
    private final double initialRecordScore;
    private double recordScore;
    private double probability;

    public static final ConstructingObjectParser<PartitionScore, Void> PARSER = new ConstructingObjectParser<>(
            PARTITION_SCORE.getPreferredName(), a -> new PartitionScore((String) a[0], (String) a[1], (Double) a[2], (Double) a[3],
            (Double) a[4]));

    static {
        PARSER.declareString(ConstructingObjectParser.constructorArg(), AnomalyRecord.PARTITION_FIELD_NAME);
        PARSER.declareString(ConstructingObjectParser.constructorArg(), AnomalyRecord.PARTITION_FIELD_VALUE);
        PARSER.declareDouble(ConstructingObjectParser.constructorArg(), AnomalyRecord.INITIAL_RECORD_SCORE);
        PARSER.declareDouble(ConstructingObjectParser.constructorArg(), AnomalyRecord.RECORD_SCORE);
        PARSER.declareDouble(ConstructingObjectParser.constructorArg(), AnomalyRecord.PROBABILITY);
    }

    public PartitionScore(String fieldName, String fieldValue, double initialRecordScore, double recordScore, double probability) {
        partitionFieldName = fieldName;
        partitionFieldValue = fieldValue;
        this.initialRecordScore = initialRecordScore;
        this.recordScore = recordScore;
        this.probability = probability;
    }

    public PartitionScore(StreamInput in) throws IOException {
        partitionFieldName = in.readString();
        partitionFieldValue = in.readString();
        initialRecordScore = in.readDouble();
        recordScore = in.readDouble();
        probability = in.readDouble();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(partitionFieldName);
        out.writeString(partitionFieldValue);
        out.writeDouble(initialRecordScore);
        out.writeDouble(recordScore);
        out.writeDouble(probability);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(AnomalyRecord.PARTITION_FIELD_NAME.getPreferredName(), partitionFieldName);
        builder.field(AnomalyRecord.PARTITION_FIELD_VALUE.getPreferredName(), partitionFieldValue);
        builder.field(AnomalyRecord.INITIAL_RECORD_SCORE.getPreferredName(), initialRecordScore);
        builder.field(AnomalyRecord.RECORD_SCORE.getPreferredName(), recordScore);
        builder.field(AnomalyRecord.PROBABILITY.getPreferredName(), probability);
        builder.endObject();
        return builder;
    }

    public double getInitialRecordScore() {
        return initialRecordScore;
    }

    public double getRecordScore() {
        return recordScore;
    }

    public void setRecordScore(double recordScore) {
        this.recordScore = recordScore;
    }

    public String getPartitionFieldName() {
        return partitionFieldName;
    }

    public String getPartitionFieldValue() {
        return partitionFieldValue;
    }

    public double getProbability() {
        return probability;
    }

    public void setProbability(double probability) {
        this.probability = probability;
    }

    @Override
    public int hashCode() {
        return Objects.hash(partitionFieldName, partitionFieldValue, probability, initialRecordScore, recordScore);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (other instanceof PartitionScore == false) {
            return false;
        }

        PartitionScore that = (PartitionScore) other;

        // id is excluded from the test as it is generated by the datastore
        return Objects.equals(this.partitionFieldValue, that.partitionFieldValue)
                && Objects.equals(this.partitionFieldName, that.partitionFieldName) && (this.probability == that.probability)
                && (this.initialRecordScore == that.initialRecordScore) && (this.recordScore == that.recordScore);
    }
}
