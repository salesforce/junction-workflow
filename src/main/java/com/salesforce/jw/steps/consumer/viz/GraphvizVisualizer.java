/*
 *
 *  * Copyright (c) 2022, salesforce.com, inc.
 *  * All rights reserved.
 *  * Licensed under the BSD 3-Clause license.
 *  * For full license text, see LICENSE.txt file in the repo root or
 *  * https://opensource.org/licenses/BSD-3-Clause
 *
 */

package com.salesforce.jw.steps.consumer.viz;

import com.salesforce.jw.kafka.KafkaConfig;
import com.salesforce.jw.kafka.KafkaLatestStateConsumer;
import com.salesforce.jw.kafka.KafkaWorkflowLogsConsumer;
import io.vertx.core.Vertx;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.salesforce.jw.steps.WorkflowProtos.Workflow;

/**
 * We have three ways to invoke this program:
 *  1. As quartz job that we can schedule
 *  2. As runnable thread that runs asynchronously
 *  3. As synchronous program via invocation of main
 */
public record GraphvizVisualizer(long timeToRunSecs) implements Job, Runnable {
    private final static Logger log = LoggerFactory.getLogger(GraphvizVisualizer.class);

    public GraphvizVisualizer() {
        this(TimeUnit.DAYS.toSeconds(7));
    }
    // We don't want this to be an expiring map because we want to see all the projects we've processed. We might want
    // to use redis if we need to scale this later
    public final static Map<String, Workflow> workflowsCache = new ConcurrentSkipListMap<>(
            Comparator.comparing(Function.identity(), (key1, key2) -> {
                if (key1.split(":").length != 6 || key2.split(":").length != 6) {
                    return key1.compareTo(key2);
                }
                var runId1 = Integer.parseInt(key1.substring(key1.lastIndexOf(":") + 1));
                var runId2 = Integer.parseInt(key2.substring(key2.lastIndexOf(":") + 1));
                if (runId1 < runId2) {
                    return 1;
                } else if (runId1 > runId2) {
                    return -1;
                } else {
                    return key1.compareTo(key2);
                }
            }));
    final static Map<String, List<String>> logCache = new ConcurrentSkipListMap<>();

    public static void main(String[] args) {
        new GraphvizVisualizer().run();
    }

    // TODO: Adding this for e2e tests for now - this feels wrong to be a public method
    public static void clearCache() {
        workflowsCache.clear();
    }

    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        if (workflowsCache.isEmpty()) {
            log.info("Nothing to display as we have not populated our cache yet");
        }
        log.debug("Cache size {}", workflowsCache.size());
        log.debug("Cache keys {}", workflowsCache.keySet());
    }

    @Override
    public void run() {
        try(KafkaLatestStateConsumer loadPreviousConsumer =
                    new KafkaLatestStateConsumer()) {
            workflowsCache.putAll(loadPreviousConsumer.getAllFromStart());
        }
        log.info("Finished caching all state from Kafka");

        try {
            Vertx vertx = Vertx.vertx();
            vertx.deployVerticle(PipelinesWeb.class.getName());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // TODO: Set better group id name
        try(KafkaLatestStateConsumer stepStateTopic =
                    new KafkaLatestStateConsumer(GraphvizVisualizer.class.getSimpleName());
            KafkaWorkflowLogsConsumer logsConsumer =
                    new KafkaWorkflowLogsConsumer(GraphvizVisualizer.class.getSimpleName(), OffsetResetStrategy.EARLIEST)) {
            Scheduler scheduler = null;

            JobDataMap jobDataMap = new JobDataMap();
            jobDataMap.put(KafkaConfig.WORKFLOW_STATE_TOPIC_CONSUMER_JOB_DATA_KEY, stepStateTopic);
            jobDataMap.put(KafkaConfig.WORKFLOW_LOGS_TOPIC_NAME, logsConsumer);
            JobDetail jobDetail = JobBuilder.newJob(GraphvizVisualizer.class)
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

                long startTime = System.currentTimeMillis();
                while (startTime + TimeUnit.SECONDS.toMillis(timeToRunSecs) > System.currentTimeMillis()) {
                    workflowsCache.putAll(stepStateTopic.pollWorkflows());

                    Map<String, List<String>> logs = logsConsumer.getLatestLogs();
                    logs.keySet().forEach(key -> {
                        if (logCache.containsKey(key)) {
                            logCache.get(key).addAll(logs.get(key));
                        } else {
                            logCache.put(key, logs.get(key));
                        }
                    });
                }
            } finally {
                if (scheduler != null) {
                    scheduler.shutdown();
                }
            }

        } catch (IOException e) {
            throw new RuntimeException("Unable to close Kafka consumer", e);
        } catch (SchedulerException e) {
            throw new RuntimeException(e);
        }
    }
}
