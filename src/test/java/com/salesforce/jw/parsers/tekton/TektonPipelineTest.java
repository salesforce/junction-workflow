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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TektonPipelineTest {
    @Test
    void testLoadSimpleExample() throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        TektonPipeline tektonPipeline = mapper.readValue(
                ClassLoader.getSystemClassLoader().getResourceAsStream("tekton-examples/simple-tekton.yaml"), TektonPipeline.class);
        var tektonWorkflow = tektonPipeline.toWorkflowProto(Optional.empty());
        assertEquals(1, tektonWorkflow.getStepsList().size());
        assertEquals(2, tektonWorkflow.getStepsList().get(0).getStepsList().size());
        assertEquals("ping", tektonWorkflow.getStepsList().get(0).getStepsList().get(0).getName());
        assertEquals("pong", tektonWorkflow.getStepsList().get(0).getStepsList().get(1).getName());
        var ping = tektonWorkflow.getStepsList().get(0).getStepsList().get(0);
        var pong = tektonWorkflow.getStepsList().get(0).getStepsList().get(1);
        assertEquals(2, ping.getStepList().size());
        assertEquals(1, pong.getStepList().size());
        assertEquals("ping-and-sleep", ping.getStepList().get(0).getName());
        assertEquals("sequential-test", ping.getStepList().get(1).getName());
        assertEquals("pong-and-sleep", pong.getStepList().get(0).getName());
    }
}
