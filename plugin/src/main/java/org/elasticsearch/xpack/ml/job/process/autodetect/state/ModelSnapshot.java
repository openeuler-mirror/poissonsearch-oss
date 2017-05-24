/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.job.process.autodetect.state;

import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.action.support.ToXContentToBytes;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.ObjectParser.ValueType;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentParser.Token;
import org.elasticsearch.xpack.ml.job.config.Job;
import org.elasticsearch.xpack.ml.utils.time.TimeUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;


/**
 * ModelSnapshot Result POJO
 */
public class ModelSnapshot extends ToXContentToBytes implements Writeable {
    /**
     * Field Names
     */
    public static final ParseField TIMESTAMP = new ParseField("timestamp");
    public static final ParseField DESCRIPTION = new ParseField("description");
    public static final ParseField SNAPSHOT_ID = new ParseField("snapshot_id");
    public static final ParseField SNAPSHOT_DOC_COUNT = new ParseField("snapshot_doc_count");
    public static final ParseField LATEST_RECORD_TIME = new ParseField("latest_record_time_stamp");
    public static final ParseField LATEST_RESULT_TIME = new ParseField("latest_result_time_stamp");
    public static final ParseField RETAIN = new ParseField("retain");

    // Used for QueryPage
    public static final ParseField RESULTS_FIELD = new ParseField("model_snapshots");

    /**
     * Elasticsearch type
     */
    public static final ParseField TYPE = new ParseField("model_snapshot");

    public static final ObjectParser<Builder, Void> PARSER = new ObjectParser<>(TYPE.getPreferredName(), Builder::new);

    static {
        PARSER.declareString(Builder::setJobId, Job.ID);
        PARSER.declareField(Builder::setTimestamp, p -> {
            if (p.currentToken() == Token.VALUE_NUMBER) {
                return new Date(p.longValue());
            } else if (p.currentToken() == Token.VALUE_STRING) {
                return new Date(TimeUtils.dateStringToEpoch(p.text()));
            }
            throw new IllegalArgumentException("unexpected token [" + p.currentToken() + "] for [" + TIMESTAMP.getPreferredName() + "]");
        }, TIMESTAMP, ValueType.VALUE);
        PARSER.declareString(Builder::setDescription, DESCRIPTION);
        PARSER.declareString(Builder::setSnapshotId, SNAPSHOT_ID);
        PARSER.declareInt(Builder::setSnapshotDocCount, SNAPSHOT_DOC_COUNT);
        PARSER.declareObject(Builder::setModelSizeStats, ModelSizeStats.PARSER, ModelSizeStats.RESULT_TYPE_FIELD);
        PARSER.declareField(Builder::setLatestRecordTimeStamp, p -> {
            if (p.currentToken() == Token.VALUE_NUMBER) {
                return new Date(p.longValue());
            } else if (p.currentToken() == Token.VALUE_STRING) {
                return new Date(TimeUtils.dateStringToEpoch(p.text()));
            }
            throw new IllegalArgumentException(
                    "unexpected token [" + p.currentToken() + "] for [" + LATEST_RECORD_TIME.getPreferredName() + "]");
        }, LATEST_RECORD_TIME, ValueType.VALUE);
        PARSER.declareField(Builder::setLatestResultTimeStamp, p -> {
            if (p.currentToken() == Token.VALUE_NUMBER) {
                return new Date(p.longValue());
            } else if (p.currentToken() == Token.VALUE_STRING) {
                return new Date(TimeUtils.dateStringToEpoch(p.text()));
            }
            throw new IllegalArgumentException(
                    "unexpected token [" + p.currentToken() + "] for [" + LATEST_RESULT_TIME.getPreferredName() + "]");
        }, LATEST_RESULT_TIME, ValueType.VALUE);
        PARSER.declareObject(Builder::setQuantiles, Quantiles.PARSER, Quantiles.TYPE);
        PARSER.declareBoolean(Builder::setRetain, RETAIN);
    }

    private final String jobId;
    private final Date timestamp;
    private final String description;
    private final String snapshotId;
    private final int snapshotDocCount;
    private final ModelSizeStats modelSizeStats;
    private final Date latestRecordTimeStamp;
    private final Date latestResultTimeStamp;
    private final Quantiles quantiles;
    private final boolean retain;

