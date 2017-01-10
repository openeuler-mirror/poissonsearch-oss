/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.job.persistence;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkResponse;

import java.util.Objects;
import java.util.function.Function;

/**
 * A class that removes results from all the jobs that
 * have expired their respected retention time.
 */
public class OldDataRemover {

    private final Function<String, JobDataDeleter> dataDeleterFactory;

    public OldDataRemover(Function<String, JobDataDeleter> dataDeleterFactory) {
        this.dataDeleterFactory = Objects.requireNonNull(dataDeleterFactory);
    }

    /**
     * Removes results between the time given and the current time
     */
    public void deleteResultsAfter(ActionListener<BulkResponse> listener, String jobId, long cutoffEpochMs) {
        JobDataDeleter deleter = dataDeleterFactory.apply(jobId);
        deleter.deleteResultsFromTime(cutoffEpochMs, new ActionListener<Boolean>() {
            @Override
            public void onResponse(Boolean success) {
                if (success) {
                    deleter.commit(listener);
                }
            }

            @Override
            public void onFailure(Exception e) {
                listener.onFailure(e);
            }
        });
    }
}
