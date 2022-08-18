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

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Pipeline(List<Step> stepList, List<Steps> stepsList) {
    // TODO: This doesn't actually work sadly - see the hacked up constructor in CIXPipeline
    @JsonCreator
    public Pipeline(@JsonProperty("step") List<Step> stepList, @JsonProperty("steps") List<Steps> stepsList) {
        this.stepList = stepList;
        this.stepsList = stepsList;
    }
}