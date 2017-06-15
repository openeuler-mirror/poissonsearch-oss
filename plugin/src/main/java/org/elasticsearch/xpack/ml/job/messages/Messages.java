/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.job.messages;

import java.text.MessageFormat;
import java.util.Locale;

/**
 * Log and audit message strings
 */
public final class Messages {

    public static final String DATAFEED_AGGREGATIONS_REQUIRES_JOB_WITH_SUMMARY_COUNT_FIELD =
            "A job configured with a datafeed with aggregations must set summary_count_field_name; use doc_count or suitable alternative";
    public static final String DATAFEED_CANNOT_DELETE_IN_CURRENT_STATE = "Cannot delete datafeed [{0}] while its status is {1}";
    public static final String DATAFEED_CANNOT_UPDATE_IN_CURRENT_STATE = "Cannot update datafeed [{0}] while its status is {1}";
    public static final String DATAFEED_CONFIG_CANNOT_USE_SCRIPT_FIELDS_WITH_AGGS =
            "script_fields cannot be used in combination with aggregations";
    public static final String DATAFEED_CONFIG_INVALID_OPTION_VALUE = "Invalid {0} value ''{1}'' in datafeed configuration";
    public static final String DATAFEED_DOES_NOT_SUPPORT_JOB_WITH_LATENCY = "A job configured with datafeed cannot support latency";
    public static final String DATAFEED_NOT_FOUND = "No datafeed with id [{0}] exists";
    public static final String DATAFEED_AGGREGATIONS_REQUIRES_DATE_HISTOGRAM =
            "A top level date_histogram (or histogram) aggregation is required";
    public static final String DATAFEED_AGGREGATIONS_INTERVAL_MUST_BE_GREATER_THAN_ZERO =
            "Aggregation interval must be greater than 0";
    public static final String DATAFEED_AGGREGATIONS_INTERVAL_MUST_LESS_OR_EQUAL_TO_BUCKET_SPAN =
            "Aggregation interval [{0}] must be less than or equal to the bucket_span [{1}]";

    public static final String INCONSISTENT_ID =
            "Inconsistent {0}; ''{1}'' specified in the body differs from ''{2}'' specified as a URL argument";
    public static final String INVALID_ID = "Invalid {0}; ''{1}'' can contain lowercase alphanumeric (a-z and 0-9), hyphens or " +
            "underscores; must start and end with alphanumeric";

    public static final String JOB_AUDIR_DATAFEED_DATA_SEEN_AGAIN = "Datafeed has started retrieving data again";
    public static final String JOB_AUDIT_CREATED = "Job created";
    public static final String JOB_AUDIT_CLOSING = "Job is closing";
    public static final String JOB_AUDIT_FORCE_CLOSING = "Job is closing (forced)";
    public static final String JOB_AUDIT_DATAFEED_CONTINUED_REALTIME = "Datafeed continued in real-time";
    public static final String JOB_AUDIT_DATAFEED_DATA_ANALYSIS_ERROR = "Datafeed is encountering errors submitting data for analysis: {0}";
    public static final String JOB_AUDIT_DATAFEED_DATA_EXTRACTION_ERROR = "Datafeed is encountering errors extracting data: {0}";
    public static final String JOB_AUDIT_DATAFEED_LOOKBACK_COMPLETED = "Datafeed lookback completed";
    public static final String JOB_AUDIT_DATAFEED_LOOKBACK_NO_DATA = "Datafeed lookback retrieved no data";
    public static final String JOB_AUDIT_DATAFEED_NO_DATA = "Datafeed has been retrieving no data for a while";
    public static final String JOB_AUDIT_DATAFEED_RECOVERED = "Datafeed has recovered data extraction and analysis";
    public static final String JOB_AUDIT_DATAFEED_STARTED_FROM_TO = "Datafeed started (from: {0} to: {1})";
    public static final String JOB_AUDIT_DATAFEED_STARTED_REALTIME = "Datafeed started in real-time";
    public static final String JOB_AUDIT_DATAFEED_STOPPED = "Datafeed stopped";
    public static final String JOB_AUDIT_DELETED = "Job deleted";
    public static final String JOB_AUDIT_KILLING = "Killing job";
    public static final String JOB_AUDIT_OLD_RESULTS_DELETED = "Deleted results prior to {1}";
    public static final String JOB_AUDIT_REVERTED = "Job model snapshot reverted to ''{0}''";
    public static final String JOB_AUDIT_SNAPSHOT_DELETED = "Model snapshot [{0}] with description ''{1}'' deleted";

