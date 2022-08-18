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

import static com.salesforce.jw.kafka.KafkaConfig.WORKFLOW_LATEST_STATE_TOPIC_NAME;

public class KafkaLatestStateProducer {
    public long sendWorkflowState(String wfKey, WorkflowProtos.Workflow workflow) throws
            ExecutionException, InterruptedException {
        return new KafkaOps().sendWorkflowState(wfKey, workflow, WORKFLOW_LATEST_STATE_TOPIC_NAME);
    }
}
