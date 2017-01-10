/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.job.process.autodetect;

import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.IOUtils;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.EsRejectedExecutionException;
import org.elasticsearch.env.Environment;
import org.elasticsearch.xpack.ml.job.Job;
import org.elasticsearch.xpack.ml.job.ModelSnapshot;
import org.elasticsearch.xpack.ml.job.persistence.JobProvider;
import org.elasticsearch.xpack.ml.job.process.NativeController;
import org.elasticsearch.xpack.ml.job.process.ProcessCtrl;
import org.elasticsearch.xpack.ml.job.process.ProcessPipes;
import org.elasticsearch.xpack.ml.job.quantiles.Quantiles;
import org.elasticsearch.xpack.ml.lists.ListDocument;
import org.elasticsearch.xpack.ml.utils.ExceptionsHelper;
import org.elasticsearch.xpack.ml.utils.NamedPipeHelper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;

public class NativeAutodetectProcessFactory implements AutodetectProcessFactory {

    private static final Logger LOGGER = Loggers.getLogger(NativeAutodetectProcessFactory.class);
    private static final NamedPipeHelper NAMED_PIPE_HELPER = new NamedPipeHelper();
    private static final Duration PROCESS_STARTUP_TIMEOUT = Duration.ofSeconds(2);

    private final Environment env;
    private final Settings settings;
    private final JobProvider jobProvider;
    private final NativeController nativeController;

    public NativeAutodetectProcessFactory(JobProvider jobProvider, Environment env, Settings settings, NativeController nativeController) {
        this.env = Objects.requireNonNull(env);
        this.settings = Objects.requireNonNull(settings);
        this.jobProvider = Objects.requireNonNull(jobProvider);
        this.nativeController = Objects.requireNonNull(nativeController);
    }

    @Override
    public AutodetectProcess createAutodetectProcess(Job job, boolean ignoreDowntime, ExecutorService executorService) {
        List<Path> filesToDelete = new ArrayList<>();
        List<ModelSnapshot> modelSnapshots = jobProvider.modelSnapshots(job.getId(), 0, 1).results();
        ModelSnapshot modelSnapshot = (modelSnapshots != null && !modelSnapshots.isEmpty()) ? modelSnapshots.get(0) : null;

        ProcessPipes processPipes = new ProcessPipes(env, NAMED_PIPE_HELPER, ProcessCtrl.AUTODETECT, job.getId(),
                true, false, true, true, modelSnapshot != null, !ProcessCtrl.DONT_PERSIST_MODEL_STATE_SETTING.get(settings));
        createNativeProcess(job, processPipes, ignoreDowntime, filesToDelete);
        int numberOfAnalysisFields = job.getAnalysisConfig().analysisFields().size();

        NativeAutodetectProcess autodetect = null;
        try {
            autodetect = new NativeAutodetectProcess(job.getId(), processPipes.getLogStream().get(),
                    processPipes.getProcessInStream().get(), processPipes.getProcessOutStream().get(),
                    processPipes.getPersistStream().get(), numberOfAnalysisFields, filesToDelete, executorService);
            if (modelSnapshot != null) {
                // TODO (norelease): I don't think we should do this in the background. If this happens then we should wait
                // until restore it is done before we can accept data.
                executorService.execute(() -> {
                    try (OutputStream r = processPipes.getRestoreStream().get()) {
                        jobProvider.restoreStateToStream(job.getId(), modelSnapshot, r);
                    } catch (Exception e) {
                        LOGGER.error("Error restoring model state for job " + job.getId(), e);
                    }
                });
            }
            return autodetect;
        } catch (EsRejectedExecutionException e) {
            try {
                IOUtils.close(autodetect);
            } catch (IOException ioe) {
                LOGGER.error("Can't close autodetect", ioe);
            }
            throw e;
        }
    }

    private void createNativeProcess(Job job, ProcessPipes processPipes, boolean ignoreDowntime, List<Path> filesToDelete) {

        String jobId = job.getId();
        Optional<Quantiles> quantiles = jobProvider.getQuantiles(jobId);

        try {
            AutodetectBuilder autodetectBuilder = new AutodetectBuilder(job, filesToDelete, LOGGER, env,
                    settings, nativeController, processPipes)
                    .ignoreDowntime(ignoreDowntime)
                    .referencedLists(resolveLists(job.getAnalysisConfig().extractReferencedLists()));

            // if state is null or empty it will be ignored
            // else it is used to restore the quantiles
            if (quantiles != null) {
                autodetectBuilder.quantiles(quantiles);
            }

            autodetectBuilder.build();
            processPipes.connectStreams(PROCESS_STARTUP_TIMEOUT);
        } catch (IOException | TimeoutException e) {
            String msg = "Failed to launch autodetect for job " + job.getId();
            LOGGER.error(msg);
            throw ExceptionsHelper.serverError(msg, e);
        }
    }

    private Set<ListDocument> resolveLists(Set<String> listIds) {
        Set<ListDocument> resolved = new HashSet<>();
        for (String listId : listIds) {
            Optional<ListDocument> list = jobProvider.getList(listId);
            if (list.isPresent()) {
                resolved.add(list.get());
            } else {
                LOGGER.warn("List '" + listId + "' could not be retrieved.");
            }
        }
        return resolved;
    }
}

