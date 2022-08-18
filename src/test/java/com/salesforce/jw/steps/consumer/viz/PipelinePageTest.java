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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.salesforce.jw.kafka.KafkaWorkflowLogsProducer;
import com.salesforce.jw.parsers.cix.CIXPipeline;
import com.salesforce.jw.parsers.tekton.TektonPipeline;
import com.salesforce.jw.steps.consumer.ShellCmd;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.salesforce.jw.steps.WorkflowProtos.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class PipelinePageTest {
    private static Stream<Arguments> protoToHtmlExpected() throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        return Stream.of(
                Arguments.of(Workflow.newBuilder()
                        .setVcs("github").setOrganization("afalko-demo2").setProject("test").setBranch("main").setUuid("a")
                        .addSteps(Steps.newBuilder().setName("start-with-parallel-steps").setUuid("b").setParallel(true)
                                .addStep(Step.newBuilder().setUuid("c").setName("step-1").build())
                                .addStep(Step.newBuilder().setUuid("d").setName("step-2").build())
                                .addStep(Step.newBuilder().setUuid("e").setName("step-3").build())
                                .build())
                        .addSteps(Steps.newBuilder().setUuid("f").setName("start-with-step-and-group").setParallel(false)
                                .addStep(Step.newBuilder().setUuid("g").setName("sub-step-1").build())
                                .addSteps(Steps.newBuilder().setUuid("h").setName("nested-steps").setParallel(true)
                                        .addStep(Step.newBuilder().setUuid("i").setName("nested-step-1").build())
                                        .addStep(Step.newBuilder().setUuid("j").setName("nested-step-2").build())
                                        .addStep(Step.newBuilder().setUuid("k").setName("nested-step-3").build())
                                        .build())
                                .addStep(Step.newBuilder().setUuid("l").setName("step-3").build())
                                .build())
                        .build(), "cix-pipeline.html"),
                Arguments.of(mapper.readValue(ClassLoader.getSystemResourceAsStream("cix-examples/cix-from-demo.yaml"), CIXPipeline.class)
                        .toWorkflowProto(Optional.empty()), "cix-pipeline-from-demo.html"),
                Arguments.of(mapper.readValue(ClassLoader.getSystemResourceAsStream("tekton-examples/simple-tekton.yaml"), TektonPipeline.class)
                        .toWorkflowProto(Optional.empty()), "simple-tekton.html"),
                Arguments.of(Workflow.newBuilder()
                        .setVcs("all").setOrganization("should").setProject("be").setBranch("complete").setUuid("a")
                        .addSteps(Steps.newBuilder().setName("start-with-parallel-steps").setUuid("b").setParallel(true)
                                .addStep(Step.newBuilder().setUuid("c").setName("step-1").setState(Step.State.COMPLETED).build())
                                .addStep(Step.newBuilder().setUuid("d").setName("step-2").setState(Step.State.COMPLETED).build())
                                .addStep(Step.newBuilder().setUuid("e").setName("step-3").setState(Step.State.COMPLETED).build())
                                .build())
                        .addSteps(Steps.newBuilder().setUuid("f").setName("start-with-step-and-group").setParallel(false)
                                .addStep(Step.newBuilder().setUuid("g").setName("sub-step-1").setState(Step.State.COMPLETED).build())
                                .addSteps(Steps.newBuilder().setUuid("h").setName("nested-steps").setParallel(true)
                                        .addStep(Step.newBuilder().setUuid("i").setName("nested-step-1").setState(Step.State.COMPLETED).build())
                                        .addStep(Step.newBuilder().setUuid("j").setName("nested-step-2").setState(Step.State.COMPLETED).build())
                                        .addStep(Step.newBuilder().setUuid("k").setName("nested-step-3").setState(Step.State.COMPLETED).build())
                                        .build())
                                .addStep(Step.newBuilder().setUuid("l").setName("step-3").setState(Step.State.COMPLETED).build())
                                .build())
                        .addSteps(Steps.newBuilder().setUuid("m").setName("another-steps").setParallel(true)
                                .addSteps(Steps.newBuilder().setUuid("n").setName("more-nested-steps-1").setParallel(false)
                                        .addStep(Step.newBuilder().setUuid("o").setName("nested-step-1").setState(Step.State.COMPLETED).build())
                                        .addStep(Step.newBuilder().setUuid("p").setName("nested-step-2").setState(Step.State.COMPLETED).build())
                                        .build())
                                .addSteps(Steps.newBuilder().setUuid("q").setName("more-nested-steps-2").setParallel(false)
                                        .addStep(Step.newBuilder().setUuid("r").setName("nested-step-1").setState(Step.State.COMPLETED).build())
                                        .addStep(Step.newBuilder().setUuid("s").setName("nested-step-2").setState(Step.State.COMPLETED).build())
                                        .build())
                                .build())
                        .build(), "all-complete-pipeline.html")
        );
    }

    @ParameterizedTest
    @MethodSource("protoToHtmlExpected")
    public void testRenderWithMixedProto(Workflow exampleProto, String htmlExample) throws Exception {
        var mockKafkaLogProducer = mock(KafkaWorkflowLogsProducer.class);
        AtomicReference<String> diffResult = new AtomicReference<>();
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                diffResult.set("Something is different; run diff to see for yourself: %s".formatted(invocation));
                return null;
            }
        }).when(mockKafkaLogProducer).sendLog(anyString(), anyString());

        PipelinePage pipelinePage = new PipelinePage();
        GraphvizVisualizer.workflowsCache.put("github:afalko-demo2:test:main:1234567:1", exampleProto);
        String result = pipelinePage.render("github", "afalko-demo2", "test", "main", "1234567", "1");
        result = result.replaceAll("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"
                .replaceAll("}-\\[", "}&#45;["), "");
        var tempDir = Files.createTempDirectory(PipelinePageTest.class.getSimpleName());
        Files.write(Paths.get(tempDir.toString(), htmlExample), result.getBytes(), StandardOpenOption.CREATE);
        assertEquals(0, ShellCmd.run("somekey", tempDir.toAbsolutePath(), mockKafkaLogProducer,
                "diff", "-du", Paths.get(tempDir.toString(), htmlExample).toString(),
                ClassLoader.getSystemResource("viz/%s".formatted(htmlExample)).getPath()),
                diffResult.get());

    }

}