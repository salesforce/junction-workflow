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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.salesforce.jw.steps.WorkflowOperations;
import com.salesforce.jw.steps.WorkflowProtos;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CIXPipelineTest {
    @Test
    void testLoadSimpleExample() throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        CIXPipeline cixPipeline = mapper.readValue(
                ClassLoader.getSystemClassLoader().getResourceAsStream("cix-examples/simple-cix.yaml"), CIXPipeline.class);
        assertEquals(1, cixPipeline.pipeline().stepList().size());
        assertEquals("test", cixPipeline.pipeline().stepList().get(0).name());
    }

    @Test
    void testLoadStepAndStepsExample() throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        CIXPipeline cixPipeline = mapper.readValue(
                ClassLoader.getSystemClassLoader().getResourceAsStream("cix-examples/step-and-steps.yaml"), CIXPipeline.class);
        assertEquals(1, cixPipeline.pipeline().stepList().size());
        assertEquals(1, cixPipeline.pipeline().stepsList().size());
        assertEquals("nest", cixPipeline.pipeline().stepsList().get(0).name());
        assertFalse(cixPipeline.pipeline().stepsList().get(0).parallel());
        assertEquals(1, cixPipeline.pipeline().stepsList().get(0).pipeline().stepList().size());
        assertEquals("nested-test", cixPipeline.pipeline().stepsList().get(0).pipeline().stepList().get(0).name());
    }

    @Test
    void testLoadParallelStepsExample() throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        CIXPipeline cixPipeline = mapper.readValue(
                ClassLoader.getSystemClassLoader().getResourceAsStream("cix-examples/simple-parallel-steps.yaml"), CIXPipeline.class);
        assertEquals(0, cixPipeline.pipeline().stepList().size());
        assertEquals(1, cixPipeline.pipeline().stepsList().size());
        assertTrue(cixPipeline.pipeline().stepsList().get(0).parallel());
        assertEquals(2, cixPipeline.pipeline().stepsList().get(0).pipeline().stepList().size());
    }

    @Test
    void testPipelineStateJsonLoad() throws Exception {
        String cixPipelineDef = """
                version: 2.1
                pipeline:
                  - steps:
                      name: 1
                      pipeline:
                      - step:
                          name: 1-1
                          image: alpine:3.9
                          commands:
                            - echo 'steps 1 step 1 (synchronous)'
                            - sleep 2
                      - step:
                          name: 1-2
                          image: alpine:3.9
                          commands:
                            - echo 'steps 1 step 2 (synchronous)'
                            - sleep 3
                      - step:
                          name: 1-3
                          image: alpine:3.9
                          commands:
                            - echo 'steps 1 step 3 (synchronous)'
                  - step:
                      name: 2
                      image: alpine:3.9
                      commands:
                        - echo 'step 2 (synchronous)'
                  - steps:
                      name: 3
                      parallel: true ## if left out, will default to false
                      pipeline:
                      - step:
                          name: 3-1
                          image: alpine:3.9
                          commands:
                            - echo 'steps 3 step 1 (parallel)'
                            - sleep 1
                            - echo 'step 3-1 after sleep (parallel)'
                            - sleep 1
                            - echo 'step 3-1 after sleep (parallel)'
                      - step:
                          name: 3-2
                          image: alpine:3.9
                          commands:
                            - echo 'steps 3 step 2 (parallel)'
                            - sleep 1
                            - echo 'step 3-2 after sleep (parallel)'
                            - sleep 1
                            - echo 'step 3-2 after sleep (parallel)'
                      - step:
                          name: 3-3
                          image: alpine:3.9
                          commands:
                            - echo 'steps 3 step 3 (parallel)'
                            - sleep 1
                            - echo 'step 3-3 after sleep (parallel)'
                            - sleep 1
                            - echo 'step 3-3 after sleep (parallel)'
                """;
        String jsonWithStateAllReady = """
                    {"name":"root","steps":[{"name":"1","steps":[{"name":"1-1","status":"ready"},{"name":"1-2","status":"ready"},{"name":"1-3","status":"ready"}]},{"name":"2","status":"ready"},{"name":"3","steps":[{"name":"3-1","status":"ready"},{"name":"3-2","status":"ready"},{"name":"3-3","status":"ready"}],"parallel":true}]}
                """;
        String jsonWithMixedStates = """
                    {"name":"root","steps":[{"name":"1","steps":[{"name":"1-1","status":"successful"},{"name":"1-2","status":"running"},{"name":"1-3","status":"ready"}]},{"name":"2","status":"ready"},{"name":"3","steps":[{"name":"3-1","status":"ready"},{"name":"3-2","status":"ready"},{"name":"3-3","status":"ready"}],"parallel":true}]}
                                
                """;
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        CIXPipeline cixPipelineNoState = yamlMapper.readValue(cixPipelineDef, CIXPipeline.class);
        ObjectMapper jsonMapper = new ObjectMapper(new JsonFactory());
        CIXPipeline cixPipelineAllState = jsonMapper.readValue(jsonWithStateAllReady, CIXPipeline.class);
        WorkflowProtos.Workflow initialFromYaml = cixPipelineNoState.toWorkflowProto(Optional.of("testName"));
        WorkflowProtos.Workflow initialFromJson = cixPipelineAllState.toWorkflowProto(Optional.of("testName"));
        assertEquals(initialFromYaml.getStepsCount(), initialFromJson.getStepsCount());
        for (int i = 0; i < initialFromYaml.getStepsCount(); i++) {
            assertEquals(initialFromYaml.getSteps(i).getStepsCount(), initialFromJson.getSteps(i).getStepsCount());
        }

        WorkflowOperations workflowOperations = new WorkflowOperations();
        assertEquals(WorkflowProtos.Step.State.NOT_STARTED, workflowOperations.lastReportedState(initialFromYaml));
        assertEquals(WorkflowProtos.Step.State.NOT_STARTED, workflowOperations.getLifecycleState(initialFromYaml));
        assertEquals(WorkflowProtos.Step.State.NOT_STARTED, workflowOperations.lastReportedState(initialFromJson));
        assertEquals(WorkflowProtos.Step.State.NOT_STARTED, workflowOperations.getLifecycleState(initialFromJson));


        WorkflowProtos.Workflow mixedStateWorkflow = jsonMapper.readValue(jsonWithMixedStates, CIXPipeline.class)
                .toWorkflowProto(Optional.of("testName"));
        assertEquals(1, workflowOperations.stepsContainState(mixedStateWorkflow, WorkflowProtos.Step.State.COMPLETED).size());
        assertEquals(1, workflowOperations.stepsContainState(mixedStateWorkflow, WorkflowProtos.Step.State.RUNNING).size());
        assertEquals(5, workflowOperations.stepsContainState(mixedStateWorkflow, WorkflowProtos.Step.State.NOT_STARTED).size());

        // TODO: Flaps
        //assertEquals(WorkflowProtos.Step.State.NOT_STARTED, workflowOperations.lastReportedState(mixedStateWorkflow));
    }
}