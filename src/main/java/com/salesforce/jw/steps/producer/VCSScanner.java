/*
 *
 *  * Copyright (c) 2022, salesforce.com, inc.
 *  * All rights reserved.
 *  * Licensed under the BSD 3-Clause license.
 *  * For full license text, see LICENSE.txt file in the repo root or
 *  * https://opensource.org/licenses/BSD-3-Clause
 *
 */

package com.salesforce.jw.steps.producer;

import com.google.common.util.concurrent.RateLimiter;
import com.salesforce.jw.kafka.KafkaHistoricStateProducer;
import com.salesforce.jw.kafka.KafkaLatestStateConsumer;
import com.salesforce.jw.kafka.KafkaLatestStateProducer;
import com.salesforce.jw.parsers.WorkflowInterface;
import com.salesforce.jw.steps.WorkflowOperations;
import com.salesforce.jw.steps.WorkflowProtos;
import io.vertx.core.Vertx;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterable;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.salesforce.jw.kafka.KafkaConfig.WORKFLOW_HISTORIC_STATE_TOPIC_PRODUCER_JOB_DATA_KEY;
import static com.salesforce.jw.kafka.KafkaConfig.WORKFLOW_LATEST_STATE_TOPIC_PRODUCER_JOB_DATA_KEY;

public class VCSScanner implements Job {
    private final static Logger log = LoggerFactory.getLogger(VCSScanner.class);

    final static Map<String, AtomicInteger> nextRunIdMap = new ConcurrentHashMap<>();

    /**
     * 1. Reads from Kafka topic to determine state of what projects it last processed
     * 2. Stores in-memory (or distributed KV store) key-values where key is the repo URL and value is record of last
     *  commit/sha and other metadata
     * 3. Get webhook or thread polls VCS to determine what to process
     * Producers to Kafka when there
     */
    public static void main(String[] args) {
        KafkaLatestStateProducer latestStateTopic = new KafkaLatestStateProducer();
        KafkaHistoricStateProducer historicStateTopic = new KafkaHistoricStateProducer();
        nextRunIdMap.putAll(new WorkflowOperations().getCurrentRunIdMap());

        try {
            Scheduler scheduler = null;

            // TODO: We'll probably want to have separate jobs for each VCS we want to poll vs. handle a webhook.
            //  Each VCS would implement the same VCS interface.
            String pollFrequencyAsCron = System.getenv("JW_POLL_FREQUENCY_AS_CRON");
            JobDataMap jobDataMap = new JobDataMap();
            jobDataMap.put(WORKFLOW_HISTORIC_STATE_TOPIC_PRODUCER_JOB_DATA_KEY, latestStateTopic);
            jobDataMap.put(WORKFLOW_LATEST_STATE_TOPIC_PRODUCER_JOB_DATA_KEY, historicStateTopic);

            JobDetail jobDetail = JobBuilder.newJob(VCSScanner.class)
                    .withIdentity(VCSScanner.class.getSimpleName()).build();
            var triggerBuilder = TriggerBuilder.newTrigger()
                    .startNow();
            if (pollFrequencyAsCron != null) {
                triggerBuilder.withSchedule(CronScheduleBuilder.cronSchedule(pollFrequencyAsCron));
            }
            var trigger = triggerBuilder.build();

            try {
                scheduler = StdSchedulerFactory.getDefaultScheduler();
                scheduler.scheduleJob(jobDetail, trigger);
                boolean poll = System.getenv("JW_POLL") != null && Boolean.parseBoolean(System.getenv("JW_POLL"));
                if (poll) {
                    log.info("Starting polling on schedule: {}",
                            pollFrequencyAsCron == null ? "Start-time only: Set JW_POLL_FREQUENCY_AS_CRON to run on schedule" : pollFrequencyAsCron);
                    scheduler.start();
                }
                try {
                    Vertx vertx = Vertx.vertx();
                    vertx.deployVerticle(RunPipeline.class.getName());
                    var processWorkflow = new ProcessWorkflow(latestStateTopic, historicStateTopic);
                    // We're limited by Github's API limits; TODO: Place the rate limits on github calls instead
                    @SuppressWarnings("UnstableApiUsage")
                    RateLimiter rateLimiter = RateLimiter.create(5000.0 / 60 / 60);
                    while (true) {
                        rateLimiter.acquire();
                        processWorkflow.processQueue();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } finally {
                if (scheduler != null) {
                    scheduler.shutdown();
                }
            }
        } catch (SchedulerException e) {
            throw new RuntimeException(e.getUnderlyingException());
        }
    }

    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        KafkaHistoricStateProducer historicStateTopicProducer = (KafkaHistoricStateProducer)jobExecutionContext
                .getJobDetail().getJobDataMap().get(WORKFLOW_HISTORIC_STATE_TOPIC_PRODUCER_JOB_DATA_KEY);
        KafkaLatestStateProducer latestStateTopicProducer = (KafkaLatestStateProducer)jobExecutionContext
                .getJobDetail().getJobDataMap().get(WORKFLOW_LATEST_STATE_TOPIC_PRODUCER_JOB_DATA_KEY);

        Set<String> repoPipelineConfigs = WorkflowInterface.toSetOfFiles();
        String orgNames = System.getenv("JW_GH_ORGS");
        if (orgNames == null) {
            throw new JobExecutionException("You must specify JW_GH_ORGS");
        }
        Set<String> scanOrgs = Stream.of(orgNames.trim().split("\\s*,\\s*"))
                .collect(Collectors.toSet());

        GitHub github = GitHubReader.init();

        /**
         * TODO: We should have a consumer thread here as an optimization so that we don't keep hammering our Kafka
         *         topic with repo data that hasn't changed. Basically, we'd have an
         *         in-memory map of org/repo/branch -> sha .
         *         If during our scan we see the same sha, we noop. Webhooks will also update this map when we get that working
         */

        scanOrgs.forEach(org -> {
            try {
                GHOrganization ghOrg = github.getOrganization(org);
                PagedIterable<GHRepository> repos = ghOrg.listRepositories();
                repos.toList().forEach(repo -> {
                    try {
                        repo.getBranches().values().forEach(ghBranch -> {
                            try {
                                repo.getDirectoryContent("/", ghBranch.getName())
                                        .parallelStream()
                                        .filter(file -> repoPipelineConfigs.contains(file.getName()))
                                        .forEach(
                                                pipelineConfig -> {
                                                    try {
                                                        new ProcessWorkflow(latestStateTopicProducer, historicStateTopicProducer)
                                                                .persistInDistributedLog(new UnprocessedWorkflow(repo.getGitTransportUrl(), org, repo.getName(), ghBranch.getName(), ghBranch.getSHA1()));
                                                    } catch (IOException | ExecutionException | InterruptedException |
                                                             JobExecutionException e) {
                                                        log.error("Unable to process pipeline config", e);
                                                    }
                                                }
                                        );
                            } catch (IOException e) {
                                log.error("Unable to process gh branch %s".formatted(ghBranch), e);
                            }
                        });
                    } catch (IOException e) {
                        log.error("Unable to process gh repo %s".formatted(repo), e);
                    }
                });
            } catch (IOException e) {
                log.error("Unable to process gh org %s".formatted(org), e);
            }
        });
    }

}
