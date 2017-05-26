/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.job.config;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.AbstractDiffable;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.ObjectParser.ValueType;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser.Token;
import org.elasticsearch.xpack.ml.job.messages.Messages;
import org.elasticsearch.xpack.ml.job.persistence.AnomalyDetectorsIndex;
import org.elasticsearch.xpack.ml.utils.ExceptionsHelper;
import org.elasticsearch.xpack.ml.utils.MlStrings;
import org.elasticsearch.xpack.ml.utils.time.TimeUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * This class represents a configured and created Job. The creation time is set
 * to the time the object was constructed and the finished time and last
 * data time fields are {@code null} until the job has seen some data or it is
 * finished respectively.
 */
public class Job extends AbstractDiffable<Job> implements Writeable, ToXContent {

    public static final String TYPE = "job";

    public static final String ANOMALY_DETECTOR_JOB_TYPE = "anomaly_detector";

    /*
     * Field names used in serialization
     */
    public static final ParseField ID = new ParseField("job_id");
    public static final ParseField JOB_TYPE = new ParseField("job_type");
    public static final ParseField JOB_VERSION = new ParseField("job_version");
    public static final ParseField ANALYSIS_CONFIG = new ParseField("analysis_config");
    public static final ParseField ANALYSIS_LIMITS = new ParseField("analysis_limits");
    public static final ParseField CREATE_TIME = new ParseField("create_time");
    public static final ParseField CUSTOM_SETTINGS = new ParseField("custom_settings");
    public static final ParseField DATA_DESCRIPTION = new ParseField("data_description");
    public static final ParseField DESCRIPTION = new ParseField("description");
    public static final ParseField FINISHED_TIME = new ParseField("finished_time");
    public static final ParseField LAST_DATA_TIME = new ParseField("last_data_time");
    public static final ParseField MODEL_PLOT_CONFIG = new ParseField("model_plot_config");
    public static final ParseField RENORMALIZATION_WINDOW_DAYS = new ParseField("renormalization_window_days");
    public static final ParseField BACKGROUND_PERSIST_INTERVAL = new ParseField("background_persist_interval");
    public static final ParseField MODEL_SNAPSHOT_RETENTION_DAYS = new ParseField("model_snapshot_retention_days");
    public static final ParseField RESULTS_RETENTION_DAYS = new ParseField("results_retention_days");
    public static final ParseField MODEL_SNAPSHOT_ID = new ParseField("model_snapshot_id");
    public static final ParseField RESULTS_INDEX_NAME = new ParseField("results_index_name");
    public static final ParseField DELETED = new ParseField("deleted");

    // Used for QueryPage
    public static final ParseField RESULTS_FIELD = new ParseField("jobs");

    public static final String ALL = "_all";

    public static final ObjectParser<Builder, Void> PARSER = new ObjectParser<>("job_details", Builder::new);

    public static final int MAX_JOB_ID_LENGTH = 64;
    public static final TimeValue MIN_BACKGROUND_PERSIST_INTERVAL = TimeValue.timeValueHours(1);

