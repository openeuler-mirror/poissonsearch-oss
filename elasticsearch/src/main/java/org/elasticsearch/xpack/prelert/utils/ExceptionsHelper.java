/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
    public static ResourceNotFoundException missingJobException(String jobId) {
        return new ResourceNotFoundException(Messages.getMessage(Messages.JOB_UNKNOWN_ID, jobId));
    }

    public static ResourceAlreadyExistsException jobAlreadyExists(String jobId) {
        throw new ResourceAlreadyExistsException(Messages.getMessage(Messages.JOB_CONFIG_ID_ALREADY_TAKEN, jobId));
    }

    public static ElasticsearchException serverError(String msg) {
        return new ElasticsearchException(msg);
    }

    public static ElasticsearchException serverError(String msg, Throwable cause) {
        return new ElasticsearchException(msg, cause);
    }

    public static ElasticsearchStatusException conflictStatusException(String msg) {
        return new ElasticsearchStatusException(msg, RestStatus.CONFLICT);
    }

    /**
     * A more REST-friendly Object.requireNonNull()
     */
    public static <T> T requireNonNull(T obj, String paramName) {
        if (obj == null) {
            throw new IllegalArgumentException("[" + paramName + "] must not be null.");
        }
        return obj;
    }
}
