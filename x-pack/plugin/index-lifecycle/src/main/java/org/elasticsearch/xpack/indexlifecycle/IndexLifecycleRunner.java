/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.indexlifecycle;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateUpdateTask;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.Settings.Builder;
import org.elasticsearch.index.Index;
import org.elasticsearch.xpack.core.indexlifecycle.AsyncActionStep;
import org.elasticsearch.xpack.core.indexlifecycle.AsyncWaitStep;
import org.elasticsearch.xpack.core.indexlifecycle.ClusterStateActionStep;
import org.elasticsearch.xpack.core.indexlifecycle.ClusterStateWaitStep;
import org.elasticsearch.xpack.core.indexlifecycle.LifecycleSettings;
import org.elasticsearch.xpack.core.indexlifecycle.Step;
import org.elasticsearch.xpack.core.indexlifecycle.Step.StepKey;

public class IndexLifecycleRunner {
    private static final Logger logger = ESLoggerFactory.getLogger(IndexLifecycleRunner.class);
    private PolicyStepsRegistry stepRegistry;
    private ClusterService clusterService;

    public IndexLifecycleRunner(PolicyStepsRegistry stepRegistry, ClusterService clusterService) {
        this.stepRegistry = stepRegistry;
        this.clusterService = clusterService;
    }

    public void runPolicy(String policy, Index index, Settings indexSettings, Cause cause) {
        Step currentStep = getCurrentStep(policy, indexSettings);
        logger.warn("running policy with current-step[" + currentStep.getKey() + "]");
        if (currentStep instanceof ClusterStateActionStep || currentStep instanceof ClusterStateWaitStep) {
            if (cause != Cause.SCHEDULE_TRIGGER) {
                executeClusterStateSteps(index, policy, currentStep);
            }
        } else if (currentStep instanceof AsyncWaitStep) {
            if (cause != Cause.CLUSTER_STATE_CHANGE) {
                ((AsyncWaitStep) currentStep).evaluateCondition(index, new AsyncWaitStep.Listener() {
    
                    @Override
                    public void onResponse(boolean conditionMet) {
                        logger.error("cs-change-async-wait-callback. current-step:" + currentStep.getKey());
                        if (conditionMet) {
                            moveToStep(index, policy, currentStep.getKey(), currentStep.getNextStepKey(), Cause.CALLBACK);
                        }
                    }
    
                    @Override
                    public void onFailure(Exception e) {
                        throw new RuntimeException(e); // NORELEASE implement error handling
                    }
                    
                });
            }
        } else if (currentStep instanceof AsyncActionStep) {
            if (cause != Cause.CLUSTER_STATE_CHANGE) {
                ((AsyncActionStep) currentStep).performAction(index, new AsyncActionStep.Listener() {
    
                    @Override
                    public void onResponse(boolean complete) {
                        logger.error("cs-change-async-action-callback. current-step:" + currentStep.getKey());
                        if (complete && currentStep.indexSurvives()) {
                            moveToStep(index, policy, currentStep.getKey(), currentStep.getNextStepKey(), Cause.CALLBACK);
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

    private void runPolicy(Index index, ClusterState clusterState, Cause cause) {
        IndexMetaData indexMetaData = clusterState.getMetaData().index(index);
        Settings indexSettings = indexMetaData.getSettings();
        String policy = LifecycleSettings.LIFECYCLE_NAME_SETTING.get(indexSettings);
        runPolicy(policy, index, indexSettings, cause);
    }

    private void executeClusterStateSteps(Index index, String policy, Step step) {
        assert step instanceof ClusterStateActionStep || step instanceof ClusterStateWaitStep;
        clusterService.submitStateUpdateTask("ILM", new ExecuteStepsUpdateTask(index, step,
            (currentState) -> getCurrentStep(policy, currentState.getMetaData().index(index).getSettings()),
            (currentState, currentStep) -> moveClusterStateToNextStep(index, currentState, currentStep.getNextStepKey()),
            (stepKey) -> stepRegistry.getStep(policy, stepKey)));
    }

    private StepKey getCurrentStepKey(Settings indexSettings) {
        String currentPhase = LifecycleSettings.LIFECYCLE_PHASE_SETTING.get(indexSettings);
        String currentAction = LifecycleSettings.LIFECYCLE_ACTION_SETTING.get(indexSettings);
        String currentStep = LifecycleSettings.LIFECYCLE_STEP_SETTING.get(indexSettings);
        if (currentStep == null) {
            assert currentPhase == null : "Current phase is not null: " + currentPhase;
            assert currentAction == null : "Current action is not null: " + currentAction;
            return null;
        } else {
            assert currentPhase != null;
            assert currentAction != null;
            return new StepKey(currentPhase, currentAction, currentStep);
        }
    }

    private Step getCurrentStep(String policy, Settings indexSettings) {
        StepKey currentStepKey = getCurrentStepKey(indexSettings);
        if (currentStepKey == null) {
            return stepRegistry.getFirstStep(policy);
        } else {
            return stepRegistry.getStep(policy, currentStepKey);
        }
    }

    private ClusterState moveClusterStateToNextStep(Index index, ClusterState clusterState, StepKey nextStep) {
        ClusterState.Builder newClusterStateBuilder = ClusterState.builder(clusterState);
        IndexMetaData idxMeta = clusterState.getMetaData().index(index);
        Builder indexSettings = Settings.builder().put(idxMeta.getSettings()).put(LifecycleSettings.LIFECYCLE_PHASE, nextStep.getPhase())
                .put(LifecycleSettings.LIFECYCLE_ACTION, nextStep.getAction()).put(LifecycleSettings.LIFECYCLE_STEP, nextStep.getName());
        newClusterStateBuilder.metaData(MetaData.builder(clusterState.getMetaData()).put(IndexMetaData
                .builder(clusterState.getMetaData().index(index))
                .settings(indexSettings)));
        return newClusterStateBuilder.build();
    }

    private void moveToStep(Index index, String policy, StepKey currentStepKey, StepKey nextStepKey, Cause cause) {
        logger.error("moveToStep[" + policy + "] [" + index.getName() + "]" + currentStepKey + " -> "
            + nextStepKey + ". because:" + cause.name());
        clusterService.submitStateUpdateTask("ILM", new MoveToNextStepUpdateTask(index, policy,
            currentStepKey, (c) -> moveClusterStateToNextStep(index, c, nextStepKey),
            (s) -> getCurrentStepKey(s), (c) -> runPolicy(index, c, cause)));
    }

    public enum Cause {
        CLUSTER_STATE_CHANGE, SCHEDULE_TRIGGER, CALLBACK;
    }
}