    static {
        PARSER.declareString(Builder::setId, ID);
        PARSER.declareString(Builder::setJobType, JOB_TYPE);
        PARSER.declareString(Builder::setJobVersion, JOB_VERSION);
        PARSER.declareStringOrNull(Builder::setDescription, DESCRIPTION);
        PARSER.declareField(Builder::setCreateTime, p -> {
            if (p.currentToken() == Token.VALUE_NUMBER) {
                return new Date(p.longValue());
            } else if (p.currentToken() == Token.VALUE_STRING) {
                return new Date(TimeUtils.dateStringToEpoch(p.text()));
            }
            throw new IllegalArgumentException("unexpected token [" + p.currentToken() + "] for [" + CREATE_TIME.getPreferredName() + "]");
        }, CREATE_TIME, ValueType.VALUE);
        PARSER.declareField(Builder::setFinishedTime, p -> {
            if (p.currentToken() == Token.VALUE_NUMBER) {
                return new Date(p.longValue());
            } else if (p.currentToken() == Token.VALUE_STRING) {
                return new Date(TimeUtils.dateStringToEpoch(p.text()));
            }
            throw new IllegalArgumentException(
                    "unexpected token [" + p.currentToken() + "] for [" + FINISHED_TIME.getPreferredName() + "]");
        }, FINISHED_TIME, ValueType.VALUE);
        PARSER.declareField(Builder::setLastDataTime, p -> {
            if (p.currentToken() == Token.VALUE_NUMBER) {
                return new Date(p.longValue());
            } else if (p.currentToken() == Token.VALUE_STRING) {
                return new Date(TimeUtils.dateStringToEpoch(p.text()));
            }
            throw new IllegalArgumentException(
                    "unexpected token [" + p.currentToken() + "] for [" + LAST_DATA_TIME.getPreferredName() + "]");
        }, LAST_DATA_TIME, ValueType.VALUE);
        PARSER.declareObject(Builder::setAnalysisConfig, AnalysisConfig.PARSER, ANALYSIS_CONFIG);
        PARSER.declareObject(Builder::setAnalysisLimits, AnalysisLimits.PARSER, ANALYSIS_LIMITS);
        PARSER.declareObject(Builder::setDataDescription, DataDescription.PARSER, DATA_DESCRIPTION);
        PARSER.declareObject(Builder::setModelPlotConfig, ModelPlotConfig.PARSER, MODEL_PLOT_CONFIG);
        PARSER.declareLong(Builder::setRenormalizationWindowDays, RENORMALIZATION_WINDOW_DAYS);
        PARSER.declareString((builder, val) -> builder.setBackgroundPersistInterval(
                TimeValue.parseTimeValue(val, BACKGROUND_PERSIST_INTERVAL.getPreferredName())), BACKGROUND_PERSIST_INTERVAL);
        PARSER.declareLong(Builder::setResultsRetentionDays, RESULTS_RETENTION_DAYS);
        PARSER.declareLong(Builder::setModelSnapshotRetentionDays, MODEL_SNAPSHOT_RETENTION_DAYS);
        PARSER.declareField(Builder::setCustomSettings, (p, c) -> p.map(), CUSTOM_SETTINGS, ValueType.OBJECT);
        PARSER.declareStringOrNull(Builder::setModelSnapshotId, MODEL_SNAPSHOT_ID);
        PARSER.declareString(Builder::setResultsIndexName, RESULTS_INDEX_NAME);
        PARSER.declareBoolean(Builder::setDeleted, DELETED);
    }

    private final String jobId;
    private final String jobType;

    /**
     * The version when the job was created.
     * Will be null for versions before 5.5.
     */
    @Nullable
    private final Version jobVersion;

    private final String description;
    // TODO: Use java.time for the Dates here: x-pack-elasticsearch#829
    private final Date createTime;
    private final Date finishedTime;
    private final Date lastDataTime;
    private final AnalysisConfig analysisConfig;
    private final AnalysisLimits analysisLimits;
    private final DataDescription dataDescription;
    private final ModelPlotConfig modelPlotConfig;
    private final Long renormalizationWindowDays;
    private final TimeValue backgroundPersistInterval;
    private final Long modelSnapshotRetentionDays;
    private final Long resultsRetentionDays;
    private final Map<String, Object> customSettings;
    private final String modelSnapshotId;
    private final String resultsIndexName;
    private final boolean deleted;

    private Job(String jobId, String jobType, Version jobVersion, String description, Date createTime,
            Date finishedTime, Date lastDataTime,
               AnalysisConfig analysisConfig, AnalysisLimits analysisLimits, DataDescription dataDescription,
               ModelPlotConfig modelPlotConfig, Long renormalizationWindowDays, TimeValue backgroundPersistInterval,
               Long modelSnapshotRetentionDays, Long resultsRetentionDays, Map<String, Object> customSettings,
               String modelSnapshotId, String resultsIndexName, boolean deleted) {

        this.jobId = jobId;
        this.jobType = jobType;
        this.jobVersion = jobVersion;
        this.description = description;
        this.createTime = createTime;
        this.finishedTime = finishedTime;
        this.lastDataTime = lastDataTime;
        this.analysisConfig = analysisConfig;
        this.analysisLimits = analysisLimits;
        this.dataDescription = dataDescription;
        this.modelPlotConfig = modelPlotConfig;
        this.renormalizationWindowDays = renormalizationWindowDays;
        this.backgroundPersistInterval = backgroundPersistInterval;
        this.modelSnapshotRetentionDays = modelSnapshotRetentionDays;
        this.resultsRetentionDays = resultsRetentionDays;
        this.customSettings = customSettings;
        this.modelSnapshotId = modelSnapshotId;
        this.resultsIndexName = resultsIndexName;
        this.deleted = deleted;
    }

