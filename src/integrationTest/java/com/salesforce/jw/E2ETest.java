/*
 *
 *  * Copyright (c) 2022, salesforce.com, inc.
 *  * All rights reserved.
 *  * Licensed under the BSD 3-Clause license.
 *  * For full license text, see LICENSE.txt file in the repo root or
 *  * https://opensource.org/licenses/BSD-3-Clause
 *
 */

package com.salesforce.jw;

import com.github.charithe.kafka.EphemeralKafkaCluster;
import com.salesforce.jw.steps.consumer.WorkExec;
import com.salesforce.jw.steps.consumer.viz.GraphvizVisualizer;
import com.salesforce.jw.steps.producer.ProcessWorkflow;
import com.salesforce.jw.steps.producer.TestTopicManager;
import com.salesforce.jw.steps.producer.VCSScanner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class E2ETest {
    private static EphemeralKafkaCluster kafkaCluster;

    @BeforeAll
    public static void setup() throws Exception {
        kafkaCluster = TestTopicManager.setup();

        // Clear state from tmp
        // TODO: Unhardcode workspace dir, which happens to be /tmp at the moment
        String workspaceNamespace = ProcessWorkflow.getHostFromUrl(System.getenv("JW_GH_URL"));
        ProcessBuilder rmBuilder = new ProcessBuilder()
                .command("bash", "-c", "rm -rf /tmp/%s*".formatted(workspaceNamespace))
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT);
        int result = rmBuilder.start().waitFor();
        if (result != 0) {
            fail("Failed to delete old state: %s".formatted(result));
        }
        assertEquals(Files.list(Paths.get("/tmp"))
                .filter(file -> file.getFileName().toString().startsWith(workspaceNamespace))
                .count(), 0, "We have state left over from previous run in '/tmp'");
    }

    // TODO: this test don't show exceptions stacktrace
    @Test
    public void testEndToEndInvoke() throws Exception {
        // Run once to seed with data
        VCSScanner vcsScanner = new VCSScanner();
        try {
            vcsScanner.execute(null);
        } catch (JobExecutionException e) {
            throw new RuntimeException(e.getUnderlyingException());
        }

        GraphvizVisualizer.clearCache();
        Executor graphvizExecutor = Executors.newSingleThreadExecutor();
        graphvizExecutor.execute(new GraphvizVisualizer());
        // TODO: Poll for result via http with timeout, then stop visualizer
        // TODO: Use selenium or a gold file to make sure the output is right

        Executor workerExecutor = Executors.newSingleThreadExecutor();
        workerExecutor.execute(new WorkExec());
        // TODO: Verify that state transitions are happening - would be cool to add a time machine feature to the page

        // TODO: It also check that we can read logs after these steps too

        // Run VCSScanner for a few minutes and cause changes
        try {
            Scheduler scheduler = null;
            JobDetail jobDetail = JobBuilder.newJob(VCSScanner.class).withIdentity("VCS Poller").build();
            Trigger trigger = TriggerBuilder.newTrigger()
                    .startNow()
                    .withSchedule(SimpleScheduleBuilder
                            .repeatMinutelyForever()
                            .withMisfireHandlingInstructionNowWithRemainingCount())
                    .build();
            try {
                scheduler = StdSchedulerFactory.getDefaultScheduler();
                scheduler.scheduleJob(jobDetail, trigger);
                scheduler.start();
                //scheduler.getTriggerState()
                // TODO: Have a configurable test timeout in addition to validations to stop the test early
                TimeUnit.MINUTES.sleep(5);
            } finally {
                if (scheduler != null) {
                    scheduler.shutdown();
                }
            }
        } catch (SchedulerException e) {
            throw new RuntimeException(e.getUnderlyingException());
        }

    }
}