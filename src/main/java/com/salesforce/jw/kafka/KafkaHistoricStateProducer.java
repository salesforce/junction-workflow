/*
 *
 *  * Copyright (c) 2022, salesforce.com, inc.
 *  * All rights reserved.
 *  * Licensed under the BSD 3-Clause license.
 *  * For full license text, see LICENSE.txt file in the repo root or
 *  * https://opensource.org/licenses/BSD-3-Clause
 *
 */

package com.salesforce.jw.kafka;

import com.salesforce.jw.steps.WorkflowProtos;

import java.util.concurrent.ExecutionException;

import static com.salesforce.jw.kafka.KafkaConfig.WORKFLOW_HISTORIC_STATE_TOPIC_NAME;

public class KafkaHistoricStateProducer {
    public void sendWorkflowState(String gitRepoBranch, WorkflowProtos.Workflow workflow) throws
            ExecutionException, InterruptedException {
        new KafkaOps().sendWorkflowState(gitRepoBranch, workflow, WORKFLOW_HISTORIC_STATE_TOPIC_NAME);
    }
}
