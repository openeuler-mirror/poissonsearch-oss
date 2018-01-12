/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.job.config;

import org.elasticsearch.Version;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.xpack.ml.job.messages.Messages;
import org.elasticsearch.xpack.ml.utils.ExceptionsHelper;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class JobUpdate implements Writeable, ToXContentObject {
    public static final ParseField DETECTORS = new ParseField("detectors");

    public static final ConstructingObjectParser<Builder, Void> PARSER = new ConstructingObjectParser<>(
            "job_update", args -> new Builder((String) args[0]));

    static {
        PARSER.declareString(ConstructingObjectParser.optionalConstructorArg(), Job.ID);
        PARSER.declareStringArray(Builder::setGroups, Job.GROUPS);
        PARSER.declareStringOrNull(Builder::setDescription, Job.DESCRIPTION);
        PARSER.declareObjectArray(Builder::setDetectorUpdates, DetectorUpdate.PARSER, DETECTORS);
        PARSER.declareObject(Builder::setModelPlotConfig, ModelPlotConfig.CONFIG_PARSER, Job.MODEL_PLOT_CONFIG);
        PARSER.declareObject(Builder::setAnalysisLimits, AnalysisLimits.CONFIG_PARSER, Job.ANALYSIS_LIMITS);
        PARSER.declareString((builder, val) -> builder.setBackgroundPersistInterval(
                TimeValue.parseTimeValue(val, Job.BACKGROUND_PERSIST_INTERVAL.getPreferredName())), Job.BACKGROUND_PERSIST_INTERVAL);
        PARSER.declareLong(Builder::setRenormalizationWindowDays, Job.RENORMALIZATION_WINDOW_DAYS);
        PARSER.declareLong(Builder::setResultsRetentionDays, Job.RESULTS_RETENTION_DAYS);
        PARSER.declareLong(Builder::setModelSnapshotRetentionDays, Job.MODEL_SNAPSHOT_RETENTION_DAYS);
        PARSER.declareStringArray(Builder::setCategorizationFilters, AnalysisConfig.CATEGORIZATION_FILTERS);
        PARSER.declareField(Builder::setCustomSettings, (p, c) -> p.map(), Job.CUSTOM_SETTINGS, ObjectParser.ValueType.OBJECT);
        PARSER.declareString(Builder::setModelSnapshotId, Job.MODEL_SNAPSHOT_ID);
        PARSER.declareLong(Builder::setEstablishedModelMemory, Job.ESTABLISHED_MODEL_MEMORY);
    }

    /**
     * Prior to 6.1 a default model_memory_limit was not enforced in Java.
     * The default of 4GB was used in the C++ code.
     * If model_memory_limit is not defined for a job then the
     * job was created before 6.1 and a value of 4GB is assumed.
     */
    static final long UNDEFINED_MODEL_MEMORY_LIMIT_DEFAULT = 4096;

    private final String jobId;
    private final List<String> groups;
    private final String description;
    private final List<DetectorUpdate> detectorUpdates;
    private final ModelPlotConfig modelPlotConfig;
    private final AnalysisLimits analysisLimits;
    private final Long renormalizationWindowDays;
    private final TimeValue backgroundPersistInterval;
    private final Long modelSnapshotRetentionDays;
    private final Long resultsRetentionDays;
    private final List<String> categorizationFilters;
    private final Map<String, Object> customSettings;
    private final String modelSnapshotId;
    private final Long establishedModelMemory;

    private JobUpdate(String jobId, @Nullable List<String> groups, @Nullable String description,
                      @Nullable List<DetectorUpdate> detectorUpdates, @Nullable ModelPlotConfig modelPlotConfig,
                      @Nullable AnalysisLimits analysisLimits, @Nullable TimeValue backgroundPersistInterval,
                      @Nullable Long renormalizationWindowDays, @Nullable Long resultsRetentionDays,
                      @Nullable Long modelSnapshotRetentionDays, @Nullable List<String> categorisationFilters,
                      @Nullable Map<String, Object> customSettings, @Nullable String modelSnapshotId,
                      @Nullable Long establishedModelMemory) {
        this.jobId = jobId;
        this.groups = groups;
        this.description = description;
        this.detectorUpdates = detectorUpdates;
        this.modelPlotConfig = modelPlotConfig;
        this.analysisLimits = analysisLimits;
        this.renormalizationWindowDays = renormalizationWindowDays;
        this.backgroundPersistInterval = backgroundPersistInterval;
        this.modelSnapshotRetentionDays = modelSnapshotRetentionDays;
        this.resultsRetentionDays = resultsRetentionDays;
        this.categorizationFilters = categorisationFilters;
        this.customSettings = customSettings;
        this.modelSnapshotId = modelSnapshotId;
        this.establishedModelMemory = establishedModelMemory;
    }

    public JobUpdate(StreamInput in) throws IOException {
        jobId = in.readString();
        if (in.getVersion().onOrAfter(Version.V_6_1_0)) {
            String[] groupsArray = in.readOptionalStringArray();
            groups = groupsArray == null ? null : Arrays.asList(groupsArray);
        } else {
            groups = null;
        }
        description = in.readOptionalString();
        if (in.readBoolean()) {
            detectorUpdates = in.readList(DetectorUpdate::new);
        } else {
            detectorUpdates = null;
        }
        modelPlotConfig = in.readOptionalWriteable(ModelPlotConfig::new);
        analysisLimits = in.readOptionalWriteable(AnalysisLimits::new);
        renormalizationWindowDays = in.readOptionalLong();
        backgroundPersistInterval = in.readOptionalWriteable(TimeValue::new);
        modelSnapshotRetentionDays = in.readOptionalLong();
        resultsRetentionDays = in.readOptionalLong();
        if (in.readBoolean()) {
            categorizationFilters = in.readList(StreamInput::readString);
        } else {
            categorizationFilters = null;
        }
        customSettings = in.readMap();
        modelSnapshotId = in.readOptionalString();
        if (in.getVersion().onOrAfter(Version.V_6_1_0)) {
            establishedModelMemory = in.readOptionalLong();
        } else {
            establishedModelMemory = null;
        }
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(jobId);
        if (out.getVersion().onOrAfter(Version.V_6_1_0)) {
            String[] groupsArray = groups == null ? null : groups.toArray(new String[groups.size()]);
            out.writeOptionalStringArray(groupsArray);
        }
        out.writeOptionalString(description);
        out.writeBoolean(detectorUpdates != null);
        if (detectorUpdates != null) {
            out.writeList(detectorUpdates);
        }
        out.writeOptionalWriteable(modelPlotConfig);
        out.writeOptionalWriteable(analysisLimits);
        out.writeOptionalLong(renormalizationWindowDays);
        out.writeOptionalWriteable(backgroundPersistInterval);
        out.writeOptionalLong(modelSnapshotRetentionDays);
        out.writeOptionalLong(resultsRetentionDays);
        out.writeBoolean(categorizationFilters != null);
        if (categorizationFilters != null) {
            out.writeStringList(categorizationFilters);
        }
        out.writeMap(customSettings);
        out.writeOptionalString(modelSnapshotId);
        if (out.getVersion().onOrAfter(Version.V_6_1_0)) {
            out.writeOptionalLong(establishedModelMemory);
        }
    }

    public String getJobId() {
        return jobId;
    }

    public List<String> getGroups() {
        return groups;
    }

    public String getDescription() {
        return description;
    }

    public List<DetectorUpdate> getDetectorUpdates() {
        return detectorUpdates;
    }

    public ModelPlotConfig getModelPlotConfig() {
        return modelPlotConfig;
    }

    public AnalysisLimits getAnalysisLimits() {
        return analysisLimits;
    }

    public Long getRenormalizationWindowDays() {
        return renormalizationWindowDays;
    }

    public TimeValue getBackgroundPersistInterval() {
        return backgroundPersistInterval;
    }

    public Long getModelSnapshotRetentionDays() {
        return modelSnapshotRetentionDays;
    }

    public Long getResultsRetentionDays() {
        return resultsRetentionDays;
    }

    public List<String> getCategorizationFilters() {
        return categorizationFilters;
    }

    public Map<String, Object> getCustomSettings() {
        return customSettings;
    }

    public String getModelSnapshotId() {
        return modelSnapshotId;
    }

    public Long getEstablishedModelMemory() {
        return establishedModelMemory;
    }

    public boolean isAutodetectProcessUpdate() {
        return modelPlotConfig != null || detectorUpdates != null;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(Job.ID.getPreferredName(), jobId);
        if (groups != null) {
            builder.field(Job.GROUPS.getPreferredName(), groups);
        }
        if (description != null) {
            builder.field(Job.DESCRIPTION.getPreferredName(), description);
        }
        if (detectorUpdates != null) {
            builder.field(DETECTORS.getPreferredName(), detectorUpdates);
        }
        if (modelPlotConfig != null) {
            builder.field(Job.MODEL_PLOT_CONFIG.getPreferredName(), modelPlotConfig);
        }
        if (analysisLimits != null) {
            builder.field(Job.ANALYSIS_LIMITS.getPreferredName(), analysisLimits);
        }
        if (renormalizationWindowDays != null) {
            builder.field(Job.RENORMALIZATION_WINDOW_DAYS.getPreferredName(), renormalizationWindowDays);
        }
        if (backgroundPersistInterval != null) {
            builder.field(Job.BACKGROUND_PERSIST_INTERVAL.getPreferredName(), backgroundPersistInterval);
        }
        if (modelSnapshotRetentionDays != null) {
            builder.field(Job.MODEL_SNAPSHOT_RETENTION_DAYS.getPreferredName(), modelSnapshotRetentionDays);
        }
        if (resultsRetentionDays != null) {
            builder.field(Job.RESULTS_RETENTION_DAYS.getPreferredName(), resultsRetentionDays);
        }
        if (categorizationFilters != null) {
            builder.field(AnalysisConfig.CATEGORIZATION_FILTERS.getPreferredName(), categorizationFilters);
        }
        if (customSettings != null) {
            builder.field(Job.CUSTOM_SETTINGS.getPreferredName(), customSettings);
        }
        if (modelSnapshotId != null) {
            builder.field(Job.MODEL_SNAPSHOT_ID.getPreferredName(), modelSnapshotId);
        }
        if (establishedModelMemory != null) {
            builder.field(Job.ESTABLISHED_MODEL_MEMORY.getPreferredName(), establishedModelMemory);
        }
        builder.endObject();
        return builder;
    }

    /**
     * Updates {@code source} with the new values in this object returning a new {@link Job}.
     *
     * @param source Source job to be updated
     * @param maxModelMemoryLimit The maximum model memory allowed
     * @return A new job equivalent to {@code source} updated.
     */
    public Job mergeWithJob(Job source, ByteSizeValue maxModelMemoryLimit) {
        Job.Builder builder = new Job.Builder(source);
        if (groups != null) {
            builder.setGroups(groups);
        }
        if (description != null) {
            builder.setDescription(description);
        }
        if (detectorUpdates != null && detectorUpdates.isEmpty() == false) {
            AnalysisConfig ac = source.getAnalysisConfig();
            int numDetectors = ac.getDetectors().size();
            for (DetectorUpdate dd : detectorUpdates) {
                if (dd.getDetectorIndex() >= numDetectors) {
                    throw ExceptionsHelper.badRequestException("Supplied detector_index [{}] is >= the number of detectors [{}]",
                            dd.getDetectorIndex(), numDetectors);
                }

                Detector.Builder detectorbuilder = new Detector.Builder(ac.getDetectors().get(dd.getDetectorIndex()));
                if (dd.getDescription() != null) {
                    detectorbuilder.setDetectorDescription(dd.getDescription());
                }
                if (dd.getRules() != null) {
                    detectorbuilder.setRules(dd.getRules());
                }
                ac.getDetectors().set(dd.getDetectorIndex(), detectorbuilder.build());
            }

            AnalysisConfig.Builder acBuilder = new AnalysisConfig.Builder(ac);
            builder.setAnalysisConfig(acBuilder);
        }
        if (modelPlotConfig != null) {
            builder.setModelPlotConfig(modelPlotConfig);
        }
        if (analysisLimits != null) {
            Long oldMemoryLimit;
            if (source.getAnalysisLimits() != null) {
                oldMemoryLimit = source.getAnalysisLimits().getModelMemoryLimit() != null ?
                        source.getAnalysisLimits().getModelMemoryLimit()
                        : UNDEFINED_MODEL_MEMORY_LIMIT_DEFAULT;
            } else {
                oldMemoryLimit = UNDEFINED_MODEL_MEMORY_LIMIT_DEFAULT;
            }

            Long newMemoryLimit = analysisLimits.getModelMemoryLimit() != null ?
                    analysisLimits.getModelMemoryLimit()
                    : oldMemoryLimit;

            if (newMemoryLimit < oldMemoryLimit) {
                throw ExceptionsHelper.badRequestException(
                        Messages.getMessage(Messages.JOB_CONFIG_UPDATE_ANALYSIS_LIMITS_MODEL_MEMORY_LIMIT_CANNOT_BE_DECREASED,
                                new ByteSizeValue(oldMemoryLimit, ByteSizeUnit.MB),
                                new ByteSizeValue(newMemoryLimit, ByteSizeUnit.MB)));
            }

            boolean maxModelMemoryLimitIsSet = maxModelMemoryLimit != null && maxModelMemoryLimit.getMb() > 0;
            if (maxModelMemoryLimitIsSet) {
                Long modelMemoryLimit = analysisLimits.getModelMemoryLimit();
                if (modelMemoryLimit != null && modelMemoryLimit > maxModelMemoryLimit.getMb()) {
                    throw ExceptionsHelper.badRequestException(Messages.getMessage(Messages.JOB_CONFIG_MODEL_MEMORY_LIMIT_GREATER_THAN_MAX,
                            new ByteSizeValue(modelMemoryLimit, ByteSizeUnit.MB),
                            maxModelMemoryLimit));
                }
            }

            builder.setAnalysisLimits(analysisLimits);
        }
        if (renormalizationWindowDays != null) {
            builder.setRenormalizationWindowDays(renormalizationWindowDays);
        }
        if (backgroundPersistInterval != null) {
            builder.setBackgroundPersistInterval(backgroundPersistInterval);
        }
        if (modelSnapshotRetentionDays != null) {
            builder.setModelSnapshotRetentionDays(modelSnapshotRetentionDays);
        }
        if (resultsRetentionDays != null) {
            builder.setResultsRetentionDays(resultsRetentionDays);
        }
        if (categorizationFilters != null) {
            AnalysisConfig.Builder analysisConfigBuilder = new AnalysisConfig.Builder(source.getAnalysisConfig());
            analysisConfigBuilder.setCategorizationFilters(categorizationFilters);
            builder.setAnalysisConfig(analysisConfigBuilder);
        }
        if (customSettings != null) {
            builder.setCustomSettings(customSettings);
        }
        if (modelSnapshotId != null) {
            builder.setModelSnapshotId(modelSnapshotId);
        }
        if (establishedModelMemory != null) {
            // An established model memory of zero means we don't actually know the established model memory
            if (establishedModelMemory > 0) {
                builder.setEstablishedModelMemory(establishedModelMemory);
            } else {
                builder.setEstablishedModelMemory(null);
            }
        }
        return builder.build();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (other instanceof JobUpdate == false) {
            return false;
        }

        JobUpdate that = (JobUpdate) other;

        return Objects.equals(this.jobId, that.jobId)
                && Objects.equals(this.groups, that.groups)
                && Objects.equals(this.description, that.description)
                && Objects.equals(this.detectorUpdates, that.detectorUpdates)
                && Objects.equals(this.modelPlotConfig, that.modelPlotConfig)
                && Objects.equals(this.analysisLimits, that.analysisLimits)
                && Objects.equals(this.renormalizationWindowDays, that.renormalizationWindowDays)
                && Objects.equals(this.backgroundPersistInterval, that.backgroundPersistInterval)
                && Objects.equals(this.modelSnapshotRetentionDays, that.modelSnapshotRetentionDays)
                && Objects.equals(this.resultsRetentionDays, that.resultsRetentionDays)
                && Objects.equals(this.categorizationFilters, that.categorizationFilters)
                && Objects.equals(this.customSettings, that.customSettings)
                && Objects.equals(this.modelSnapshotId, that.modelSnapshotId)
                && Objects.equals(this.establishedModelMemory, that.establishedModelMemory);
    }

    @Override
    public int hashCode() {
        return Objects.hash(jobId, groups, description, detectorUpdates, modelPlotConfig, analysisLimits, renormalizationWindowDays,
                backgroundPersistInterval, modelSnapshotRetentionDays, resultsRetentionDays, categorizationFilters, customSettings,
                modelSnapshotId, establishedModelMemory);
    }

    public static class DetectorUpdate implements Writeable, ToXContentObject {
        @SuppressWarnings("unchecked")
        public static final ConstructingObjectParser<DetectorUpdate, Void> PARSER =
                new ConstructingObjectParser<>("detector_update", a -> new DetectorUpdate((int) a[0], (String) a[1],
                        (List<DetectionRule>) a[2]));

        static {
            PARSER.declareInt(ConstructingObjectParser.optionalConstructorArg(), Detector.DETECTOR_INDEX);
            PARSER.declareStringOrNull(ConstructingObjectParser.optionalConstructorArg(), Job.DESCRIPTION);
            PARSER.declareObjectArray(ConstructingObjectParser.optionalConstructorArg(), (parser, parseFieldMatcher) ->
                    DetectionRule.CONFIG_PARSER.apply(parser, parseFieldMatcher).build(), Detector.RULES_FIELD);
        }

        private int detectorIndex;
        private String description;
        private List<DetectionRule> rules;

        public DetectorUpdate(int detectorIndex, String description, List<DetectionRule> rules) {
            this.detectorIndex = detectorIndex;
            this.description = description;
            this.rules = rules;
        }

        public DetectorUpdate(StreamInput in) throws IOException {
            detectorIndex = in.readInt();
            description = in.readOptionalString();
            if (in.readBoolean()) {
                rules = in.readList(DetectionRule::new);
            } else {
                rules = null;
            }
        }

        public int getDetectorIndex() {
            return detectorIndex;
        }

        public String getDescription() {
            return description;
        }

        public List<DetectionRule> getRules() {
            return rules;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeInt(detectorIndex);
            out.writeOptionalString(description);
            out.writeBoolean(rules != null);
            if (rules != null) {
                out.writeList(rules);
            }
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();

            builder.field(Detector.DETECTOR_INDEX.getPreferredName(), detectorIndex);
            if (description != null) {
                builder.field(Job.DESCRIPTION.getPreferredName(), description);
            }
            if (rules != null) {
                builder.field(Detector.RULES_FIELD.getPreferredName(), rules);
            }
            builder.endObject();

            return builder;
        }

        @Override
        public int hashCode() {
            return Objects.hash(detectorIndex, description, rules);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (other instanceof DetectorUpdate == false) {
                return false;
            }

            DetectorUpdate that = (DetectorUpdate) other;
            return this.detectorIndex == that.detectorIndex && Objects.equals(this.description, that.description)
                    && Objects.equals(this.rules, that.rules);
        }
    }

    public static class Builder {

        private String jobId;
        private List<String> groups;
        private String description;
        private List<DetectorUpdate> detectorUpdates;
        private ModelPlotConfig modelPlotConfig;
        private AnalysisLimits analysisLimits;
        private Long renormalizationWindowDays;
        private TimeValue backgroundPersistInterval;
        private Long modelSnapshotRetentionDays;
        private Long resultsRetentionDays;
        private List<String> categorizationFilters;
        private Map<String, Object> customSettings;
        private String modelSnapshotId;
        private Long establishedModelMemory;

        public Builder(String jobId) {
            this.jobId = jobId;
        }

        public Builder setJobId(String jobId) {
            this.jobId = jobId;
            return this;
        }

        public Builder setGroups(List<String> groups) {
            this.groups = groups;
            return this;
        }

        public Builder setDescription(String description) {
            this.description = description;
            return this;
        }

        public Builder setDetectorUpdates(List<DetectorUpdate> detectorUpdates) {
            this.detectorUpdates = detectorUpdates;
            return this;
        }

        public Builder setModelPlotConfig(ModelPlotConfig modelPlotConfig) {
            this.modelPlotConfig = modelPlotConfig;
            return this;
        }

        public Builder setAnalysisLimits(AnalysisLimits analysisLimits) {
            this.analysisLimits = analysisLimits;
            return this;
        }

        public Builder setRenormalizationWindowDays(Long renormalizationWindowDays) {
            this.renormalizationWindowDays = renormalizationWindowDays;
            return this;
        }

        public Builder setBackgroundPersistInterval(TimeValue backgroundPersistInterval) {
            this.backgroundPersistInterval = backgroundPersistInterval;
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

        public Builder setCategorizationFilters(List<String> categorizationFilters) {
            this.categorizationFilters = categorizationFilters;
            return this;
        }

        public Builder setCustomSettings(Map<String, Object> customSettings) {
            this.customSettings = customSettings;
            return this;
        }

        public Builder setModelSnapshotId(String modelSnapshotId) {
            this.modelSnapshotId = modelSnapshotId;
            return this;
        }

        public Builder setEstablishedModelMemory(Long establishedModelMemory) {
            this.establishedModelMemory = establishedModelMemory;
            return this;
        }

        public JobUpdate build() {
            return new JobUpdate(jobId, groups, description, detectorUpdates, modelPlotConfig, analysisLimits, backgroundPersistInterval,
                    renormalizationWindowDays, resultsRetentionDays, modelSnapshotRetentionDays, categorizationFilters, customSettings,
                    modelSnapshotId, establishedModelMemory);
        }
    }
}