    public static final String JOB_CONFIG_BYFIELD_INCOMPATIBLE_FUNCTION = "by_field_name cannot be used with function ''{0}''";
    public static final String JOB_CONFIG_CATEGORIZATION_FILTERS_CONTAINS_DUPLICATES = "categorization_filters contain duplicates";
    public static final String JOB_CONFIG_CATEGORIZATION_FILTERS_CONTAINS_EMPTY =
            "categorization_filters are not allowed to contain empty strings";
    public static final String JOB_CONFIG_CATEGORIZATION_FILTERS_CONTAINS_INVALID_REGEX =
            "categorization_filters contains invalid regular expression ''{0}''";
    public static final String JOB_CONFIG_CATEGORIZATION_FILTERS_REQUIRE_CATEGORIZATION_FIELD_NAME =
            "categorization_filters require setting categorization_field_name";
    public static final String JOB_CONFIG_CONDITION_INVALID_VALUE_NULL = "Invalid condition: the value field cannot be null";
    public static final String JOB_CONFIG_CONDITION_INVALID_VALUE_NUMBER =
            "Invalid condition value: cannot parse a double from string ''{0}''";
    public static final String JOB_CONFIG_CONDITION_INVALID_VALUE_REGEX =
            "Invalid condition value: ''{0}'' is not a valid regular expression";
    public static final String JOB_CONFIG_DETECTION_RULE_CONDITION_CATEGORICAL_INVALID_OPTION =
            "Invalid detector rule: a categorical rule_condition does not support {0}";
    public static final String JOB_CONFIG_DETECTION_RULE_CONDITION_CATEGORICAL_MISSING_OPTION =
            "Invalid detector rule: a categorical rule_condition requires {0} to be set";
    public static final String JOB_CONFIG_DETECTION_RULE_CONDITION_INVALID_FIELD_NAME =
            "Invalid detector rule: field_name has to be one of {0}; actual was ''{1}''";
    public static final String JOB_CONFIG_DETECTION_RULE_CONDITION_MISSING_FIELD_NAME =
            "Invalid detector rule: missing field_name in rule_condition where field_value ''{0}'' is set";
    public static final String JOB_CONFIG_DETECTION_RULE_CONDITION_NUMERICAL_INVALID_OPERATOR =
            "Invalid detector rule: operator ''{0}'' is not allowed";
    public static final String JOB_CONFIG_DETECTION_RULE_CONDITION_NUMERICAL_INVALID_OPTION =
            "Invalid detector rule: a numerical rule_condition does not support {0}";
    public static final String JOB_CONFIG_DETECTION_RULE_CONDITION_NUMERICAL_MISSING_OPTION =
            "Invalid detector rule: a numerical rule_condition requires {0} to be set";
    public static final String JOB_CONFIG_DETECTION_RULE_CONDITION_NUMERICAL_WITH_FIELD_NAME_REQUIRES_FIELD_VALUE =
            "Invalid detector rule: a numerical rule_condition with field_name requires that field_value is set";
    public static final String JOB_CONFIG_DETECTION_RULE_INVALID_TARGET_FIELD_NAME =
            "Invalid detector rule: target_field_name has to be one of {0}; actual was ''{1}''";
    public static final String JOB_CONFIG_DETECTION_RULE_MISSING_TARGET_FIELD_NAME =
            "Invalid detector rule: missing target_field_name where target_field_value ''{0}'' is set";
    public static final String JOB_CONFIG_DETECTION_RULE_NOT_SUPPORTED_BY_FUNCTION =
            "Invalid detector rule: function {0} does not support rules";
    public static final String JOB_CONFIG_DETECTION_RULE_REQUIRES_AT_LEAST_ONE_CONDITION =
            "Invalid detector rule: at least one rule_condition is required";
    public static final String JOB_CONFIG_FIELDNAME_INCOMPATIBLE_FUNCTION = "field_name cannot be used with function ''{0}''";
    public static final String JOB_CONFIG_FIELD_VALUE_TOO_LOW = "{0} cannot be less than {1,number}. Value = {2,number}";
    public static final String JOB_CONFIG_FUNCTION_INCOMPATIBLE_PRESUMMARIZED =
            "The ''{0}'' function cannot be used in jobs that will take pre-summarized input";
    public static final String JOB_CONFIG_FUNCTION_REQUIRES_BYFIELD = "by_field_name must be set when the ''{0}'' function is used";
    public static final String JOB_CONFIG_FUNCTION_REQUIRES_FIELDNAME = "field_name must be set when the ''{0}'' function is used";
    public static final String JOB_CONFIG_FUNCTION_REQUIRES_OVERFIELD = "over_field_name must be set when the ''{0}'' function is used";
    public static final String JOB_CONFIG_ID_ALREADY_TAKEN = "The job cannot be created with the Id ''{0}''. The Id is already used.";
    public static final String JOB_CONFIG_ID_TOO_LONG = "The job id cannot contain more than {0,number,integer} characters.";
    public static final String JOB_CONFIG_INVALID_CREATE_SETTINGS =
            "The job is configured with fields [{0}] that are illegal to set at job creation";
    public static final String JOB_CONFIG_INVALID_FIELDNAME_CHARS =
            "Invalid field name ''{0}''. Field names including over, by and partition fields cannot contain any of these characters: {1}";
    public static final String JOB_CONFIG_INVALID_FIELDNAME =
            "Invalid field name ''{0}''. Field names including over, by and partition fields cannot be ''{1}''";
    public static final String JOB_CONFIG_INVALID_TIMEFORMAT = "Invalid Time format string ''{0}''";
    public static final String JOB_CONFIG_MISSING_ANALYSISCONFIG = "An analysis_config must be set";
    public static final String JOB_CONFIG_MISSING_DATA_DESCRIPTION = "A data_description must be set";
    public static final String JOB_CONFIG_MULTIPLE_BUCKETSPANS_MUST_BE_MULTIPLE =
            "Multiple bucket_span ''{0}'' must be a multiple of the main bucket_span ''{1}''";
    public static final String JOB_CONFIG_NO_ANALYSIS_FIELD_NOT_COUNT =
            "Unless the function is 'count' one of field_name, by_field_name or over_field_name must be set";
    public static final String JOB_CONFIG_NO_DETECTORS = "No detectors configured";
    public static final String JOB_CONFIG_OVERFIELD_INCOMPATIBLE_FUNCTION =
            "over_field_name cannot be used with function ''{0}''";
    public static final String JOB_CONFIG_OVERLAPPING_BUCKETS_INCOMPATIBLE_FUNCTION =
            "Overlapping buckets cannot be used with function ''{0}''";
    public static final String JOB_CONFIG_PER_PARTITION_NORMALIZATION_CANNOT_USE_INFLUENCERS =
            "A job configured with Per-Partition Normalization cannot use influencers";
    public static final String JOB_CONFIG_PER_PARTITION_NORMALIZATION_REQUIRES_PARTITION_FIELD =
            "If the job is configured with Per-Partition Normalization enabled a detector must have a partition field";
    public static final String JOB_CONFIG_UNKNOWN_FUNCTION = "Unknown function ''{0}''";
    public static final String JOB_CONFIG_UPDATE_ANALYSIS_LIMITS_MODEL_MEMORY_LIMIT_CANNOT_BE_DECREASED =
            "Invalid update value for analysis_limits: model_memory_limit cannot be decreased; existing is {0}, update had {1}";
    public static final String JOB_CONFIG_DETECTOR_DUPLICATE_FIELD_NAME =
            "{0} and {1} cannot be the same: ''{2}''";
    public static final String JOB_CONFIG_DETECTOR_COUNT_DISALLOWED =
            "''count'' is not a permitted value for {0}";
    public static final String JOB_CONFIG_DETECTOR_BY_DISALLOWED =
            "''by'' is not a permitted value for {0}";
    public static final String JOB_CONFIG_DETECTOR_OVER_DISALLOWED =
            "''over'' is not a permitted value for {0}";
    public static final String JOB_CONFIG_MAPPING_TYPE_CLASH =
            "A field has a different mapping type to an existing field with the same name. " +
                    "Use the 'results_index_name' setting to assign the job to another index";
    public static final String JOB_CONFIG_TIME_FIELD_NOT_ALLOWED_IN_ANALYSIS_CONFIG =
            "data_description.time_field may not be used in the analysis_config";

