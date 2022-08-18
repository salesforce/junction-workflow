/*
 *
 *  * Copyright (c) 2022, salesforce.com, inc.
 *  * All rights reserved.
 *  * Licensed under the BSD 3-Clause license.
 *  * For full license text, see LICENSE.txt file in the repo root or
 *  * https://opensource.org/licenses/BSD-3-Clause
 *
 */

package com.salesforce.jw.steps;

import com.salesforce.jw.kafka.KafkaLatestStateConsumer;
import com.salesforce.jw.steps.WorkflowProtos.Step;
import com.salesforce.jw.steps.WorkflowProtos.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.salesforce.jw.steps.WorkflowProtos.Steps;

public class WorkflowOperations {
    private final static Logger log = LoggerFactory.getLogger(WorkflowOperations.class);

    private final AtomicLong updateTimestamp = new AtomicLong(System.currentTimeMillis());

    private Step lastReportedStep(List<Steps> stepsList) {
        return stepsList.stream().map(steps -> {
            Step latestStateOfStepList;
            if (steps.getStepList().size() > 0) {
                latestStateOfStepList = steps.getStepList().stream()
                        .max(Comparator.comparing(Step::getTimestampEnd)).orElseThrow();
                if (steps.getStepsList().size() == 0) {
                    return latestStateOfStepList;
                } else {
                    return Stream.of(lastReportedStep(steps.getStepsList()), latestStateOfStepList)
                            .max(Comparator.comparing(Step::getTimestampEnd)).orElseThrow();
                }
            }
            return Stream.of(lastReportedStep(steps.getStepsList()))
                    .max(Comparator.comparing(Step::getTimestampEnd)).orElseThrow();
        }).max(Comparator.comparing(Step::getTimestampEnd)).orElseThrow();
    }

    public Step.State lastReportedState(Steps steps) {
        return lastReportedStep(List.of(steps)).getState();
    }

    public Step.State lastReportedState(Workflow workflow) {
        return workflow.getStepsList().stream()
                .map(steps -> lastReportedStep(workflow.getStepsList()))
                .max(Comparator.comparing(Step::getTimestampEnd)).orElseThrow()
                .getState();
    }

    private List<Step> stepsContainStates(List<Steps> stepsList, Set<Step.State> states) {
        return stepsList.stream().flatMap(steps -> {
            List<Step> stepsWithState = steps.getStepList().stream()
                    .filter(step -> states.contains(step.getState()))
                    .collect(Collectors.toList());
            stepsWithState.addAll(stepsContainStates(steps.getStepsList(), states));
            return stepsWithState.stream();
        }).collect(Collectors.toList());
    }

    public List<Step> stepsContainState(Workflow workflow, Step.State state) {
        return stepsContainStates(workflow, Set.of(state));
    }

    public List<Step> stepsContainStates(Workflow workflow, Set<Step.State> states) {
        return workflow.getStepsList().stream()
                .flatMap(steps -> stepsContainStates(workflow.getStepsList(), states).stream())
                .collect(Collectors.toList());
    }

