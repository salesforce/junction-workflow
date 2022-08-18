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

import java.util.List;
import java.util.Objects;

public record TaskSpec(List<Step> steps) {
    // TODO: Support optional params listed here: https://github.com/tektoncd/pipeline/blob/main/docs/tasks.md
    @JsonCreator
    public TaskSpec(@JsonProperty("steps") List<Step> steps) {
        this.steps = steps;
    }
}
