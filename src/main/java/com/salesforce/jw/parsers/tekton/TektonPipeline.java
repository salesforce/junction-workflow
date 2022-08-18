/*
 *
 *  * Copyright (c) 2022, salesforce.com, inc.
 *  * All rights reserved.
 *  * Licensed under the BSD 3-Clause license.
 *  * For full license text, see LICENSE.txt file in the repo root or
 *  * https://opensource.org/licenses/BSD-3-Clause
 *  
 */

package com.salesforce.jw.parsers.tekton;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.salesforce.jw.parsers.PipelineParser;
import com.salesforce.jw.steps.WorkflowProtos;
import io.kubernetes.client.proto.Meta;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public record TektonPipeline(String apiVersion, String kind, Metadata metadata, Spec spec) implements PipelineParser {
    @JsonCreator
    public TektonPipeline(@JsonProperty("apiVersion") String apiVersion,
                          @JsonProperty("kind") String kind,
                          @JsonProperty("metadata") Metadata metadata,
                          @JsonProperty("spec") Spec spec) {
        this.apiVersion = apiVersion;
        this.kind = kind;
        this.metadata = metadata;
        this.spec = spec;
    }

    @Override
    public WorkflowProtos.Workflow.Type getType() {
        return WorkflowProtos.Workflow.Type.TEKTON_PIPELINE;
    }

    @Override
    public WorkflowProtos.Workflow toWorkflowProto(Optional<String> defaultName) {
        WorkflowProtos.Workflow.Builder workflowBuilder = WorkflowProtos.Workflow.newBuilder();
        WorkflowProtos.Steps.Builder topStepBuilder = WorkflowProtos.Steps.newBuilder();
        List<WorkflowProtos.Steps> tasks = new LinkedList<>();
        spec.tasks().forEach(task -> {
            WorkflowProtos.Steps.Builder taskAsSteps = WorkflowProtos.Steps.newBuilder();

            var stepList = task.taskSpec().steps().stream()
                    .map(tektonStep -> WorkflowProtos.Step.newBuilder()
                            .setUuid(UUID.randomUUID().toString())
                            .setTimestampStart(System.currentTimeMillis())
                            .setTimestampEnd(System.currentTimeMillis())
                            .setTryCount(0)
                            .setState(WorkflowProtos.Step.State.NOT_STARTED)
                            .setName(tektonStep.getName())
                            .build())
                    .toList();

            tasks.add(taskAsSteps
                    .setName(task.name())
                    .setUuid(UUID.randomUUID().toString())
                    .setParallel(false)
                    .addAllStep(stepList)
                    .build());
        });

        topStepBuilder
                .setName(metadata().name())
                .setUuid(UUID.randomUUID().toString())
                .setParallel(true)
                .addAllSteps(tasks);
        return workflowBuilder
                .addSteps(topStepBuilder.build())
                .setType(WorkflowProtos.Workflow.Type.TEKTON_PIPELINE)
                .setUuid(UUID.randomUUID().toString())
                .build();
    }
}