    private ModelSnapshot(String jobId, Date timestamp, String description, String snapshotId, int snapshotDocCount,
                          ModelSizeStats modelSizeStats, Date latestRecordTimeStamp, Date latestResultTimeStamp, Quantiles quantiles,
                          boolean retain) {
        this.jobId = jobId;
        this.timestamp = timestamp;
        this.description = description;
        this.snapshotId = snapshotId;
        this.snapshotDocCount = snapshotDocCount;
        this.modelSizeStats = modelSizeStats;
        this.latestRecordTimeStamp = latestRecordTimeStamp;
        this.latestResultTimeStamp = latestResultTimeStamp;
        this.quantiles = quantiles;
        this.retain = retain;
    }

    public ModelSnapshot(StreamInput in) throws IOException {
        jobId = in.readString();
        timestamp = in.readBoolean() ? new Date(in.readVLong()) : null;
        description = in.readOptionalString();
        snapshotId = in.readOptionalString();
        snapshotDocCount = in.readInt();
        modelSizeStats = in.readOptionalWriteable(ModelSizeStats::new);
        latestRecordTimeStamp = in.readBoolean() ? new Date(in.readVLong()) : null;
        latestResultTimeStamp = in.readBoolean() ? new Date(in.readVLong()) : null;
        quantiles = in.readOptionalWriteable(Quantiles::new);
        retain = in.readBoolean();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(jobId);
        if (timestamp != null) {
            out.writeBoolean(true);
            out.writeVLong(timestamp.getTime());
        } else {
            out.writeBoolean(false);
        }
        out.writeOptionalString(description);
        out.writeOptionalString(snapshotId);
        out.writeInt(snapshotDocCount);
        out.writeOptionalWriteable(modelSizeStats);
        if (latestRecordTimeStamp != null) {
            out.writeBoolean(true);
            out.writeVLong(latestRecordTimeStamp.getTime());
        } else {
            out.writeBoolean(false);
        }
        if (latestResultTimeStamp != null) {
            out.writeBoolean(true);
            out.writeVLong(latestResultTimeStamp.getTime());
        } else {
            out.writeBoolean(false);
        }
        out.writeOptionalWriteable(quantiles);
        out.writeBoolean(retain);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(Job.ID.getPreferredName(), jobId);
        if (timestamp != null) {
            builder.dateField(TIMESTAMP.getPreferredName(), TIMESTAMP.getPreferredName() + "_string", timestamp.getTime());
        }
        if (description != null) {
            builder.field(DESCRIPTION.getPreferredName(), description);
        }
        if (snapshotId != null) {
            builder.field(SNAPSHOT_ID.getPreferredName(), snapshotId);
        }
        builder.field(SNAPSHOT_DOC_COUNT.getPreferredName(), snapshotDocCount);
        if (modelSizeStats != null) {
            builder.field(ModelSizeStats.RESULT_TYPE_FIELD.getPreferredName(), modelSizeStats);
        }
        if (latestRecordTimeStamp != null) {
            builder.dateField(LATEST_RECORD_TIME.getPreferredName(), LATEST_RECORD_TIME.getPreferredName() + "_string",
                    latestRecordTimeStamp.getTime());
        }
        if (latestResultTimeStamp != null) {
            builder.dateField(LATEST_RESULT_TIME.getPreferredName(), LATEST_RESULT_TIME.getPreferredName() + "_string",
                    latestResultTimeStamp.getTime());
        }
        if (quantiles != null) {
            builder.field(Quantiles.TYPE.getPreferredName(), quantiles);
        }
        builder.field(RETAIN.getPreferredName(), retain);
        builder.endObject();
        return builder;
    }