    public Job(StreamInput in) throws IOException {
        jobId = in.readString();
        jobType = in.readString();
        if (in.getVersion().onOrAfter(Version.V_5_5_0)) {
            jobVersion = in.readBoolean() ? Version.readVersion(in) : null;
        } else {
            jobVersion = null;
        }
        description = in.readOptionalString();
        createTime = new Date(in.readVLong());
        finishedTime = in.readBoolean() ? new Date(in.readVLong()) : null;
        lastDataTime = in.readBoolean() ? new Date(in.readVLong()) : null;
        analysisConfig = new AnalysisConfig(in);
        analysisLimits = in.readOptionalWriteable(AnalysisLimits::new);
        dataDescription = in.readOptionalWriteable(DataDescription::new);
        modelPlotConfig = in.readOptionalWriteable(ModelPlotConfig::new);
        renormalizationWindowDays = in.readOptionalLong();
        backgroundPersistInterval = in.readOptionalWriteable(TimeValue::new);
        modelSnapshotRetentionDays = in.readOptionalLong();
        resultsRetentionDays = in.readOptionalLong();
        customSettings = in.readMap();
        modelSnapshotId = in.readOptionalString();
        resultsIndexName = in.readString();
        deleted = in.readBoolean();
    }

    /**
     * Return the Job Id.
     *
     * @return The job Id string
     */
    public String getId() {
        return jobId;
    }

    public String getJobType() {
        return jobType;
    }

    public Version getJobVersion() {
        return jobVersion;
    }

    /**
     * The name of the index storing the job's results and state.
     * This defaults to {@link #getId()} if a specific index name is not set.
     * @return The job's index name
     */
    public String getResultsIndexName() {
        return AnomalyDetectorsIndex.RESULTS_INDEX_PREFIX + resultsIndexName;
    }

    /**
     * Private version of getResultsIndexName so that a job can be built from another
     * job and pass index name validation
     * @return The job's index name, minus prefix
     */
    private String getResultsIndexNameNoPrefix() {
        return resultsIndexName;
    }

    /**
     * The job description
     *
     * @return job description
     */
    public String getDescription() {
        return description;
    }

    /**
     * The Job creation time. This name is preferred when serialising to the
     * REST API.
     *
     * @return The date the job was created
     */
    public Date getCreateTime() {
        return createTime;
    }

    /**
     * The Job creation time. This name is preferred when serialising to the
     * data store.
     *
     * @return The date the job was created
     */
    public Date getAtTimestamp() {
        return createTime;
    }

    /**
     * The time the job was finished or <code>null</code> if not finished.
     *
     * @return The date the job was last retired or <code>null</code>
     */
    public Date getFinishedTime() {
        return finishedTime;
    }

    /**
     * The last time data was uploaded to the job or <code>null</code> if no
     * data has been seen.
     *
     * @return The date at which the last data was processed
     */
    public Date getLastDataTime() {
        return lastDataTime;
    }

    /**
     * The analysis configuration object
     *
     * @return The AnalysisConfig
     */
    public AnalysisConfig getAnalysisConfig() {
        return analysisConfig;
    }

    /**
     * The analysis options object
     *
     * @return The AnalysisLimits
     */
    public AnalysisLimits getAnalysisLimits() {
        return analysisLimits;
    }

    public ModelPlotConfig getModelPlotConfig() {
        return modelPlotConfig;
    }

    /**
     * If not set the input data is assumed to be csv with a '_time' field in
     * epoch format.
     *
     * @return A DataDescription or <code>null</code>
     * @see DataDescription
     */
    public DataDescription getDataDescription() {
        return dataDescription;
    }

    /**
     * The duration of the renormalization window in days
     *
     * @return renormalization window in days
     */
    public Long getRenormalizationWindowDays() {
        return renormalizationWindowDays;
    }

    /**
     * The background persistence interval
     *
     * @return background persistence interval
     */
    public TimeValue getBackgroundPersistInterval() {
        return backgroundPersistInterval;
    }

    public Long getModelSnapshotRetentionDays() {
        return modelSnapshotRetentionDays;
    }

    public Long getResultsRetentionDays() {
        return resultsRetentionDays;
    }

    public Map<String, Object> getCustomSettings() {
        return customSettings;
    }

    public String getModelSnapshotId() {
        return modelSnapshotId;
    }

    public boolean isDeleted() {
        return deleted;
    }

