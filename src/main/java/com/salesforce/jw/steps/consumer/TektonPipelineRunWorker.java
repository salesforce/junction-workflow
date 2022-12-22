/*
 *
 *  * Copyright (c) 2022, salesforce.com, inc.
 *  * All rights reserved.
 *  * Licensed under the BSD 3-Clause license.
 *  * For full license text, see LICENSE.txt file in the repo root or
 *  * https://opensource.org/licenses/BSD-3-Clause
 *
 */

package com.salesforce.jw.steps.consumer;

import com.salesforce.jw.k8s.PodPhase;
import com.salesforce.jw.kafka.KafkaHistoricStateProducer;
import com.salesforce.jw.kafka.KafkaLatestStateProducer;
import com.salesforce.jw.kafka.KafkaWorkflowLogsProducer;
import com.salesforce.jw.parsers.WorkflowInterface;
import com.salesforce.jw.steps.WorkflowOperations;
import com.salesforce.jw.steps.WorkflowProtos;
import io.kubernetes.client.PodLogs;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public record TektonPipelineRunWorker(String key, WorkflowProtos.Workflow workflow,
                                      KafkaHistoricStateProducer historicStateProducer,
                                      KafkaLatestStateProducer latestStateProducer,
                                      KafkaWorkflowLogsProducer logsProducer,
                                      WorkflowOperations workflowOperations) implements Runnable {
    private final static Logger log = LoggerFactory.getLogger(TektonPipelineRunWorker.class);
    private final static Map<PodPhase, WorkflowProtos.Step.State> podPhaseToState =
            Map.of(
                    PodPhase.PENDING, WorkflowProtos.Step.State.RUNNING,
                    PodPhase.FAILED, WorkflowProtos.Step.State.ERRORED,
                    PodPhase.RUNNING, WorkflowProtos.Step.State.RUNNING,
                    PodPhase.SUCCEEDED, WorkflowProtos.Step.State.COMPLETED,
                    PodPhase.UNKNOWN, WorkflowProtos.Step.State.RUNNING
            );
    private final static String PIPELINE_RUN_TEMPLATE = """
            apiVersion: tekton.dev/v1beta1
            kind: PipelineRun
            metadata:
              name: %s
              namespace: %s
            spec:
              pipelineRef:
                name: %s
            """;
    public static final String TEKTON_PIPELINE_RUN_YAML = "tekton-pipeline-run.yaml";

    @Override
    public void run() {
        try {
            var projectPath = Workspace.setupAndGetProjectPath(key, logsProducer, workflow, WorkflowInterface.TEKTON_PIPELINE);
            if (projectPath == null) {
                return;
            }

            setupK8sClient();
            var namespaceStr = key.replace(":", "-").replace(".", "-");
            var namespace = createNamespace(namespaceStr);
            try {
                var pipelineName = workflow.getSteps(0).getName();
                applyAndRunPipeline(projectPath, pipelineName, namespace.getMetadata().getName());

                pollPodsTillPodsCompleteOrTimeout(namespace.getMetadata().getName());
            } finally {
                deleteNamespace(namespace);
            }
        } catch (IOException | ExecutionException | InterruptedException | ApiException e) {
            throw new RuntimeException("Got exception for %s".formatted(key), e);
        } finally {
            try {
                Workspace.clean(key);
            } catch (IOException e) {
                throw new RuntimeException("Failed to remove workspace after run", e);
            }
        }
    }

    void pollPodsTillPodsCompleteOrTimeout(String namespace) throws ApiException, InterruptedException {
        Map<String, Executor> podLogThreadPools = new ConcurrentHashMap<>();
        AtomicInteger completeTasks = new AtomicInteger(0);
        final int totalTasks = workflow.getStepsList().get(0).getStepsCount();

        long startTimeMs = System.currentTimeMillis();
        long defaultJobTimeoutMs = Duration.of(5, ChronoUnit.MINUTES).toMillis();
        while (System.currentTimeMillis() - startTimeMs < defaultJobTimeoutMs) {
            // Need to set this so that all state updates happen atomically, else we mis-interpret the overall state
            workflowOperations.setUpdateTimestampToNow();

            CoreV1Api api = new CoreV1Api();
            V1PodList pods = api.listNamespacedPod(namespace, null, null, null, null, null, null, null, null, null, null);

            if (pods.getItems().size() == 0) {
                log.info("Waiting for pods to be initiated...");
                TimeUnit.SECONDS.sleep(1);
                continue;
            }

            var resultWorkflow = workflow;
            for (V1Pod pod : pods.getItems()) {
                resultWorkflow = processPod(namespace, podLogThreadPools, completeTasks, resultWorkflow, pod);
            }

            try {
                latestStateProducer.sendWorkflowState(key, resultWorkflow);
                historicStateProducer.sendWorkflowState(key, resultWorkflow);
            } catch (ExecutionException e) {
                throw new RuntimeException("Unable to send latest state of jobs to Kafka", e);
            }

            if (completeTasks.get() == totalTasks) {
                log.info("All tasks completed for {}! Final workflow: {}", key, resultWorkflow);
                break;
            } else {
                log.info("Found {} complete and {} incomplete tasks", completeTasks.get(), totalTasks - completeTasks.get());
                completeTasks.set(0);
            }
            TimeUnit.SECONDS.sleep(1);
        }
    }

    private WorkflowProtos.Workflow processPod(String namespace, Map<String, Executor> podLogThreadPools, AtomicInteger completeTasks,
                                               WorkflowProtos.Workflow updateWorkflow, V1Pod pod)
            throws ApiException, InterruptedException {
        var resultWorkFlow = updateWorkflow;
        PodPhase phase = PodPhase.UNKNOWN;
        if (pod.getStatus() != null && pod.getStatus().getPhase() != null) {
            phase = phase.fromString(pod.getStatus().getPhase());
        }
        if (pod.getMetadata() == null || pod.getMetadata().getName() == null) {
            logsProducer.sendLog(key, "Internal error inspecting pod: %s".formatted(pod));
            throw new ApiException("Pod name should never be null: %s".formatted(pod));
        }
        String podName = pod.getMetadata().getName();

        List<V1ContainerStatus> containerStatuses = new LinkedList<>();
        if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null) {
            containerStatuses.addAll(pod.getStatus().getContainerStatuses());
        }
        if (containerStatuses.size() == 0) {
            log.info("Waiting for containers to be initiated for pod {}...", podName);
            TimeUnit.SECONDS.sleep(1);
            return resultWorkFlow;
        }

        AtomicInteger completedContainers = new AtomicInteger(0);
        log.debug("Pod {} has containers: {}", podName, containerStatuses.stream().map(V1ContainerStatus::getName).toList());
        for (V1ContainerStatus containerStatus : containerStatuses) {
            log.debug("Checking container status for {}", containerStatus.getName());
            var containerNameUserDefined = containerStatus.getName().replaceFirst("step-", "");

            if (containerStatus.getState() == null) {
                logsProducer.sendLog(key, "Internal error inspecting container: %s".formatted(containerStatus));
                throw new ApiException("Container status should never be null: %s".formatted(containerStatus));
            }
            var containerState = containerStatus.getState();
            if (containerState.getRunning() == null && containerState.getTerminated() == null) {
                // Means it is waiting
                var waiting = containerState.getWaiting();
                logsProducer.sendLog(key, "Container is waiting: %s due to %s".formatted(waiting.getReason(), waiting.getMessage()));
                resultWorkFlow = workflowOperations.updateStepStateByStepName(resultWorkFlow.toBuilder(), containerNameUserDefined,
                        WorkflowProtos.Step.State.RUNNING);
            } else if (containerState.getWaiting() == null && containerState.getTerminated() == null) {
                // Means it is running
                var running = containerState.getRunning();
                log.debug("Found running container: {}", containerState);
                resultWorkFlow = workflowOperations.updateStepStateByStepName(resultWorkFlow.toBuilder(), containerNameUserDefined,
                        WorkflowProtos.Step.State.RUNNING);
                getContainerLogs(podLogThreadPools, namespace, podName, containerStatus.getName());
            } else if (containerState.getWaiting() == null && containerState.getRunning() == null) {
                var terminated = containerState.getTerminated();
                if (terminated.getExitCode() == 0) {
                    resultWorkFlow = workflowOperations.updateStepStateByStepName(resultWorkFlow.toBuilder(), containerNameUserDefined,
                            WorkflowProtos.Step.State.COMPLETED);
                } else {
                    logsProducer.sendLog(key, "Container exited with non-zero exit status: %s due to %s: %s".formatted(terminated.getExitCode(), terminated.getReason(), terminated.getMessage()));
                    resultWorkFlow = workflowOperations.updateStepStateByStepName(resultWorkFlow.toBuilder(), containerNameUserDefined,
                            WorkflowProtos.Step.State.ERRORED);
                }
                completedContainers.incrementAndGet();
                getContainerLogs(podLogThreadPools, namespace, podName, containerStatus.getName());
            }
        }

        switch (phase) {
            case FAILED -> {
                log.error("Pipeline [key:{}] failed due to {}", key, pod.getStatus().getReason());
                checkAndIncrementCompleted(completeTasks, podName, containerStatuses, completedContainers);
            }
            case PENDING, RUNNING, UNKNOWN -> {
                log.debug("Task [key:{} task:{}] is in running state: {}", key, podName, pod.getStatus().getPhase());
            }
            case SUCCEEDED -> {
                checkAndIncrementCompleted(completeTasks, podName, containerStatuses, completedContainers);
                log.info("Task [key:{} task:{}] completed successfully!", key, podName);
            }
        }
        return resultWorkFlow;
    }

    private static void checkAndIncrementCompleted(AtomicInteger completeTasks, String podName, List<V1ContainerStatus> containerStatuses, AtomicInteger completedContainers) {
        if (completedContainers.get() == containerStatuses.size()) {
            completeTasks.incrementAndGet();
        } else {
            log.warn("Pod ({}) thinks it is has finished, but not all containers ({}) completed", podName,
                    containerStatuses.stream().map(V1ContainerStatus::getState).toList());
        }
    }

    private void getContainerLogs(Map<String, Executor> podLogThreadPools,
                                  String namespace, String podName, String containerName) {
        var loggerKey =  "%s:%s:%s".formatted(containerName, podName, containerName);
        if (podLogThreadPools.containsKey(loggerKey)) {
            // TODO: Check if we need to retry adding a logging connection
            log.debug("We already have a logging thread running for pod");
        } else {
            var logExecutor = Executors.newSingleThreadExecutor();
            logExecutor.submit(() -> {
                PodLogs logs = new PodLogs();
                log.debug("Trying to start log stream for {}/{}/{}", namespace, podName, containerName);
                try (var podLogStream = logs.streamNamespacedPodLog(namespace, podName, containerName);
                     BufferedReader podLogReader = new BufferedReader(new InputStreamReader(podLogStream))) {
                    while (true) {
                        var line = podLogReader.readLine();
                        if (line == null) {
                            break;
                        }
                        logsProducer.sendLog(key, "%s:%s: %s".formatted(podName, containerName, line));
                    }
                } catch (IOException | ApiException e) {
                    log.error("Unable to read log for %s/%s/%s".formatted(namespace, podName, containerName), e);
                } finally {
                    log.info("Container log stream ended for {}/{}/{}", namespace, podName, containerName);
                }
            });
            podLogThreadPools.put(loggerKey, Executors.newSingleThreadExecutor());
        }
    }

    void setupK8sClient() throws IOException {
        ApiClient k8sClient = Config.defaultClient();
        // TODO: pass this as a config: k8sClient.setDebugging(true);
        Configuration.setDefaultApiClient(k8sClient);
    }

    V1Namespace createNamespace(String namespace) throws ApiException {
        CoreV1Api api = new CoreV1Api();
        var validNamespace = new AtomicReference<>(namespace);
        if (namespace.length() > 63) {
            validNamespace.set(namespace.substring(namespace.length() - 63));
        }
        V1Namespace namespaceForRun = new V1Namespace().metadata(new V1ObjectMeta().name(validNamespace.get()));
        V1NamespaceList namespaces = api.listNamespace(null, null, null, null, null, null, null, null, null, null);
        var existingNs = namespaces.getItems().stream()
                .filter(ns -> ns.getMetadata().getName().equals(validNamespace.get()))
                .findAny();
        V1Namespace createdNs;
        if (existingNs.isPresent()) {
            log.info("Found existing namespace {}", namespaceForRun);
            createdNs = existingNs.get();
        } else {
            log.info("Creating namespace {}", namespaceForRun);
            createdNs = api.createNamespace(namespaceForRun, null, null, null, null);
        }
        return createdNs;
    }

    void deleteNamespace(V1Namespace namespace) throws ApiException {
        CoreV1Api api = new CoreV1Api();
        V1Status result = api.deleteNamespace(namespace.getMetadata().getName(), null, null, null, null, null, null);
        log.info("Removed namespace after run: {}", result);
    }

    void applyAndRunPipeline(Path projectPath, String pipelineName, String namespace) throws IOException, ExecutionException, InterruptedException {
        // TODO: Would be nice to do this with the native api:
        int pipelineApplyResult = ShellCmd.run(key, projectPath, logsProducer,
                "kubectl", "apply", "--namespace", namespace, "--filename", WorkflowInterface.TEKTON_PIPELINE.getDeclarationFile());
        if (pipelineApplyResult != 0) {
            throw new RuntimeException("Unable to apply tekton pipeline (exitCode: %s): %s"
                    .formatted(pipelineApplyResult, pipelineName));
        }
        Path pipelineRunPath = Paths.get(projectPath.toString(), TEKTON_PIPELINE_RUN_YAML);
        Files.write(pipelineRunPath,
                PIPELINE_RUN_TEMPLATE
                        .formatted(pipelineName, namespace, pipelineName).getBytes(),
                StandardOpenOption.CREATE);
        int pipelineRunApplyResult = ShellCmd.run(key, projectPath, logsProducer,
                "kubectl", "apply", "--namespace", namespace, "--filename", TEKTON_PIPELINE_RUN_YAML);
        if (pipelineRunApplyResult != 0) {
            throw new RuntimeException("Unable to apply tekton pipeline run (exitCode: %s): %s"
                    .formatted(pipelineRunApplyResult, Files.readString(pipelineRunPath)));
        }
    }
}