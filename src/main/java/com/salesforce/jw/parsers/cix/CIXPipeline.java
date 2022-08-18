/*
 *
 *  * Copyright (c) 2022, salesforce.com, inc.
 *  * All rights reserved.
 *  * Licensed under the BSD 3-Clause license.
 *  * For full license text, see LICENSE.txt file in the repo root or
 *  * https://opensource.org/licenses/BSD-3-Clause
 *
 */

package com.salesforce.jw.parsers.cix;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.salesforce.jw.parsers.PipelineParser;
import com.salesforce.jw.steps.WorkflowProtos;
import com.salesforce.jw.steps.WorkflowProtos.Workflow;
import io.vertx.core.json.JsonObject;

import java.util.*;

import static com.salesforce.jw.steps.WorkflowProtos.Step.State;
import static com.salesforce.jw.steps.WorkflowProtos.Step.newBuilder;

@JsonIgnoreProperties(ignoreUnknown = true)
/*
 *  TODO: This entire class ought to be more elegant and might not even be needed. A pipeline consists of a list of
 *        steps or groups of steps. We need to tell Jackson to create two lists of each to refactor for another approach
 *        altogether
 */
public class CIXPipeline implements PipelineParser {
    private final static Map<String, State> statusToState =
            // Needs to match https://github.com/salesforce/cix/blob/master/src/engine/pipeline/Pipeline.js#L23
            Collections.unmodifiableMap(Map.of(
                    "ready", State.NOT_STARTED,
                    "loaded", State.NOT_STARTED,
                    "initializing", State.RUNNING,
                    "running", State.RUNNING,
                    "failed", State.ERRORED,
                    "paused", State.PAUSED,
                    "skipped", State.SKIPPED,
                    "successful", State.COMPLETED
                    ));
    private final Pipeline pipeline;
    private final JsonNode rawPipeline;

    @JsonCreator
    public CIXPipeline(@JsonProperty("pipeline") JsonNode pipeline,
                       @JsonProperty("steps") List<JsonNode> stepsListJson,
                       @JsonProperty("name") String name) {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        List<Step> stepList = new LinkedList<>();
        List<Steps> stepsList = new LinkedList<>();
        if (pipeline == null && stepsListJson == null) {
            throw new RuntimeException("Nothing to process for CIX declaration");
        }
        this.rawPipeline = pipeline;
        if (pipeline != null) {
            pipeline.elements().forEachRemaining(stepOrSteps ->
                    processStepOrSteps(mapper, stepList, stepsList, stepOrSteps));
        }
        if (stepsListJson != null) {
            stepsListJson.forEach(stepOrSteps ->
                    processStepOrSteps(mapper, stepList, stepsList, stepOrSteps));
        }
        this.pipeline = new Pipeline(stepList, stepsList);
    }

    private void processStepOrSteps(ObjectMapper mapper, List<Step> stepList, List<Steps> stepsList, JsonNode stepOrSteps) {
        try {
            if (stepOrSteps.has("step")) {
                stepList.add(mapper.treeToValue(stepOrSteps.get("step"), Step.class));
            } else if (stepOrSteps.has("steps")) {
                /* TODO: this hack shouldn't exist - I couldn't get "@JsonInclude(JsonInclude.Include.NON_EMPTY)"
                 *       to work on the Steps class.
                 */
                if (stepOrSteps.get("steps").has("parallel")) {
                    stepsList.add(mapper.treeToValue(stepOrSteps.get("steps"), Steps.class));
                } else if (stepOrSteps.get("steps") instanceof ArrayNode stepOrStepsArray) {
                    List<Step> subStepList = new LinkedList<>();
                    List<Steps> subStepsList = new LinkedList<>();
                    stepOrStepsArray.forEach(element -> {
                        if (element instanceof ObjectNode modifiableNode) {
                            modifiableNode.set("step", element);
                            processStepOrSteps(mapper, subStepList, subStepsList, modifiableNode);
                        } else {
                            processStepOrSteps(mapper, subStepList, subStepsList, element);
                        }
                    });
                    stepsList.add(new Steps(stepOrSteps.get("name").textValue(),
                            stepOrSteps.has("parallel") ? stepOrSteps.get("parallel").asBoolean() : Steps.DEFAULT_PARALLEL_SETTING,
                            subStepList, subStepsList));
                } else {
                    ObjectNode modifiableNode = (ObjectNode) stepOrSteps.get("steps");
                    modifiableNode.set("parallel", JsonNodeFactory.instance.booleanNode(Steps.DEFAULT_PARALLEL_SETTING));
                    stepsList.add(mapper.treeToValue(modifiableNode, Steps.class));
                }
                //stepList.add(new Steps(stepOrSteps.get("steps").get("name")));
            } else { // Assume plain 'ol step
                stepList.add(mapper.treeToValue(stepOrSteps, Step.class));
            }
        } catch (JsonProcessingException e){
            throw new RuntimeException("Unable to process step", e);
        }
    }