    /**
     * Get a list of all input data fields mentioned in the job configuration,
     * namely analysis fields and the time field.
     *
     * @return the list of fields - never <code>null</code>
     */
    public List<String> allFields() {
        Set<String> allFields = new TreeSet<>();

        // analysis fields
        if (analysisConfig != null) {
            allFields.addAll(analysisConfig.analysisFields());
        }

        // time field
        if (dataDescription != null) {
            String timeField = dataDescription.getTimeField();
            if (timeField != null) {
                allFields.add(timeField);
            }
        }

        // remove empty strings
        allFields.remove("");

        return new ArrayList<>(allFields);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(jobId);
        out.writeString(jobType);
        if (out.getVersion().onOrAfter(Version.V_5_5_0)) {
            if (jobVersion != null) {
                out.writeBoolean(true);
                Version.writeVersion(jobVersion, out);
            } else {
                out.writeBoolean(false);
            }
        }
        out.writeOptionalString(description);
        out.writeVLong(createTime.getTime());
        if (finishedTime != null) {
            out.writeBoolean(true);
            out.writeVLong(finishedTime.getTime());
        } else {
            out.writeBoolean(false);
        }
        if (lastDataTime != null) {
            out.writeBoolean(true);
            out.writeVLong(lastDataTime.getTime());
        } else {
            out.writeBoolean(false);
        }
        analysisConfig.writeTo(out);
        out.writeOptionalWriteable(analysisLimits);
        out.writeOptionalWriteable(dataDescription);
        out.writeOptionalWriteable(modelPlotConfig);
        out.writeOptionalLong(renormalizationWindowDays);
        out.writeOptionalWriteable(backgroundPersistInterval);
        out.writeOptionalLong(modelSnapshotRetentionDays);
        out.writeOptionalLong(resultsRetentionDays);
        out.writeMap(customSettings);
        out.writeOptionalString(modelSnapshotId);
        out.writeString(resultsIndexName);
        out.writeBoolean(deleted);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        doXContentBody(builder, params);
        builder.endObject();
        return builder;
    }

    public XContentBuilder doXContentBody(XContentBuilder builder, Params params) throws IOException {
        final String humanReadableSuffix = "_string";

        builder.field(ID.getPreferredName(), jobId);
        builder.field(JOB_TYPE.getPreferredName(), jobType);
        if (jobVersion != null) {
            builder.field(JOB_VERSION.getPreferredName(), jobVersion);
        }
        if (description != null) {
            builder.field(DESCRIPTION.getPreferredName(), description);
        }
        builder.dateField(CREATE_TIME.getPreferredName(), CREATE_TIME.getPreferredName() + humanReadableSuffix, createTime.getTime());
        if (finishedTime != null) {
            builder.dateField(FINISHED_TIME.getPreferredName(), FINISHED_TIME.getPreferredName() + humanReadableSuffix,
                    finishedTime.getTime());
        }
        if (lastDataTime != null) {
            builder.dateField(LAST_DATA_TIME.getPreferredName(), LAST_DATA_TIME.getPreferredName() + humanReadableSuffix,
                    lastDataTime.getTime());
        }
        builder.field(ANALYSIS_CONFIG.getPreferredName(), analysisConfig, params);
        if (analysisLimits != null) {
            builder.field(ANALYSIS_LIMITS.getPreferredName(), analysisLimits, params);
        }
        if (dataDescription != null) {
            builder.field(DATA_DESCRIPTION.getPreferredName(), dataDescription, params);
        }
        if (modelPlotConfig != null) {
            builder.field(MODEL_PLOT_CONFIG.getPreferredName(), modelPlotConfig, params);
        }
        if (renormalizationWindowDays != null) {
            builder.field(RENORMALIZATION_WINDOW_DAYS.getPreferredName(), renormalizationWindowDays);
        }
        if (backgroundPersistInterval != null) {
            builder.field(BACKGROUND_PERSIST_INTERVAL.getPreferredName(), backgroundPersistInterval.getStringRep());
        }
        if (modelSnapshotRetentionDays != null) {
            builder.field(MODEL_SNAPSHOT_RETENTION_DAYS.getPreferredName(), modelSnapshotRetentionDays);
        }
        if (resultsRetentionDays != null) {
            builder.field(RESULTS_RETENTION_DAYS.getPreferredName(), resultsRetentionDays);
        }
        if (customSettings != null) {
            builder.field(CUSTOM_SETTINGS.getPreferredName(), customSettings);
        }
        if (modelSnapshotId != null) {
            builder.field(MODEL_SNAPSHOT_ID.getPreferredName(), modelSnapshotId);
        }
        builder.field(RESULTS_INDEX_NAME.getPreferredName(), resultsIndexName);
        if (params.paramAsBoolean("all", false)) {
            builder.field(DELETED.getPreferredName(), deleted);
        }
        return builder;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (other instanceof Job == false) {
            return false;
        }

        Job that = (Job) other;
        return Objects.equals(this.jobId, that.jobId)
                && Objects.equals(this.jobType, that.jobType)
                && Objects.equals(this.jobVersion, that.jobVersion)
                && Objects.equals(this.description, that.description)
                && Objects.equals(this.createTime, that.createTime)
                && Objects.equals(this.finishedTime, that.finishedTime)
                && Objects.equals(this.lastDataTime, that.lastDataTime)
                && Objects.equals(this.analysisConfig, that.analysisConfig)
                && Objects.equals(this.analysisLimits, that.analysisLimits) && Objects.equals(this.dataDescription, that.dataDescription)
                && Objects.equals(this.modelPlotConfig, that.modelPlotConfig)
                && Objects.equals(this.renormalizationWindowDays, that.renormalizationWindowDays)
                && Objects.equals(this.backgroundPersistInterval, that.backgroundPersistInterval)
                && Objects.equals(this.modelSnapshotRetentionDays, that.modelSnapshotRetentionDays)
                && Objects.equals(this.resultsRetentionDays, that.resultsRetentionDays)
                && Objects.equals(this.customSettings, that.customSettings)
                && Objects.equals(this.modelSnapshotId, that.modelSnapshotId)
                && Objects.equals(this.resultsIndexName, that.resultsIndexName)
                && Objects.equals(this.deleted, that.deleted);
    }

