/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.indexlifecycle;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.Settings.Builder;
import org.elasticsearch.index.Index;
import org.elasticsearch.xpack.core.indexlifecycle.AsyncActionStep;
import org.elasticsearch.xpack.core.indexlifecycle.AsyncWaitStep;
import org.elasticsearch.xpack.core.indexlifecycle.ClusterStateWaitStep;
import org.elasticsearch.xpack.core.indexlifecycle.InitializePolicyContextStep;
import org.elasticsearch.xpack.core.indexlifecycle.LifecycleSettings;
import org.elasticsearch.xpack.core.indexlifecycle.Step;
import org.elasticsearch.xpack.core.indexlifecycle.Step.StepKey;
import org.elasticsearch.xpack.core.indexlifecycle.TerminalPolicyStep;

import java.util.function.LongSupplier;

public class IndexLifecycleRunner {
    private static final Logger logger = ESLoggerFactory.getLogger(IndexLifecycleRunner.class);
    private PolicyStepsRegistry stepRegistry;
    private ClusterService clusterService;
    private LongSupplier nowSupplier;

    public IndexLifecycleRunner(PolicyStepsRegistry stepRegistry, ClusterService clusterService, LongSupplier nowSupplier) {
        this.stepRegistry = stepRegistry;
        this.clusterService = clusterService;
        this.nowSupplier = nowSupplier;
    }

    public void runPolicy(String policy, IndexMetaData indexMetaData, Settings indexSettings, boolean fromClusterStateChange) {
        Step currentStep = getCurrentStep(stepRegistry, policy, indexSettings);
        logger.warn("running policy with current-step[" + currentStep.getKey() + "]");
        if (currentStep instanceof TerminalPolicyStep) {
            logger.debug("policy [" + policy + "] for index [" + indexMetaData.getIndex().getName() + "] complete, skipping execution");
        } else if (currentStep instanceof InitializePolicyContextStep || currentStep instanceof ClusterStateWaitStep) {
            executeClusterStateSteps(indexMetaData.getIndex(), policy, currentStep);
        } else if (currentStep instanceof AsyncWaitStep) {
            if (fromClusterStateChange == false) {
                ((AsyncWaitStep) currentStep).evaluateCondition(indexMetaData.getIndex(), new AsyncWaitStep.Listener() {
    
                    @Override
                    public void onResponse(boolean conditionMet) {
                        logger.error("cs-change-async-wait-callback. current-step:" + currentStep.getKey());
                        if (conditionMet) {
                            moveToStep(indexMetaData.getIndex(), policy, currentStep.getKey(), currentStep.getNextStepKey());
                        }
                    }
    
                    @Override
                    public void onFailure(Exception e) {
                        throw new RuntimeException(e); // NORELEASE implement error handling
                    }
                    
                });
            }
        } else if (currentStep instanceof AsyncActionStep) {
            if (fromClusterStateChange == false) {
                ((AsyncActionStep) currentStep).performAction(indexMetaData, new AsyncActionStep.Listener() {
    
                    @Override
                    public void onResponse(boolean complete) {
                        logger.error("cs-change-async-action-callback. current-step:" + currentStep.getKey());
                        if (complete && ((AsyncActionStep) currentStep).indexSurvives()) {
                            moveToStep(indexMetaData.getIndex(), policy, currentStep.getKey(), currentStep.getNextStepKey());
                        }
                    }
    
                    @Override
                    public void onFailure(Exception e) {
                        throw new RuntimeException(e); // NORELEASE implement error handling
                    }
                });
            }
        } else {
            throw new IllegalStateException(
                    "Step with key [" + currentStep.getKey() + "] is not a recognised type: [" + currentStep.getClass().getName() + "]");
        }
    }

    private void runPolicy(IndexMetaData indexMetaData) {
        Settings indexSettings = indexMetaData.getSettings();
        String policy = LifecycleSettings.LIFECYCLE_NAME_SETTING.get(indexSettings);
        runPolicy(policy, indexMetaData, indexSettings, false);
    }

