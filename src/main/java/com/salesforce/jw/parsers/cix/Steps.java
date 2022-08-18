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
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
// TODO: If we didn't have to map JsonNode pipeline to Pipeline, we can make this a record instead of class
public final class Steps {
    final static boolean DEFAULT_PARALLEL_SETTING = false;

    private final String name;
    private final Boolean parallel;
    private final Pipeline pipeline;

    @JsonCreator
    public Steps(@JsonProperty(value = "name", required = true) String name,
                 @JsonProperty("parallel") Boolean parallel,
                 @JsonProperty("pipeline") JsonNode pipeline) {
        this.pipeline = new CIXPipeline(pipeline, null, "").pipeline();
        this.parallel = parallel;
        this.name = name;
    }

    public Steps(String name,
                 Boolean parallel,
                 List<Step> stepList,
                 List<Steps> stepsList) {
        this.pipeline = new Pipeline(stepList, stepsList);
        this.parallel = parallel;
        this.name = name;
    }

    public String name() {
        return name;
    }

    public Boolean parallel() {
        return parallel;
    }

    public Pipeline pipeline() {
        return pipeline;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (Steps) obj;
        return Objects.equals(this.name, that.name) &&
                Objects.equals(this.parallel, that.parallel) &&
                Objects.equals(this.pipeline, that.pipeline);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, parallel, pipeline);
    }

    @Override
    public String toString() {
        return "Steps[" +
                "name=" + name + ", " +
                "parallel=" + parallel + ", " +
                "pipeline=" + pipeline + ']';
    }

}