    @Override
    public int hashCode() {
        return Objects.hash(jobId, jobType, jobVersion, description, createTime, finishedTime, lastDataTime, analysisConfig,
                analysisLimits, dataDescription, modelPlotConfig, renormalizationWindowDays,
                backgroundPersistInterval, modelSnapshotRetentionDays, resultsRetentionDays, customSettings,
                modelSnapshotId, resultsIndexName, deleted);
    }

    // Class already extends from AbstractDiffable, so copied from ToXContentToBytes#toString()
    @Override
    public final String toString() {
        return Strings.toString(this);
    }

    private static void checkValueNotLessThan(long minVal, String name, Long value) {
        if (value != null && value < minVal) {
            throw new IllegalArgumentException(Messages.getMessage(Messages.JOB_CONFIG_FIELD_VALUE_TOO_LOW, name, minVal, value));
        }
    }

    /**
     * Returns the job types that are compatible with a node running on {@code nodeVersion}
     * @param nodeVersion the version of the node
     * @return the compatible job types
     */
    public static Set<String> getCompatibleJobTypes(Version nodeVersion) {
        Set<String> compatibleTypes = new HashSet<>();
        if (nodeVersion.onOrAfter(Version.V_5_4_0)) {
            compatibleTypes.add(ANOMALY_DETECTOR_JOB_TYPE);
        }
        return compatibleTypes;
    }

    public static class Builder implements Writeable, ToXContent  {

        private String id;
        private String jobType = ANOMALY_DETECTOR_JOB_TYPE;
        private Version jobVersion;
        private String description;
        private AnalysisConfig analysisConfig;
        private AnalysisLimits analysisLimits;
        private DataDescription dataDescription;
        private Date createTime;
        private Date finishedTime;
        private Date lastDataTime;
        private ModelPlotConfig modelPlotConfig;
        private Long renormalizationWindowDays;
        private TimeValue backgroundPersistInterval;
        private Long modelSnapshotRetentionDays = 1L;
        private Long resultsRetentionDays;
        private Map<String, Object> customSettings;
        private String modelSnapshotId;
        private String resultsIndexName;
        private boolean deleted;

        public Builder() {
        }

        public Builder(String id) {
            this.id = id;
        }

        public Builder(Job job) {
            this.id = job.getId();
            this.jobType = job.getJobType();
            this.jobVersion = job.getJobVersion();
            this.description = job.getDescription();
            this.analysisConfig = job.getAnalysisConfig();
            this.analysisLimits = job.getAnalysisLimits();
            this.dataDescription = job.getDataDescription();
            this.createTime = job.getCreateTime();
            this.finishedTime = job.getFinishedTime();
            this.lastDataTime = job.getLastDataTime();
            this.modelPlotConfig = job.getModelPlotConfig();
            this.renormalizationWindowDays = job.getRenormalizationWindowDays();
            this.backgroundPersistInterval = job.getBackgroundPersistInterval();
            this.modelSnapshotRetentionDays = job.getModelSnapshotRetentionDays();
            this.resultsRetentionDays = job.getResultsRetentionDays();
            this.customSettings = job.getCustomSettings();
            this.modelSnapshotId = job.getModelSnapshotId();
            this.resultsIndexName = job.getResultsIndexNameNoPrefix();
            this.deleted = job.isDeleted();
        }

        public Builder(StreamInput in) throws IOException {
            id = in.readOptionalString();
            jobType = in.readString();
            if (in.getVersion().onOrAfter(Version.V_5_5_0)) {
                jobVersion = in.readBoolean() ? Version.readVersion(in) : null;
            }
            description = in.readOptionalString();
            createTime = in.readBoolean() ? new Date(in.readVLong()) : null;
            finishedTime = in.readBoolean() ? new Date(in.readVLong()) : null;
            lastDataTime = in.readBoolean() ? new Date(in.readVLong()) : null;
            analysisConfig = in.readOptionalWriteable(AnalysisConfig::new);
            analysisLimits = in.readOptionalWriteable(AnalysisLimits::new);
            dataDescription = in.readOptionalWriteable(DataDescription::new);
            modelPlotConfig = in.readOptionalWriteable(ModelPlotConfig::new);
            renormalizationWindowDays = in.readOptionalLong();
            backgroundPersistInterval = in.readOptionalWriteable(TimeValue::new);
            modelSnapshotRetentionDays = in.readOptionalLong();
            resultsRetentionDays = in.readOptionalLong();
            customSettings = in.readMap();
            modelSnapshotId = in.readOptionalString();
            resultsIndexName = in.readOptionalString();
            deleted = in.readBoolean();
        }

