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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.salesforce.jw.kafka.KafkaHistoricStateProducer;
import com.salesforce.jw.kafka.KafkaLatestStateProducer;
import com.salesforce.jw.kafka.KafkaWorkflowLogsProducer;
import com.salesforce.jw.parsers.WorkflowInterface;
import com.salesforce.jw.parsers.cix.CIXPipeline;
import com.salesforce.jw.steps.WorkflowOperations;
import com.salesforce.jw.steps.WorkflowProtos.Workflow;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.client.WebClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.netty.handler.codec.http.HttpHeaderValues.APPLICATION_JSON;

public record CixWorker(String key, Workflow workflow,
                        KafkaHistoricStateProducer historicStateProducer,
                        KafkaLatestStateProducer latestStateProducer,
                        KafkaWorkflowLogsProducer logsProducer,
                        int cixPort) implements Runnable {
    private final static Logger log = LoggerFactory.getLogger(CixWorker.class);

    @Override
    public void run() {
        try {
            var projectPath = Workspace.setupAndGetProjectPath(key, logsProducer, workflow, WorkflowInterface.CIX);
            if (projectPath == null) {
                return;
            }
            ShellCmd.ProcessMetadata cixServerRun = ShellCmd.runAsync(key, projectPath, logsProducer, Duration.of(10, ChronoUnit.MINUTES),
                    "cix", "server",
                    "--logging", "console",
                    "--workspace", projectPath.toString(),
                    "--port", String.valueOf(cixPort));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                cleanUpCixServer(cixServerRun);
            }));

            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

            //File cixAtRepoRoot = Paths.get(projectPath.toString(), SupportedCI.CIX.getDeclarationFile()).toFile();
            //log.info("Opening .cix.yaml at {}", cixAtRepoRoot.getAbsolutePath());
            //ObjectNode cixYaml = mapper.readValue(cixAtRepoRoot, ObjectNode.class);
            //for (int tries = 0; tries < 5; tries++) {
            TimeUnit.SECONDS.sleep(5);
            Buffer cixYamlBuffer = Buffer.buffer("""
                    {"yamlPath": "%s"}
                    """.formatted(WorkflowInterface.CIX.getDeclarationFile()));
            WebClient client = WebClient.create(Vertx.vertx());
            client.post(cixPort, "127.0.0.1", "/api/pipeline")
                    .putHeader(HttpHeaders.CONTENT_TYPE.toString(), APPLICATION_JSON.toString())
                    .sendBuffer(cixYamlBuffer)
                    .onFailure(failureHandler ->
                            log.error("Unable to send workload to cix server: %s".formatted(cixYamlBuffer.toString()),
                                failureHandler))
                    .onSuccess(response -> {
                        log.info("Found cix pipeline to start: {} Sent .cix.yaml: {}",
                                response.bodyAsString(), cixYamlBuffer);
                        client.get(cixPort, "127.0.0.1", "/api/pipeline/%s/start"
                                        .formatted(response.bodyAsJsonObject().getMap().get("id")))
                                .send()
                                .onSuccess(startResponse -> {
                                    log.info("Started pipeline {}", startResponse);
                                })
                                .onFailure(handler -> {
                                    log.error("Failed to start pipeline", handler);
                                });
                    });
            //}

            AtomicBoolean finished = new AtomicBoolean(false);
            var startTimeMs = System.currentTimeMillis();
            long defaultJobTimeoutMs = Duration.of(5, ChronoUnit.MINUTES).toMillis();
            try {
                while (System.currentTimeMillis() - startTimeMs < defaultJobTimeoutMs ||
                        !finished.get() || !cixServerRun.future().isDone() || !cixServerRun.future().isCancelled()) {
                    // TODO: Configurable poll period here
                    log.info("Polling CIX for results...");
                    TimeUnit.SECONDS.sleep(2);
                    client.get(cixPort, "127.0.0.1", "/api/pipeline")
                            .send()
                            .onSuccess(response -> {
                                log.info("Found cix pipeline id: {}", response.bodyAsString());
                                if (response.bodyAsJsonArray().size() == 0) {
                                    log.error("No cix pipeline found, trying again...");
                                    return;
                                }
                                client.get(cixPort, "127.0.0.1", "/api/pipeline/%s"
                                                .formatted(response.bodyAsJsonArray().stream().iterator().next()))
                                        .send()
                                        .onSuccess(pipelineStates -> {
                                            log.info("Found states for {}: {}", key, pipelineStates.bodyAsString());
                                            // TODO: Generate workflow proto from output
                                            try {
                                                Workflow workflow = mapper.readValue(pipelineStates.bodyAsString(),
                                                        CIXPipeline.class).toWorkflowProto(Optional.of(key));
                                                latestStateProducer.sendWorkflowState(key, workflow);
                                                historicStateProducer.sendWorkflowState(key, workflow);
                                                if (new WorkflowOperations().isDone(workflow)) {
                                                    log.info("Pipeline on CIX server (localhost:{}) " +
                                                            "reached terminal state, shutting down it down", cixPort);
                                                    cleanUpCixServer(cixServerRun);
                                                    finished.set(true);
                                                }
                                            } catch (JsonProcessingException e) {
                                                log.error("Failed to process CIX result: %s".formatted(pipelineStates.bodyAsString()), e);
                                                finished.set(true);
                                            } catch (ExecutionException | InterruptedException e) {
                                                log.error("Unable to report result for %s".formatted(key), e);
                                                finished.set(true);
                                            }
                                        })
                                        .onFailure(message -> {
                                            log.error("Failed to get state for pipeline %s"
                                                    .formatted(response.bodyAsString()), message);
                                            finished.set(true);
                                        });
                            })
                            .onFailure(handler -> {
                                log.error("Failed to get pipeline", handler);
                                finished.set(true);
                            });
                    if (finished.get()) {
                        break;
                    }
                }
            } finally {
                cleanUpCixServer(cixServerRun);
            }
        } catch (IOException | ExecutionException | InterruptedException e) {
            log.error("Failed to do work for cix", e);
        } finally {
            try {
                Workspace.clean(key);
            } catch (IOException e) {
                throw new RuntimeException("Failed to remove workspace after run", e);
            }
        }
    }

    private void cleanUpCixServer(ShellCmd.ProcessMetadata cixServerRun) {
        ProcessHandle.of(cixServerRun.childPid()).ifPresent(ProcessHandle::destroy);
        cixServerRun.future().cancel(true);
        cixServerRun.executor().shutdownNow();
    }
}
