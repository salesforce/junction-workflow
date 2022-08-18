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
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Step(AtomicReference<String> name, String image, String script) {
    // TODO: Lots more to support: https://tekton.dev/docs/pipelines/tasks/#defining-steps
    // TODO: Would be great to generate something like this: https://github.com/fabric8io/kubernetes-client/blob/master/extensions/tekton/model-v1beta1/src/generated/java/io/fabric8/tekton/pipeline/v1beta1/Step.java
    @JsonCreator
    public Step(Map<String, Object> properties) {
        this(new AtomicReference<>(), (String)properties.get("image"), (String)properties.get("script"));
        name.set(extractNameFromProps(properties));
    }

    private String extractNameFromProps(Map<String, Object> props) {
        if (props.containsKey("name") && props.get("name") instanceof String) {
            return (String)props.get("name");
        } else {
            return "%s...".formatted(script.substring(0, Math.min(script.length(), 18)));
        }
    }

    public String getName() {
        return name.get();
    }
}