/*
 *
 *  * Copyright (c) 2022, salesforce.com, inc.
 *  * All rights reserved.
 *  * Licensed under the BSD 3-Clause license.
 *  * For full license text, see LICENSE.txt file in the repo root or
 *  * https://opensource.org/licenses/BSD-3-Clause
 *
 */

package com.salesforce.jw.parsers;

import com.salesforce.jw.steps.WorkflowProtos;

import java.util.Optional;

public interface PipelineParser {
    WorkflowProtos.Workflow.Type getType();
    WorkflowProtos.Workflow toWorkflowProto(Optional<String> defaultName);
}
