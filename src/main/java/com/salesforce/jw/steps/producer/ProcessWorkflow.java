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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.salesforce.jw.kafka.KafkaHistoricStateProducer;
import com.salesforce.jw.kafka.KafkaLatestStateProducer;
import com.salesforce.jw.parsers.WorkflowInterface;
import com.salesforce.jw.parsers.cix.CIXPipeline;
import com.salesforce.jw.parsers.tekton.TektonPipeline;
import com.salesforce.jw.steps.WorkflowProtos;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import org.kohsuke.github.GitHub;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public record ProcessWorkflow(KafkaLatestStateProducer latestStateTopic, KafkaHistoricStateProducer historicStateTopic, GitHub gitHubApi) {
    private final static Logger log = LoggerFactory.getLogger(ProcessWorkflow.class);
    private final static Deque<UnprocessedWorkflow> toProcessQueue = new ConcurrentLinkedDeque<>();

    public ProcessWorkflow(KafkaLatestStateProducer latestStateTopic, KafkaHistoricStateProducer historicStateTopic) throws JobExecutionException {
        this(latestStateTopic, historicStateTopic, GitHubReader.init());

    }

    // TODO: This should be persisted to a Kafka topic
    protected void queue(UnprocessedWorkflow unprocessedWorkflow) {
        log.info("Adding {} to queue", unprocessedWorkflow);
        toProcessQueue.add(unprocessedWorkflow);
    }

    protected void persistInDistributedLog(UnprocessedWorkflow unprocessedWorkflow) throws IOException, InterruptedException, ExecutionException {
        var ghRepo = gitHubApi.getRepository("%s/%s".formatted(unprocessedWorkflow.org(), unprocessedWorkflow.repo()));
        var ghBranch = ghRepo.getBranch(unprocessedWorkflow.branch());
        var vcsHost = getHostFromUrl(ghRepo.getHttpTransportUrl());

        ghRepo.getDirectoryContent("/", ghBranch.getName())
                .parallelStream()
                .filter(file -> WorkflowInterface.toSetOfFiles().contains(file.getName()))
                .forEach(
                        pipelineConfig -> {
                            try {
                                var workflowFilename = pipelineConfig.getName();
                                var workflowDownloadUrl = pipelineConfig.getDownloadUrl();

                                log.info("Downloading {}", workflowDownloadUrl);
                                String buildKey = unprocessedWorkflow.getKey();
                                log.info("Our key will be: {}", buildKey);
                                WorkflowProtos.Workflow workflow = readPipelineConfigAndGenerateNestedStepsRecord(
                                        workflowFilename, workflowDownloadUrl,
                                        unprocessedWorkflow.gitsha(), vcsHost, unprocessedWorkflow.org(), unprocessedWorkflow.repo(), unprocessedWorkflow.branch());
                                // TODO: Some idempotency would be good
                                latestStateTopic.sendWorkflowState(buildKey, workflow);
                                historicStateTopic.sendWorkflowState(buildKey, workflow);
                            } catch (IOException | ExecutionException | InterruptedException e) {
                                log.error("Unable to process pipeline config", e);
                            }
                        }
                );
    }

    // TODO: Move to another class
    public static String getHostFromUrl(String vcsApiUrl) throws MalformedURLException {
        return new URL(vcsApiUrl).getHost();
    }

    // TODO: We need to consolidate out workflow metadata into an object and make it more generic
    WorkflowProtos.Workflow readPipelineConfigAndGenerateNestedStepsRecord(String filename, String downloadUrl, String uuid,
                                                                                          String vcs, String org, String repo,
                                                                                          String branch) throws InterruptedException {
        AtomicReference<WorkflowProtos.Workflow> serializedWorkflow = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean();

        WebClient client = WebClient.create(Vertx.vertx());
        var webClientFuture = client.getAbs(downloadUrl)
                .send()
                .onSuccess(response -> {
                    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
                    Optional<String> workflowName = Optional.of("%s/%s - %s".formatted(org, repo, branch));
                    log.info("Downloading {}", downloadUrl);

                    switch (Objects.requireNonNull(WorkflowInterface.fromDeclarationFile(filename))) {
                        case CIX -> {
                            try {
                                serializedWorkflow.set(WorkflowProtos.Workflow.newBuilder(ingestCixProject(mapper, response.bodyAsString(), workflowName))
                                        .setUuid(uuid)
                                        .setVcs(vcs)
                                        .setOrganization(org)
                                        .setProject(repo)
                                        .setBranch(branch)
                                        .build());
                            } catch (JsonProcessingException e) {
                                log.error("Unable to process response from github for %s".formatted(downloadUrl), e);
                            }
                        }
                        case TEKTON_PIPELINE -> {
                            try {
                                serializedWorkflow.set(WorkflowProtos.Workflow.newBuilder(ingestTektonPipeline(mapper, response.bodyAsString(), workflowName))
                                        .setUuid(uuid)
                                        .setVcs(vcs)
                                        .setOrganization(org)
                                        .setProject(repo)
                                        .setBranch(branch)
                                        .build());
                            } catch (JsonProcessingException e) {
                                log.error("Unable to process response from github for %s".formatted(downloadUrl), e);
                            }
                        }
                    }
                    if (serializedWorkflow.get() == null) {
                        log.error("Unable to invoke parser for filename {} in {}/{} - this should never happen",
                                filename, org, repo);
                    }
                })
                .onFailure(failureHandler -> {
                    log.error("Unable to download Tekton pipeline from %s due to %s"
                            .formatted(downloadUrl, failureHandler.getCause()), failureHandler);
                })
                .onComplete(handler -> {
                    completed.set(true);
                });
        while (!completed.get()) {
            if (webClientFuture.failed()) {
                log.info("Failed to retrieve pipeline to process");
                return null;
            }
            log.debug("Waiting to download workflow declaration and serialize it");
            TimeUnit.MILLISECONDS.sleep(25);
        }
        if (serializedWorkflow.get() == null) {
            log.error("Did not serialize a workflow for {} [enum: {}] from {}. Result from web client: {} body: {}", filename, WorkflowInterface.fromDeclarationFile(filename), downloadUrl, webClientFuture.succeeded(), webClientFuture.result().bodyAsString());
        }
        return serializedWorkflow.get();
    }

    private WorkflowProtos.Workflow ingestTektonPipeline(ObjectMapper mapper, String body,
                                                                Optional<String> workflowName) throws JsonProcessingException {
        TektonPipeline tektonPipeline = mapper.readValue(body, TektonPipeline.class);
        return tektonPipeline.toWorkflowProto(workflowName);
    }

    protected WorkflowProtos.Workflow ingestCixProject(ObjectMapper mapper, String body,
                                                              Optional<String> workflowName) throws JsonProcessingException {
        CIXPipeline cixYaml = mapper.readValue(body, CIXPipeline.class);
        log.info(cixYaml.toString());
        return cixYaml.toWorkflowProto(workflowName);
    }

    public void processQueue() {
        if (!toProcessQueue.isEmpty()) {
            UnprocessedWorkflow unprocessedWorkflow = toProcessQueue.pollFirst();
            try {
                persistInDistributedLog(unprocessedWorkflow);
            } catch (IOException | InterruptedException | ExecutionException e) {
                log.error("Failed to persist workflow request {}", unprocessedWorkflow);
            }
        }
    }
}