    private void executeClusterStateSteps(Index index, String policy, Step step) {
        assert step instanceof InitializePolicyContextStep || step instanceof ClusterStateWaitStep;
        clusterService.submitStateUpdateTask("ILM", new ExecuteStepsUpdateTask(policy, index, step, stepRegistry, nowSupplier));
    }

    /**
     * Retrieves the current {@link StepKey} from the index settings. Note that
     * it is illegal for the step to be set with the phase and/or action unset,
     * or for the step to be unset with the phase and/or action set. All three
     * settings must be either present or missing.
     * 
     * @param indexSettings
     *            the index settings to extract the {@link StepKey} from.
     */
    static StepKey getCurrentStepKey(Settings indexSettings) {
        String currentPhase = LifecycleSettings.LIFECYCLE_PHASE_SETTING.get(indexSettings);
        String currentAction = LifecycleSettings.LIFECYCLE_ACTION_SETTING.get(indexSettings);
        String currentStep = LifecycleSettings.LIFECYCLE_STEP_SETTING.get(indexSettings);
        if (Strings.isNullOrEmpty(currentStep)) {
            assert Strings.isNullOrEmpty(currentPhase) : "Current phase is not empty: " + currentPhase;
            assert Strings.isNullOrEmpty(currentAction) : "Current action is not empty: " + currentAction;
            return null;
        } else {
            assert Strings.isNullOrEmpty(currentPhase) == false;
            assert Strings.isNullOrEmpty(currentAction) == false;
            return new StepKey(currentPhase, currentAction, currentStep);
        }
    }

    static Step getCurrentStep(PolicyStepsRegistry stepRegistry, String policy, Settings indexSettings) {
        StepKey currentStepKey = getCurrentStepKey(indexSettings);
        if (currentStepKey == null) {
            return stepRegistry.getFirstStep(policy);
        } else {
            return stepRegistry.getStep(policy, currentStepKey);
        }
    }

    static ClusterState moveClusterStateToNextStep(Index index, ClusterState clusterState, StepKey currentStep, StepKey nextStep,
            LongSupplier nowSupplier) {
        ClusterState.Builder newClusterStateBuilder = ClusterState.builder(clusterState);
        IndexMetaData idxMeta = clusterState.getMetaData().index(index);
        Builder indexSettings = Settings.builder().put(idxMeta.getSettings()).put(LifecycleSettings.LIFECYCLE_PHASE, nextStep.getPhase())
                .put(LifecycleSettings.LIFECYCLE_ACTION, nextStep.getAction()).put(LifecycleSettings.LIFECYCLE_STEP, nextStep.getName());
        if (currentStep.getPhase().equals(nextStep.getPhase()) == false) {
            indexSettings.put(LifecycleSettings.LIFECYCLE_PHASE_TIME, nowSupplier.getAsLong());
        }
        if (currentStep.getAction().equals(nextStep.getAction()) == false) {
            indexSettings.put(LifecycleSettings.LIFECYCLE_ACTION_TIME, nowSupplier.getAsLong());
        }
        newClusterStateBuilder.metaData(MetaData.builder(clusterState.getMetaData()).put(IndexMetaData
                .builder(clusterState.getMetaData().index(index))
                .settings(indexSettings)));
        return newClusterStateBuilder.build();
    }

    private void moveToStep(Index index, String policy, StepKey currentStepKey, StepKey nextStepKey) {
        logger.error("moveToStep[" + policy + "] [" + index.getName() + "]" + currentStepKey + " -> "
                + nextStepKey);
        clusterService.submitStateUpdateTask("ILM", new MoveToNextStepUpdateTask(index, policy, currentStepKey,
                nextStepKey, nowSupplier, newState -> runPolicy(newState.getMetaData().index(index))));
    }
}