    public boolean isDone(Workflow workflow) {
        var notDoneSteps = stepsContainStates(workflow, Set.of(Step.State.NOT_STARTED, Step.State.RUNNING, Step.State.PAUSED));
        if (notDoneSteps.size() > 0) {
            return false;
        }

        switch (lastReportedState(workflow)) {
            case ERRORED, COMPLETED, TIMED_OUT, SKIPPED -> {
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void updateStepStateByStepName(Steps.Builder stepsBuilder, String name, Step.State targetState) {
        stepsBuilder.getStepBuilderList().stream()
                .filter(stepBuilder -> stepBuilder.getName().equals(name))
                .forEach(stepBuilder -> {
                    stepBuilder.setState(targetState);
                    stepBuilder.setTimestampEnd(updateTimestamp.get());
                });
        updateStepStateByStepName(stepsBuilder.getStepsBuilderList(), name, targetState);
    }

    private void updateStepStateByStepName(List<Steps.Builder> stepsBuilders, String name, Step.State targetState) {
        for (Steps.Builder stepsBuilder : stepsBuilders) {
            updateStepStateByStepName(stepsBuilder, name, targetState);
        }
    }

    public Workflow updateStepStateByStepName(Workflow.Builder workflowBuilder, String name, Step.State targetState) {
        log.trace("Workflow builder was {}", workflowBuilder);
        updateStepStateByStepName(workflowBuilder.getStepsBuilderList(), name, targetState);
        log.trace("Updated workflow builder to {}", workflowBuilder);
        return workflowBuilder.build();
    }

    public Workflow updateAllStepTimestampsToNow(Workflow workflow) {
        setUpdateTimestampToNow();
        var builder = workflow.toBuilder();
        builder.getStepsBuilderList().forEach(this::updateAllStepTimestampsToNow);
        return builder.build();
    }

    private void updateAllStepTimestampsToNow(Steps.Builder stepGroup) {
        stepGroup.getStepsBuilderList().forEach(steps -> {
            steps.getStepsBuilderList().forEach(this::updateAllStepTimestampsToNow);
            steps.getStepBuilderList().forEach(step -> step.setTimestampEnd(updateTimestamp.get()));
        });
    }

    public void setUpdateTimestampToNow() {
        updateTimestamp.set(System.currentTimeMillis());
    }

    public Map<String, AtomicInteger> getCurrentRunIdMap() {
        Map<String, WorkflowProtos.Workflow> knownWorkflows;
        Map<String, AtomicInteger> subkeyToCurrentRunId = new ConcurrentHashMap<>();
        try (KafkaLatestStateConsumer latestStateConsumer = new KafkaLatestStateConsumer()) {
            knownWorkflows = new ConcurrentHashMap<>(latestStateConsumer.getAllFromStart());
        }

        knownWorkflows.keySet().forEach(key -> {
            if (key.split(":").length != 6) {
                log.warn("Key {} w/o run id detected; skipping putting in runid map", key);
                return;
            }
            var indexBeforeRunId = key.lastIndexOf(":");
            var nonRunIdKey = key.substring(0, indexBeforeRunId);
            var runId = Integer.parseInt(key.substring(indexBeforeRunId + 1));
            if (subkeyToCurrentRunId.containsKey(nonRunIdKey)) {
                var currentRunIdValue = subkeyToCurrentRunId.get(nonRunIdKey);
                if (runId <= currentRunIdValue.get()) {
                    log.info("The run id %s for key %s jumped down in count. Keeping current higher value: %s".formatted(runId, key, currentRunIdValue));
                    return;
                }
                subkeyToCurrentRunId.get(nonRunIdKey).set(runId);
            } else {
                subkeyToCurrentRunId.put(nonRunIdKey, new AtomicInteger(runId));
            }
        });
        return subkeyToCurrentRunId;
    }

    /**
     * Unlike lastReportedState, this function looks at all states to determine the overall lifecycle state of the
     * the workflow graph. For example, our lastReportedState might be COMPLETED, but there might be a node in the
     * graph that is NOT_STARTED or RUNNING. The overall workflow thus should be NOT_STARTED or RUNNING.
     *
     * @param workflow
     */
    public Step.State getLifecycleState(Workflow workflow) {
        List<Step.State> lifecycleOrder = List.of(Step.State.RUNNING, Step.State.PAUSED, Step.State.NOT_STARTED,
                Step.State.ERRORED, Step.State.TIMED_OUT, Step.State.SKIPPED, Step.State.UNRECOGNIZED,
                Step.State.COMPLETED);
        for (Step.State state : lifecycleOrder) {
            if (stepsContainState(workflow, state).size() > 0) {
                return state;
            }
        }
        return Step.State.UNRECOGNIZED;
    }

    private int getMaxRetryCount(List<Steps> stepGroups) {
        if (stepGroups.isEmpty()) {
            return 0;
        }
        return stepGroups.stream().mapToInt(group -> {
                    var groupRetryMax = getMaxRetryCount(group.getStepsList());
                    if (group.getStepList().isEmpty()) {
                        return groupRetryMax;
                    }
                    return Math.max(
                            groupRetryMax,
                            group.getStepList().stream().mapToInt(Step::getTryCount).max().orElseThrow()
                    );
                }
        ).max().orElseThrow(() -> new RuntimeException("Unable to compute max retries for %s".formatted(stepGroups)));
    }

    public int getMaxRetryCount(Workflow workflow) {
        return workflow.getStepsList().stream()
                .mapToInt(steps -> getMaxRetryCount(workflow.getStepsList()))
                .max().orElseThrow(() -> new RuntimeException("Unable to compute max retries for %s".formatted(workflow)));
    }

    public Workflow updateAllToState(Workflow workflow, Step.State targetState) {
        setUpdateTimestampToNow();
        var builder = workflow.toBuilder();
        builder.getStepsBuilderList().forEach(group -> updateAllToState(group, targetState));
        return builder.build();
    }

    private void updateAllToState(Steps.Builder stepGroup, Step.State targetState) {
        stepGroup.getStepsBuilderList().forEach(group -> updateAllToState(group, targetState));
        stepGroup.getStepBuilderList().forEach(step -> {
            step.setTimestampEnd(updateTimestamp.get());
            step.setState(targetState);
        });
    }

    public Workflow updateForRetry(Workflow workflow) {
        setUpdateTimestampToNow();
        var builder = workflow.toBuilder();
        builder.getStepsBuilderList().forEach(this::updateForRetry);
        return builder.build();
    }

    private void updateForRetry(Steps.Builder stepGroup) {
        stepGroup.getStepsBuilderList().forEach(this::updateForRetry);
        stepGroup.getStepBuilderList().forEach(stepBuilder -> {
            stepBuilder.setTimestampEnd(updateTimestamp.get());
            stepBuilder.setTryCount(stepBuilder.getTryCount() + 1);
            stepBuilder.setState(Step.State.NOT_STARTED);
        });
    }

    private long getCreationTimeMs(List<Steps> stepGroups) {
        if (stepGroups.isEmpty()) {
            return Long.MAX_VALUE;
        }
        return stepGroups.stream().mapToLong(group -> {
                    var subGroupCreationTime = getCreationTimeMs(group.getStepsList());
                    if (group.getStepList().isEmpty()) {
                        return subGroupCreationTime;
                    }
                    return Math.min(
                            subGroupCreationTime,
                            group.getStepList().stream().mapToLong(Step::getTimestampStart).min()
                                    .orElseThrow(() -> new RuntimeException("Unable to get start time for %s".formatted(group.getStepList())))
                    );
                }
        ).min().orElseThrow(() -> new RuntimeException("Unable to aggregate start times among %s".formatted(stepGroups)));
    }

    public long getCreationTimeMs(Workflow workflow) {
        return workflow.getStepsList().stream()
                .mapToLong(steps -> getCreationTimeMs(workflow.getStepsList()))
                .min().orElseThrow(() -> new RuntimeException("Unable to aggregate start times in %s".formatted(workflow)));
    }

    private long getLastUpdateTimeMs(List<Steps> stepGroups) {
        if (stepGroups.isEmpty()) {
            return Long.MAX_VALUE;
        }
        return stepGroups.stream().mapToLong(group -> {
                    var subGroupCreationTime = getLastUpdateTimeMs(group.getStepsList());
                    if (group.getStepList().isEmpty()) {
                        return subGroupCreationTime;
                    }
                    return Math.min(
                            subGroupCreationTime,
                            group.getStepList().stream().mapToLong(Step::getTimestampEnd).min()
                                    .orElseThrow(() -> new RuntimeException("Unable to get start time for %s".formatted(group.getStepList())))
                    );
                }
        ).min().orElseThrow(() -> new RuntimeException("Unable to aggregate start times among %s".formatted(stepGroups)));
    }

    public long getLastUpdateTimeMs(Workflow workflow) {
        return workflow.getStepsList().stream()
                .mapToLong(steps -> getLastUpdateTimeMs(workflow.getStepsList()))
                .min().orElseThrow(() -> new RuntimeException("Unable to aggregate start times in %s".formatted(workflow)));
    }
}
