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

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProcessWorkflowTest {

    @Test
    void readCixPipelineConfigAndGenerateNestedStepsRecord() throws Exception {
        var workflow = new ProcessWorkflow(null, null).readPipelineConfigAndGenerateNestedStepsRecord(".cix.yaml",
                "https://raw.githubusercontent.com/afalko-demo/cix-test-repo/main/.cix.yaml",
                UUID.randomUUID().toString(),
                "github.com", "afalko-demo", "cix-test-repo", "main");
        assertNotNull(workflow);
    }

    @Test
    void readTektonPipelineConfigAndGenerateNestedStepsRecord() throws Exception {
        var workflow = new ProcessWorkflow(null, null).readPipelineConfigAndGenerateNestedStepsRecord("tekton-pipeline.yaml",
                "https://raw.githubusercontent.com/afalko-demo/tekton-demo/main/tekton-pipeline.yaml",
                UUID.randomUUID().toString(),
                "github.com", "afalko-demo", "tekton-demo", "main");
        assertNotNull(workflow);
    }

}