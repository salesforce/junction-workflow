package com.salesforce.jw.steps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.salesforce.jw.parsers.PipelineParser;
import com.salesforce.jw.parsers.cix.CIXPipeline;
import com.salesforce.jw.parsers.tekton.TektonPipeline;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowOperationsTest {
    private final static Logger log = LoggerFactory.getLogger(WorkflowOperationsTest.class);

    private static Stream<Arguments> fromSamples() {
        return Stream.of(
                Arguments.of("cix-examples/simple-cix.yaml", CIXPipeline.class, "test", WorkflowProtos.Step.State.COMPLETED),
                Arguments.of("tekton-examples/simple-tekton.yaml", TektonPipeline.class, "ping-and-sleep", WorkflowProtos.Step.State.NOT_STARTED),
                Arguments.of("tekton-examples/simple-tekton.yaml", TektonPipeline.class, "pong-and-sleep", WorkflowProtos.Step.State.NOT_STARTED)
        );
    }

    @ParameterizedTest
    @MethodSource("fromSamples")
    public void testLastReportedState(String sample, Class<PipelineParser> clazz, String updateStepState) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        PipelineParser pipeline = mapper.readValue(
                ClassLoader.getSystemClassLoader().getResourceAsStream(sample), clazz);
        WorkflowOperations workflowOperations = new WorkflowOperations();
        assertEquals(WorkflowProtos.Step.State.NOT_STARTED,
                workflowOperations.lastReportedState(pipeline.toWorkflowProto(Optional.empty())));
    }

    @ParameterizedTest
    @MethodSource("fromSamples")
    public void testUpdateState(String sample, Class<PipelineParser> clazz, String updateStepState, WorkflowProtos.Step.State expectedLifecycleState) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        PipelineParser pipeline = mapper.readValue(
                ClassLoader.getSystemClassLoader().getResourceAsStream(sample), clazz);

        var builder = pipeline.toWorkflowProto(Optional.empty()).toBuilder();
        Thread.sleep(1); // Sleep to allow wf operations to set a slightly later timestamp
        WorkflowOperations workflowOperations = new WorkflowOperations();
        var completed = workflowOperations.updateStepStateByStepName(builder, updateStepState, WorkflowProtos.Step.State.COMPLETED);
        assertEquals(WorkflowProtos.Step.State.COMPLETED, workflowOperations.lastReportedState(completed), "Expected step %s to be COMPLETED: %s".formatted(updateStepState, completed));
        assertEquals(expectedLifecycleState, workflowOperations.getLifecycleState(completed), "Expected lifecycle state to be %s: %s".formatted(expectedLifecycleState, completed));
    }

    @ParameterizedTest
    @MethodSource("fromSamples")
    public void testGetCreationTime(String sample, Class<PipelineParser> clazz, String updateStepState, WorkflowProtos.Step.State expectedLifecycleState) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        PipelineParser pipeline = mapper.readValue(
                ClassLoader.getSystemClassLoader().getResourceAsStream(sample), clazz);

        var builder = pipeline.toWorkflowProto(Optional.empty()).toBuilder();
        Thread.sleep(1); // Sleep to allow wf operations to set a slightly later timestamp
        WorkflowOperations workflowOperations = new WorkflowOperations();
        var createTime = workflowOperations.getCreationTimeMs(builder.build());
        var now = System.currentTimeMillis();
        assertTrue(createTime < System.currentTimeMillis(), "Expected create time %s to be newer than now %s".formatted(createTime, now));
    }

    @ParameterizedTest
    @MethodSource("fromSamples")
    public void testMaxRetries(String sample, Class<PipelineParser> clazz, String updateStepState, WorkflowProtos.Step.State expectedLifecycleState) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        PipelineParser pipeline = mapper.readValue(
                ClassLoader.getSystemClassLoader().getResourceAsStream(sample), clazz);

        var wf = pipeline.toWorkflowProto(Optional.empty());
        WorkflowOperations workflowOperations = new WorkflowOperations();
        var retryCount = workflowOperations.getMaxRetryCount(wf);
        assertEquals(0, retryCount);
        var retriesPlusOne = workflowOperations.updateForRetry(wf);
        log.info("{}", retriesPlusOne);
        var retryCountAfterUpdate = workflowOperations.getMaxRetryCount(retriesPlusOne);
        assertEquals(1, retryCountAfterUpdate);
    }
}