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

import com.salesforce.jw.kafka.KafkaHistoricStateProducer;
import com.salesforce.jw.kafka.KafkaLatestStateConsumer;
import com.salesforce.jw.kafka.KafkaLatestStateProducer;
import com.salesforce.jw.kafka.KafkaWorkflowLogsProducer;
import com.salesforce.jw.steps.WorkflowOperations;
import com.salesforce.jw.steps.WorkflowProtos;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.salesforce.jw.kafka.KafkaConfig.*;
import static com.salesforce.jw.steps.WorkflowProtos.Step.State.ERRORED;
import static com.salesforce.jw.steps.WorkflowProtos.Step.State.TIMED_OUT;
import static com.salesforce.jw.steps.WorkflowProtos.Workflow;

public class WorkExec implements Job, Runnable {
    private final static Logger log = LoggerFactory.getLogger(WorkExec.class);
    // TODO: Both of these should be unhardcoded and passed through config
    public static final int MAX_WORKER_THREADS = Runtime.getRuntime().availableProcessors() * 2;
    // TODO: Timeout should be scoped to workflow configuration
    private static final long MAX_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);
    private static final String WORKFLOW_EXECUTORS_KEY = "WF_EXECUTORS";
    private static final String WORKFLOW_COMPLETED_SET_KEY = "WF_COMPLETED_SET";

    private final int runDurationMins;

    public WorkExec() {
        runDurationMins = Integer.MAX_VALUE;
    }

    public WorkExec(int runDurationMins) {
        this.runDurationMins = runDurationMins;
    }

    public static void main(String[] args) {
        new WorkExec().run();
    }

    @Override
    public void run() {
        KafkaHistoricStateProducer historicStateTopicProducer = new KafkaHistoricStateProducer();
        KafkaLatestStateProducer latestStateTopicProducer = new KafkaLatestStateProducer();
        KafkaWorkflowLogsProducer logsProducer = new KafkaWorkflowLogsProducer();
        Map<String, ExecutorService> workerExectors = new ConcurrentHashMap<>();

        // TODO: Set more appropriate work id
        try(KafkaLatestStateConsumer latestStateTopicConsumer = new KafkaLatestStateConsumer(WorkExec.class.getSimpleName(), false)) {
            Scheduler scheduler = null;

            JobDataMap jobDataMap = new JobDataMap();
            jobDataMap.put(WORKFLOW_STATE_TOPIC_CONSUMER_JOB_DATA_KEY, latestStateTopicConsumer);
            jobDataMap.put(WORKFLOW_HISTORIC_STATE_TOPIC_PRODUCER_JOB_DATA_KEY, historicStateTopicProducer);
            jobDataMap.put(WORKFLOW_LATEST_STATE_TOPIC_PRODUCER_JOB_DATA_KEY, latestStateTopicProducer);
            jobDataMap.put(WORKFLOW_LOGS_TOPIC_PRODUCER_JOB_DATA_KEY, logsProducer);
            jobDataMap.put(WORKFLOW_EXECUTORS_KEY, workerExectors);
            jobDataMap.put(WORKFLOW_COMPLETED_SET_KEY, new HashSet<>());
            JobDetail jobDetail = JobBuilder.newJob(WorkExec.class)
                    .withIdentity(this.getClass().getSimpleName())
                    .usingJobData(jobDataMap)
                    .build();
            Trigger trigger = TriggerBuilder.newTrigger()
                    .startNow()
                    .withSchedule(SimpleScheduleBuilder
                            .repeatSecondlyForever()
                            .withMisfireHandlingInstructionNowWithRemainingCount())
                    .build();
            try {
                scheduler = StdSchedulerFactory.getDefaultScheduler();
                scheduler.scheduleJob(jobDetail, trigger);
                scheduler.start();
                TimeUnit.MINUTES.sleep(runDurationMins);
            } finally {
                if (scheduler != null) {
                    scheduler.shutdown();
                }
            }
        } catch (SchedulerException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void attemptRetry(WorkflowOperations workflowOperations, Workflow workflow,
                              KafkaWorkflowLogsProducer logsProducer, String key,
                              KafkaLatestStateProducer latestStateTopicProducer) {
        log.info("Attempting retry for {}:{}", key, workflow);
        logsProducer.sendLog(key, "Attempting to re-try on %s".formatted(getHostName()));
        // TODO: Unhardcode retry count. Ideally this would come at creation of initial protobuf so we can have retries
        // at the project level
        var maxRetryCount = workflowOperations.getMaxRetryCount(workflow);
        if (maxRetryCount >= 3) {
            logsProducer.sendLog(key, "Workflow has been retried 3 times, marking to ERROR");
            var erroredWf = workflowOperations.updateAllToState(workflow, ERRORED);
            try {
                latestStateTopicProducer.sendWorkflowState(key, erroredWf);
            } catch (ExecutionException | InterruptedException e) {
                throw new RuntimeException("Unable to send retry workflow for %s".formatted(key), e);
            }
            return;
        }
        var retryWf = workflowOperations.updateForRetry(workflow);
        try {
            latestStateTopicProducer.sendWorkflowState(key, retryWf);
        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException("Unable to send retry workflow for %s".formatted(key), e);
        }
    }

    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        log.debug("Polling for workflows...");
        WorkflowOperations workflowOperations = new WorkflowOperations();
        // TODO: Error handling for casts
        KafkaLatestStateConsumer latestStateTopicConsumer = (KafkaLatestStateConsumer)jobExecutionContext
                .getJobDetail().getJobDataMap().get(WORKFLOW_STATE_TOPIC_CONSUMER_JOB_DATA_KEY);
        KafkaHistoricStateProducer historicStateTopicProducer = (KafkaHistoricStateProducer)jobExecutionContext
                .getJobDetail().getJobDataMap().get(WORKFLOW_HISTORIC_STATE_TOPIC_PRODUCER_JOB_DATA_KEY);
        KafkaLatestStateProducer latestStateTopicProducer = (KafkaLatestStateProducer)jobExecutionContext
                .getJobDetail().getJobDataMap().get(WORKFLOW_LATEST_STATE_TOPIC_PRODUCER_JOB_DATA_KEY);
        KafkaWorkflowLogsProducer logsProducer = (KafkaWorkflowLogsProducer)jobExecutionContext
                .getJobDetail().getJobDataMap().get(WORKFLOW_LOGS_TOPIC_PRODUCER_JOB_DATA_KEY);
        var workerExecutors = (Map<String, ExecutorService>)jobExecutionContext
                .getJobDetail().getJobDataMap().get(WORKFLOW_EXECUTORS_KEY);
        /**
         * We need this completed set to deal with a race where the Kafka client returns NOT_STARTED messages
         * that have not been auto-compacted yet. If the node crashes, it is possible that we'll have the race again.
         * It is somewhat mitigated by setting `max.compaction.lag.ms` to a small number (e.g. 500ms, which is 1/2 of
         * our polling period).
         * TODO: Experiment with either tuning Kafka compaction or maintain state for COMPLETED workflows in another topic
         */
        var compeletedSet = (Set<String>)jobExecutionContext
                .getJobDetail().getJobDataMap().get(WORKFLOW_COMPLETED_SET_KEY);

        Map<WorkflowProtos.Step.State, AtomicInteger> stateProcessedCounts = new ConcurrentHashMap<>();
        AtomicInteger terminalStateCounts = new AtomicInteger(0);

        var wfPollResult = latestStateTopicConsumer.pollWorkflows();
        // Don't throw any exceptions in the below loop
        List<RuntimeException> exceptions = new LinkedList<>();
        wfPollResult.forEach((key, workflow) -> {
            // TODO: Put this into an executor service of a reasonable size, so we can keep polling for more work
            // TODO: All output, exception, shell STDOUT and STDERR should go to kafka topic keyed by key
            var lifecycleState = workflowOperations.getLifecycleState(workflow);
            logsProducer.sendLog(key, "%s picked up %s with state %s".formatted(getHostName(), key, lifecycleState.name()));
            log.info("Processing workflow {} with state {}", key, lifecycleState.name());

            switch (lifecycleState) {
                case NOT_STARTED -> {
                    if (workerExecutors.containsKey(key)) {
                        // We employ at-least once semantics with Kafka
                        log.info("Work appears to be already started for {}", key);
                        return;
                    }
                    if (compeletedSet.contains(key)) {
                        log.error("Compaction should have happen to {} - workflow already completed", key);
                        return;
                    }

                    logsProducer.sendLog(key, "DEBUG: Agent %s is running %s workflows. Capacity: %s"
                            .formatted(getHostName(), workerExecutors.size(), MAX_WORKER_THREADS));
                    if (workerExecutors.size() >= MAX_WORKER_THREADS) {
                        log.info("Agent {} has {} run in flight [{}], cannot process more. " +
                                        "Add more Kafka partitions and agents.",
                                getHostName(), workerExecutors.size(), workerExecutors.keySet());
                        logsProducer.sendLog(key, "Queued on agent %s...".formatted(getHostName()));
                        try {
                            var offset = latestStateTopicProducer.sendWorkflowState(key,
                                    workflowOperations.updateAllStepTimestampsToNow(workflow));
                            log.info("Sent workflow {} with updated update stamps to try getting picked up again. " +
                                    "Offset: {}", key, offset);
                        } catch (ExecutionException | InterruptedException e) {
                            exceptions.add(new RuntimeException("Failed to update timestamp for %s".formatted(key), e));
                        }
                        return;
                    }
                    log.info("Starting work on {}", key);

                    workerExecutors.put(key, Executors.newSingleThreadExecutor());
                    switch (workflow.getType()) {
                        case CIX ->
                                startCixJob(key, workflow, historicStateTopicProducer, latestStateTopicProducer,
                                        logsProducer, workerExecutors);
                        case TEKTON_PIPELINE ->
                                startTektonPipeline(key, workflow, historicStateTopicProducer, latestStateTopicProducer,
                                        logsProducer, workerExecutors);
                        default -> {
                            log.warn("UNHANDLED workflow type. I don't know how to do that yet!!");
                        }
                    }
                    // These are run-once executors. We never want to submit again and want the exec service to
                    // terminate when thread finishes
                    workerExecutors.get(key).shutdown();

                }
                case RUNNING, PAUSED -> {
                    if (workerExecutors.containsKey(key)) {
                        log.info("Found workflow in running or paused state: {}", key);
                        log.debug("Detailed workflow: {}", workflow);

                        if (workerExecutors.get(key).isTerminated()) {
                            log.warn("One of our workflows ({}) appears to have stopped", key);
                        } else {
                            var now = System.currentTimeMillis();
                            var lastUpdateTimeMs = workflowOperations.getLastUpdateTimeMs(workflow);
                            if (now - lastUpdateTimeMs > MAX_TIMEOUT_MS) {
                                logsProducer.sendLog(key, "Timed out after %s minutes"
                                        .formatted(TimeUnit.MILLISECONDS.toMinutes(MAX_TIMEOUT_MS)));
                                log.info("Hit timedout condition for {} with start time {} and current time {}", key,
                                        lastUpdateTimeMs, now);
                                var erroredWf = workflowOperations.updateAllToState(workflow, TIMED_OUT);
                                try {
                                    latestStateTopicProducer.sendWorkflowState(key, erroredWf);
                                } catch (ExecutionException | InterruptedException e) {
                                    exceptions.add(new RuntimeException("Unable to send TIMED_OUT state update for %s".formatted(key), e));
                                }
                            }
                        }
                    } else if (compeletedSet.contains(key)) {
                        log.info("Workflow is in our completed set, ignoring running state");
                    } else {
                        // We've likely had a node failure, so we need to re-try the run
                        log.info("Didn't find workflow executor ({}) within keyspace {}", key, workerExecutors.keySet());
                        attemptRetry(workflowOperations, workflow, logsProducer, key, latestStateTopicProducer);
                    }
                }
                case COMPLETED, ERRORED, SKIPPED -> {
                    markWorkflowTerminalState(workerExecutors, compeletedSet, terminalStateCounts, key);
                    terminalStateCounts.incrementAndGet();
                }
                case TIMED_OUT -> {
                    attemptRetry(workflowOperations, workflow, logsProducer, key, latestStateTopicProducer);
                }
                case UNRECOGNIZED -> log.error("Got state that we didn't recognize for {}:{}", key, workflow);
                default -> log.error("Unable to recognize workflow state for %s".formatted(workflow));
            }
            if (stateProcessedCounts.containsKey(lifecycleState)) {
                stateProcessedCounts.get(lifecycleState).incrementAndGet();
            } else {
                stateProcessedCounts.put(lifecycleState, new AtomicInteger(1));
            }
        });

        exceptions.forEach(exception -> {
            log.error("Exception while processing workflows", exception);
        });
        var maybeExecption = exceptions.stream().findFirst();
        if (maybeExecption.isPresent()) {
            throw maybeExecption.get();
        }

        // If all states are terminal states, then we can safely commit offsets and all partitions assigned to the agent
        var totalStatesProcessed = stateProcessedCounts.values().stream().mapToInt(AtomicInteger::get).sum();
        if (wfPollResult.size() > 0 && terminalStateCounts.get() == totalStatesProcessed && workerExecutors.size() == 0) {
            log.info("Committing offsets for consumer");
            latestStateTopicConsumer.commit();
        } else {
            log.info("Not committing offsets due to {} non-completed workflows or no poll results. Active wfs: {}",
                    totalStatesProcessed - terminalStateCounts.get(), workerExecutors.keySet());
        }
        log.info("Finished polling {} workflows...", totalStatesProcessed);
    }

    private static void markWorkflowTerminalState(Map<String, ExecutorService> workerExecutors, Set<String> compeletedSet, AtomicInteger terminalStateCounts, String key) {
        // Terminal states; remove state and skip
        if (workerExecutors.containsKey(key)) {
            workerExecutors.get(key).shutdownNow();
            workerExecutors.remove(key);
        }
        compeletedSet.add(key);
    }

    private static String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            throw new RuntimeException("Failed to get agent hostname", e);
        }
    }

    void startCixJob(String key, Workflow workflow, KafkaHistoricStateProducer historicStateTopicProducer,
                     KafkaLatestStateProducer latestStateTopicProducer, KafkaWorkflowLogsProducer logsProducer,
                     Map<String, ExecutorService> workerExecutors) {
        int cixPort;
        try (ServerSocket reservedPort = new ServerSocket(0)) {
            cixPort = reservedPort.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Unable to reserve port for cix");
        }
        workerExecutors.get(key).execute(new CixWorker(key, workflow,
                historicStateTopicProducer, latestStateTopicProducer, logsProducer, cixPort));
    }
    void startTektonPipeline(String key, Workflow workflow, KafkaHistoricStateProducer historicStateTopicProducer,
                             KafkaLatestStateProducer latestStateTopicProducer, KafkaWorkflowLogsProducer logsProducer,
                             Map<String, ExecutorService> workerExecutors) {
        workerExecutors.get(key).execute(new TektonPipelineRunWorker(key, workflow,
                historicStateTopicProducer, latestStateTopicProducer, logsProducer, new WorkflowOperations()));
    }
}