    public static final String JOB_UNKNOWN_ID = "No known job with id ''{0}''";

    public static final String REST_CANNOT_DELETE_HIGHEST_PRIORITY =
            "Model snapshot ''{0}'' is the active snapshot for job ''{1}'', so cannot be deleted";
    public static final String REST_INVALID_DATETIME_PARAMS =
            "Query param ''{0}'' with value ''{1}'' cannot be parsed as a date or converted to a number (epoch).";
    public static final String REST_INVALID_FLUSH_PARAMS_MISSING = "Invalid flush parameters: ''{0}'' has not been specified.";
    public static final String REST_INVALID_FLUSH_PARAMS_UNEXPECTED = "Invalid flush parameters: unexpected ''{0}''.";
    public static final String REST_JOB_NOT_CLOSED_REVERT = "Can only revert to a model snapshot when the job is closed.";
    public static final String REST_NO_SUCH_MODEL_SNAPSHOT = "No model snapshot with id [{0}] exists for job [{1}]";
    public static final String REST_START_AFTER_END = "Invalid time range: end time ''{0}'' is earlier than start time ''{1}''.";

    private Messages() {
    }

    /**
     * Returns the message parameter
     *
     * @param message Should be one of the statics defined in this class
     */
    public static String getMessage(String message) {
        return message;
    }

    /**
     * Format the message with the supplied arguments
     *
     * @param message Should be one of the statics defined in this class
     * @param args MessageFormat arguments. See {@linkplain MessageFormat#format(Object)}]
     */
    public static String getMessage(String message, Object...args) {
        return new MessageFormat(message, Locale.ROOT).format(args);
    }
}
