package com.salesforce.jw.k8s;

public enum PodPhase {
    FAILED,
    PENDING,
    RUNNING,
    SUCCEEDED,
    UNKNOWN;

    public PodPhase fromString(String strPhase) {
        switch (strPhase) {
            case "Failed" -> { return FAILED; }
            case "Pending" -> { return PENDING; }
            case "Running" -> { return RUNNING; }
            case "Succeeded" -> { return SUCCEEDED; }
            default -> { return UNKNOWN; }
        }
    }
}