        public Builder setId(String id) {
            this.id = id;
            return this;
        }

        public String getId() {
            return id;
        }

        public void setJobVersion(Version jobVersion) {
            this.jobVersion = jobVersion;
        }

        private void setJobVersion(String jobVersion) {
            this.jobVersion = Version.fromString(jobVersion);
        }

        private void setJobType(String jobType) {
            this.jobType = jobType;
        }

        public Date getCreateTime() {
            return createTime;
        }

        public Builder setCustomSettings(Map<String, Object> customSettings) {
            this.customSettings = customSettings;
            return this;
        }

        public Builder setDescription(String description) {
            this.description = description;
            return this;
        }

        public Builder setAnalysisConfig(AnalysisConfig.Builder configBuilder) {
            analysisConfig = ExceptionsHelper.requireNonNull(configBuilder, ANALYSIS_CONFIG.getPreferredName()).build();
            return this;
        }

        public Builder setAnalysisLimits(AnalysisLimits analysisLimits) {
            if (this.analysisLimits != null) {
                long oldMemoryLimit = this.analysisLimits.getModelMemoryLimit();
                long newMemoryLimit = analysisLimits.getModelMemoryLimit();
                if (newMemoryLimit < oldMemoryLimit) {
                    throw new IllegalArgumentException(
                            Messages.getMessage(Messages.JOB_CONFIG_UPDATE_ANALYSIS_LIMITS_MODEL_MEMORY_LIMIT_CANNOT_BE_DECREASED,
                                    oldMemoryLimit, newMemoryLimit));
                }
            }
            this.analysisLimits = analysisLimits;
            return this;
        }

        Builder setCreateTime(Date createTime) {
            this.createTime = createTime;
            return this;
        }

        public Builder setFinishedTime(Date finishedTime) {
            this.finishedTime = finishedTime;
            return this;
        }

        /**
         * Set the wall clock time of the last data upload
         * @param lastDataTime Wall clock time
         */
        public Builder setLastDataTime(Date lastDataTime) {
            this.lastDataTime = lastDataTime;
            return this;
        }

        public Builder setDataDescription(DataDescription.Builder description) {
            dataDescription = ExceptionsHelper.requireNonNull(description, DATA_DESCRIPTION.getPreferredName()).build();
            return this;
        }

        public Builder setModelPlotConfig(ModelPlotConfig modelPlotConfig) {
            this.modelPlotConfig = modelPlotConfig;
            return this;
        }

        public Builder setBackgroundPersistInterval(TimeValue backgroundPersistInterval) {
            this.backgroundPersistInterval = backgroundPersistInterval;
            return this;
        }

        public Builder setRenormalizationWindowDays(Long renormalizationWindowDays) {
            this.renormalizationWindowDays = renormalizationWindowDays;
            return this;
        }

        public Builder setModelSnapshotRetentionDays(Long modelSnapshotRetentionDays) {
            this.modelSnapshotRetentionDays = modelSnapshotRetentionDays;
            return this;
        }

        public Builder setResultsRetentionDays(Long resultsRetentionDays) {
            this.resultsRetentionDays = resultsRetentionDays;
            return this;
        }

        public Builder setModelSnapshotId(String modelSnapshotId) {
            this.modelSnapshotId = modelSnapshotId;
            return this;
        }

        public Builder setResultsIndexName(String resultsIndexName) {
            this.resultsIndexName = resultsIndexName;
            return this;
        }

