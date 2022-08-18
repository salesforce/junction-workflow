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

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum WorkflowInterface {
    CIX(".cix.yaml"),
    TEKTON_PIPELINE("tekton-pipeline.yaml");

    private final String declarationFile;

    WorkflowInterface(String declarationFile) {
        this.declarationFile = declarationFile;
    }

    public String getDeclarationFile() {
        return declarationFile;
    }

    public static WorkflowInterface fromDeclarationFile(String declarationFile) {
        // TODO: We have these in workflow.type
        switch (declarationFile) {
            case ".cix.yaml" -> {
                return CIX;
            }
            case "tekton-pipeline.yaml" -> {
                return TEKTON_PIPELINE;
            }
        }
        return null;
    }

    public static Set<String> toSetOfFiles() {
        return Arrays.stream(values()).map(WorkflowInterface::getDeclarationFile).collect(Collectors.toSet());
    }
}
