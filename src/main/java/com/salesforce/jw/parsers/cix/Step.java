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

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Step(String name, String status) {
    @JsonCreator
    public Step(@JsonProperty(value = "name", required = true) String name,
                @JsonProperty(value = "status", defaultValue = "ready") String status) {
        this.name = name;
        this.status = Objects.requireNonNullElse(status, "ready");
    }
}
