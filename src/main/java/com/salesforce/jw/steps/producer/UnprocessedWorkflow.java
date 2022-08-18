/*
 *
 *  * Copyright (c) 2022, salesforce.com, inc.
 *  * All rights reserved.
 *  * Licensed under the BSD 3-Clause license.
 *  * For full license text, see LICENSE.txt file in the repo root or
 *  * https://opensource.org/licenses/BSD-3-Clause
 *
 */

package com.salesforce.jw.steps.producer;

import java.util.concurrent.atomic.AtomicInteger;

public record UnprocessedWorkflow(String vcs, String org, String repo, String branch, String gitsha, Integer runId) {
    public UnprocessedWorkflow(String vcs, String org, String repo, String branch, String gitsha) {
        this(vcs, org, repo, branch, gitsha, UnprocessedWorkflow.getRunId(vcs, org, repo, branch, gitsha));
    }

    public String getKey() {
        var subKey = getSubKey(vcs, org, repo, branch, gitsha);
        return "%s:%s".formatted(subKey, runId);
    }

    /**
     * TODO: Need to remove these hacks - eventually we need to use another Kafka topic as per spec
     * @param vcs
     * @param org
     * @param repo
     * @param branch
     * @param gitsha
     * @return
     */
    private static String getSubKey(String vcs, String org, String repo, String branch, String gitsha) {
        return "%s:%s:%s:%s:%s".formatted(vcs, org, repo, branch, gitsha.substring(0, 6));
    }

    private static Integer getRunId(String vcs, String org, String repo, String branch, String gitsha) {
        var subKey = getSubKey(vcs, org, repo, branch, gitsha);
        var startRunId = 1;
        if (VCSScanner.nextRunIdMap.containsKey(subKey)) {
            // If this gets called on multiple VCSScanner machines, we'll have a duplicate key
            // This is OK because generally, we don't want duplicate workflows to get kicked off
            // Since the sha is in the subkey, people will still able to have a workflow for each change
            VCSScanner.nextRunIdMap.get(subKey).incrementAndGet();
        } else {
            VCSScanner.nextRunIdMap.put(subKey, new AtomicInteger(startRunId));
        }
        return VCSScanner.nextRunIdMap.get(subKey).get();
    }
}
