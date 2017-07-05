/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.job.process.autodetect;

import org.elasticsearch.xpack.ml.job.config.Job;
import org.elasticsearch.xpack.ml.job.config.MlFilter;
import org.elasticsearch.xpack.ml.job.process.autodetect.state.ModelSnapshot;
import org.elasticsearch.xpack.ml.job.process.autodetect.state.Quantiles;

import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Factory interface for creating implementations of {@link AutodetectProcess}
 */
public interface AutodetectProcessFactory {

    /**
     * Create an implementation of {@link AutodetectProcess}
     *
     * @param job             Job configuration for the analysis process
     * @param modelSnapshot   The model snapshot to restore from
     * @param quantiles       The quantiles to push to the native process
     * @param filters         The filters to push to the native process
     * @param executorService Executor service used to start the async tasks a job needs to operate the analytical process
     * @param onProcessCrash  Callback to execute if the process stops unexpectedly
     * @return The process
     */
    AutodetectProcess createAutodetectProcess(Job job, ModelSnapshot modelSnapshot, Quantiles quantiles, Set<MlFilter> filters,
                                              ExecutorService executorService,
                                              Runnable onProcessCrash);
}