        public Builder setDeleted(boolean deleted) {
            this.deleted = deleted;
            return this;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeOptionalString(id);
            out.writeString(jobType);
            if (out.getVersion().onOrAfter(Version.V_5_5_0)) {
                if (jobVersion != null) {
                    out.writeBoolean(true);
                    Version.writeVersion(jobVersion, out);
                } else {
                    out.writeBoolean(false);
                }
            }
            out.writeOptionalString(description);
            if (createTime != null) {
                out.writeBoolean(true);
                out.writeVLong(createTime.getTime());
            } else {
                out.writeBoolean(false);
            }
            if (finishedTime != null) {
                out.writeBoolean(true);
                out.writeVLong(finishedTime.getTime());
            } else {
                out.writeBoolean(false);
            }
            if (lastDataTime != null) {
                out.writeBoolean(true);
                out.writeVLong(lastDataTime.getTime());
            } else {
                out.writeBoolean(false);
            }
            out.writeOptionalWriteable(analysisConfig);
            out.writeOptionalWriteable(analysisLimits);
            out.writeOptionalWriteable(dataDescription);
            out.writeOptionalWriteable(modelPlotConfig);
            out.writeOptionalLong(renormalizationWindowDays);
            out.writeOptionalWriteable(backgroundPersistInterval);
            out.writeOptionalLong(modelSnapshotRetentionDays);
            out.writeOptionalLong(resultsRetentionDays);
            out.writeMap(customSettings);
            out.writeOptionalString(modelSnapshotId);
            out.writeOptionalString(resultsIndexName);
            out.writeBoolean(deleted);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            if (id != null) {
                builder.field(ID.getPreferredName(), id);
            }
            builder.field(JOB_TYPE.getPreferredName(), jobType);
            if (jobVersion != null) {
                builder.field(JOB_VERSION.getPreferredName(), jobVersion);
            }
            if (description != null) {
                builder.field(DESCRIPTION.getPreferredName(), description);
            }
            if (createTime != null) {
                builder.field(CREATE_TIME.getPreferredName(), createTime.getTime());
            }
            if (finishedTime != null) {
                builder.field(FINISHED_TIME.getPreferredName(), finishedTime.getTime());
            }
            if (lastDataTime != null) {
                builder.field(LAST_DATA_TIME.getPreferredName(), lastDataTime.getTime());
            }
            if (analysisConfig != null) {
                builder.field(ANALYSIS_CONFIG.getPreferredName(), analysisConfig, params);
            }
            if (analysisLimits != null) {
                builder.field(ANALYSIS_LIMITS.getPreferredName(), analysisLimits, params);
            }
            if (dataDescription != null) {
                builder.field(DATA_DESCRIPTION.getPreferredName(), dataDescription, params);
            }
            if (modelPlotConfig != null) {
                builder.field(MODEL_PLOT_CONFIG.getPreferredName(), modelPlotConfig, params);
            }
            if (renormalizationWindowDays != null) {
                builder.field(RENORMALIZATION_WINDOW_DAYS.getPreferredName(), renormalizationWindowDays);
            }
            if (backgroundPersistInterval != null) {
                builder.field(BACKGROUND_PERSIST_INTERVAL.getPreferredName(), backgroundPersistInterval.getStringRep());
            }
            if (modelSnapshotRetentionDays != null) {
                builder.field(MODEL_SNAPSHOT_RETENTION_DAYS.getPreferredName(), modelSnapshotRetentionDays);
            }
            if (resultsRetentionDays != null) {
                builder.field(RESULTS_RETENTION_DAYS.getPreferredName(), resultsRetentionDays);
            }
            if (customSettings != null) {
                builder.field(CUSTOM_SETTINGS.getPreferredName(), customSettings);
            }
            if (modelSnapshotId != null) {
                builder.field(MODEL_SNAPSHOT_ID.getPreferredName(), modelSnapshotId);
            }
            if (resultsIndexName != null) {
                builder.field(RESULTS_INDEX_NAME.getPreferredName(), resultsIndexName);
            }
            if (params.paramAsBoolean("all", false)) {
                builder.field(DELETED.getPreferredName(), deleted);
            }

            builder.endObject();
            return builder;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Job.Builder that = (Job.Builder) o;
            return Objects.equals(this.id, that.id)
                    && Objects.equals(this.jobType, that.jobType)
                    && Objects.equals(this.jobVersion, that.jobVersion)
                    && Objects.equals(this.description, that.description)
                    && Objects.equals(this.analysisConfig, that.analysisConfig)
                    && Objects.equals(this.analysisLimits, that.analysisLimits)
                    && Objects.equals(this.dataDescription, that.dataDescription)
                    && Objects.equals(this.createTime, that.createTime)
                    && Objects.equals(this.finishedTime, that.finishedTime)
                    && Objects.equals(this.lastDataTime, that.lastDataTime)
                    && Objects.equals(this.modelPlotConfig, that.modelPlotConfig)
                    && Objects.equals(this.renormalizationWindowDays, that.renormalizationWindowDays)
                    && Objects.equals(this.backgroundPersistInterval, that.backgroundPersistInterval)
                    && Objects.equals(this.modelSnapshotRetentionDays, that.modelSnapshotRetentionDays)
                    && Objects.equals(this.resultsRetentionDays, that.resultsRetentionDays)
                    && Objects.equals(this.customSettings, that.customSettings)
                    && Objects.equals(this.modelSnapshotId, that.modelSnapshotId)
                    && Objects.equals(this.resultsIndexName, that.resultsIndexName)
                    && Objects.equals(this.deleted, that.deleted);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, jobType, jobVersion, description, analysisConfig, analysisLimits, dataDescription, createTime,
                    finishedTime, lastDataTime, modelPlotConfig, renormalizationWindowDays, backgroundPersistInterval,
                    modelSnapshotRetentionDays, resultsRetentionDays, customSettings, modelSnapshotId, resultsIndexName, deleted);
        }