    public String getJobId() {
        return jobId;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    public String getDescription() {
        return description;
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public int getSnapshotDocCount() {
        return snapshotDocCount;
    }

    public ModelSizeStats getModelSizeStats() {
        return modelSizeStats;
    }

    public Quantiles getQuantiles() {
        return quantiles;
    }

    public Date getLatestRecordTimeStamp() {
        return latestRecordTimeStamp;
    }

    public Date getLatestResultTimeStamp() {
        return latestResultTimeStamp;
    }

    @Override
    public int hashCode() {
        return Objects.hash(jobId, timestamp, description, snapshotId, quantiles, snapshotDocCount, modelSizeStats, latestRecordTimeStamp,
                latestResultTimeStamp, retain);
    }

    /**
     * Compare all the fields.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (other instanceof ModelSnapshot == false) {
            return false;
        }

        ModelSnapshot that = (ModelSnapshot) other;

        return Objects.equals(this.jobId, that.jobId)
                && Objects.equals(this.timestamp, that.timestamp)
                && Objects.equals(this.description, that.description)
                && Objects.equals(this.snapshotId, that.snapshotId)
                && this.snapshotDocCount == that.snapshotDocCount
                && Objects.equals(this.modelSizeStats, that.modelSizeStats)
                && Objects.equals(this.quantiles, that.quantiles)
                && Objects.equals(this.latestRecordTimeStamp, that.latestRecordTimeStamp)
                && Objects.equals(this.latestResultTimeStamp, that.latestResultTimeStamp)
                && this.retain == that.retain;
    }

    private String stateDocumentPrefix() {
        return jobId + "-" + snapshotId;
    }

    public List<String> stateDocumentIds() {
        String prefix = stateDocumentPrefix();
        List<String> stateDocumentIds = new ArrayList<>(snapshotDocCount);
        // The state documents count suffices are 1-based
        for (int i = 1; i <= snapshotDocCount; i++) {
            stateDocumentIds.add(prefix + '#' + i);
        }
        return stateDocumentIds;
    }

    public static String documentId(ModelSnapshot snapshot) {
        return documentId(snapshot.getJobId(), snapshot.getSnapshotId());
    }

    public static String documentId(String jobId, String snapshotId) {
        return jobId + "_model_snapshot_" + snapshotId;
    }

    public static ModelSnapshot fromJson(BytesReference bytesReference) {
        try (XContentParser parser = XContentFactory.xContent(bytesReference).createParser(NamedXContentRegistry.EMPTY, bytesReference)) {
            return PARSER.apply(parser, null).build();
        } catch (IOException e) {
            throw new ElasticsearchParseException("failed to parse modelSnapshot", e);
        }
    }

    public static class Builder {
        private String jobId;
        private Date timestamp;
        private String description;
        private String snapshotId;
        private int snapshotDocCount;
        private ModelSizeStats modelSizeStats;
        private Date latestRecordTimeStamp;
        private Date latestResultTimeStamp;
        private Quantiles quantiles;
        private boolean retain;

        public Builder() {
        }

        public Builder(String jobId) {
            this();
            this.jobId = jobId;
        }

        public Builder(ModelSnapshot modelSnapshot) {
            this.jobId = modelSnapshot.jobId;
            this.timestamp = modelSnapshot.timestamp;
            this.description = modelSnapshot.description;
            this.snapshotId = modelSnapshot.snapshotId;
            this.snapshotDocCount = modelSnapshot.snapshotDocCount;
            this.modelSizeStats = modelSnapshot.modelSizeStats;
            this.latestRecordTimeStamp = modelSnapshot.latestRecordTimeStamp;
            this.latestResultTimeStamp = modelSnapshot.latestResultTimeStamp;
            this.quantiles = modelSnapshot.quantiles;
            this.retain = modelSnapshot.retain;
        }

        public Builder setJobId(String jobId) {
            this.jobId = jobId;
            return this;
        }

        public Builder setTimestamp(Date timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder setDescription(String description) {
            this.description = description;
            return this;
        }

        public Builder setSnapshotId(String snapshotId) {
            this.snapshotId = snapshotId;
            return this;
        }

        public Builder setSnapshotDocCount(int snapshotDocCount) {
            this.snapshotDocCount = snapshotDocCount;
            return this;
        }

        public Builder setModelSizeStats(ModelSizeStats.Builder modelSizeStats) {
            this.modelSizeStats = modelSizeStats.build();
            return this;
        }

        public Builder setModelSizeStats(ModelSizeStats modelSizeStats) {
            this.modelSizeStats = modelSizeStats;
            return this;
        }

        public Builder setLatestRecordTimeStamp(Date latestRecordTimeStamp) {
            this.latestRecordTimeStamp = latestRecordTimeStamp;
            return this;
        }

        public Builder setLatestResultTimeStamp(Date latestResultTimeStamp) {
            this.latestResultTimeStamp = latestResultTimeStamp;
            return this;
        }

        public Builder setQuantiles(Quantiles quantiles) {
            this.quantiles = quantiles;
            return this;
        }

        public Builder setRetain(boolean value) {
            this.retain = value;
            return this;
        }

        public ModelSnapshot build() {
            return new ModelSnapshot(jobId, timestamp, description, snapshotId, snapshotDocCount, modelSizeStats, latestRecordTimeStamp,
                    latestResultTimeStamp, quantiles, retain);
        }
    }
}