    public CIXPipeline(Pipeline pipeline) {
        this.rawPipeline = null;
        this.pipeline = pipeline;
    }

    public Pipeline pipeline() {
        return pipeline;
    }

    private State convStatusToState(String status) {
        return statusToState.getOrDefault(status.toLowerCase(), State.UNRECOGNIZED);
    }

    @Override
    public Workflow.Type getType() {
        return Workflow.Type.CIX;
    }

    protected WorkflowProtos.Steps.Builder assembleWorkflowSteps(Pipeline pipeline, WorkflowProtos.Steps.Builder wfStepsBuilder) {
        pipeline.stepList().forEach(step ->
                wfStepsBuilder.addStep(
                        newBuilder()
                                .setUuid(UUID.randomUUID().toString())
                                // TODO: This should be passed into this method. It is only by coincidence that we
                                // create our CIX-based work flow when we detect a change
                                .setTimestampStart(System.currentTimeMillis())
                                .setTimestampEnd(System.currentTimeMillis())
                                .setTryCount(0)
                                .setName(step.name())
                                .setState(convStatusToState(step.status()))
                                .build()));
        pipeline.stepsList().forEach(nestedSteps -> {
            WorkflowProtos.Steps.Builder nestedWfSteps = WorkflowProtos.Steps.newBuilder();
            assembleWorkflowSteps(nestedSteps.pipeline(), nestedWfSteps);
            wfStepsBuilder.addSteps(nestedWfSteps
                    .setUuid(UUID.randomUUID().toString())
                    .setName(nestedSteps.name())
                    .setParallel(nestedSteps.parallel())
                    .build());
        });
        return wfStepsBuilder;
    }

    /**
     * Converts our CIXPipeline yaml-jackson object to proto
     *
     * @param defaultName if user doesn't specify a name in their parent pipeline yaml, then we set one for them
     * @return
     */
    @Override
    public WorkflowProtos.Workflow toWorkflowProto(Optional<String> defaultName) {
        WorkflowProtos.Workflow.Builder workflowBuilder = WorkflowProtos.Workflow.newBuilder();
        WorkflowProtos.Steps.Builder wfSteps = WorkflowProtos.Steps.newBuilder();
        assembleWorkflowSteps(pipeline(), wfSteps);
        if ((wfSteps.getName() == null || wfSteps.getName().isBlank()) && defaultName.isPresent()) {
            wfSteps.setName(defaultName.get());
        }
        return workflowBuilder.addSteps(wfSteps)
                .setType(getType())
                .setUuid(UUID.randomUUID().toString())
                .build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CIXPipeline that = (CIXPipeline) o;
        return Objects.equals(pipeline, that.pipeline);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pipeline);
    }

    @Override
    public String toString() {
        return "CIXPipeline{" +
                "pipeline=" + pipeline +
                '}';
    }

    public JsonObject toCixServerJson() {
        return new JsonObject().put("rawpipeline",
                this.rawPipeline.textValue());
    }
}