        /**
         * Call this method to validate that the job JSON provided by a user is valid.
         * Throws an exception if there are any problems; normal return implies valid.
         */
        public void validateInputFields() {

            if (analysisConfig == null) {
                throw new IllegalArgumentException(Messages.getMessage(Messages.JOB_CONFIG_MISSING_ANALYSISCONFIG));
            }

            if (dataDescription == null) {
                throw new IllegalArgumentException(Messages.getMessage(Messages.JOB_CONFIG_MISSING_DATA_DESCRIPTION));
            }

            checkValidBackgroundPersistInterval();
            checkValueNotLessThan(0, RENORMALIZATION_WINDOW_DAYS.getPreferredName(), renormalizationWindowDays);
            checkValueNotLessThan(0, MODEL_SNAPSHOT_RETENTION_DAYS.getPreferredName(), modelSnapshotRetentionDays);
            checkValueNotLessThan(0, RESULTS_RETENTION_DAYS.getPreferredName(), resultsRetentionDays);

            if (!MlStrings.isValidId(id)) {
                throw new IllegalArgumentException(Messages.getMessage(Messages.INVALID_ID, ID.getPreferredName(), id));
            }
            if (id.length() > MAX_JOB_ID_LENGTH) {
                throw new IllegalArgumentException(Messages.getMessage(Messages.JOB_CONFIG_ID_TOO_LONG, MAX_JOB_ID_LENGTH));
            }

            // Results index name not specified in user input means use the default, so is acceptable in this validation
            if (!Strings.isNullOrEmpty(resultsIndexName) && !MlStrings.isValidId(resultsIndexName)) {
                throw new IllegalArgumentException(
                        Messages.getMessage(Messages.INVALID_ID, RESULTS_INDEX_NAME.getPreferredName(), resultsIndexName));
            }

            // Creation time is NOT required in user input, hence validated only on build
        }

        /**
         * Builds a job with the given {@code createTime} and the current version.
         * This should be used when a new job is created as opposed to {@link #build()}.
         *
         * @param createTime The time this job was created
         * @return The job
         */
        public Job build(Date createTime) {
            setCreateTime(createTime);
            setJobVersion(Version.CURRENT);
            return build();
        }

        /**
         * Builds a job.
         * This should be used when an existing job is being built
         * as opposed to {@link #build(Date)}.
         *
         * @return The job
         */
        public Job build() {

            validateInputFields();

            // Creation time is NOT required in user input, hence validated only on build
            Date createTime = ExceptionsHelper.requireNonNull(this.createTime, CREATE_TIME.getPreferredName());

            if (Strings.isNullOrEmpty(resultsIndexName)) {
                resultsIndexName = AnomalyDetectorsIndex.RESULTS_INDEX_DEFAULT;
            } else if (!resultsIndexName.equals(AnomalyDetectorsIndex.RESULTS_INDEX_DEFAULT)) {
                // User-defined names are prepended with "custom"
                // Conditional guards against multiple prepending due to updates instead of first creation
                resultsIndexName = resultsIndexName.startsWith("custom-")
                        ? resultsIndexName
                        : "custom-" + resultsIndexName;
            }

            return new Job(
                    id, jobType, jobVersion, description, createTime, finishedTime, lastDataTime,
                    analysisConfig, analysisLimits,
                    dataDescription, modelPlotConfig, renormalizationWindowDays, backgroundPersistInterval,
                    modelSnapshotRetentionDays, resultsRetentionDays, customSettings, modelSnapshotId,
                    resultsIndexName, deleted);
        }

        private void checkValidBackgroundPersistInterval() {
            if (backgroundPersistInterval != null) {
                TimeUtils.checkMultiple(backgroundPersistInterval, TimeUnit.SECONDS, BACKGROUND_PERSIST_INTERVAL);
                checkValueNotLessThan(MIN_BACKGROUND_PERSIST_INTERVAL.getSeconds(), BACKGROUND_PERSIST_INTERVAL.getPreferredName(),
                        backgroundPersistInterval.getSeconds());
            }
        }
    }
}
