/*
 *
 *  * Copyright (c) 2022, salesforce.com, inc.
 *  * All rights reserved.
 *  * Licensed under the BSD 3-Clause license.
 *  * For full license text, see LICENSE.txt file in the repo root or
 *  * https://opensource.org/licenses/BSD-3-Clause
 *
 */

package com.salesforce.jw.steps.consumer;

import com.salesforce.jw.kafka.KafkaWorkflowLogsProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Scanner;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ShellCmd {
    private final static Logger log = LoggerFactory.getLogger(ShellCmd.class);

    private static void writeLogs(boolean onlyPrintOne, String logKey, KafkaWorkflowLogsProducer logsProducer,
                           Scanner stdoutReader, Scanner stderrReader)
            throws ExecutionException, InterruptedException {
        while (stderrReader.hasNextLine() || stdoutReader.hasNextLine()) {
            if (stderrReader.hasNextLine()) {
                String logLine = stderrReader.nextLine();
                log.info("STDERR key:{} {}", logKey, logLine);
                logsProducer.sendLog(logKey, "STDERR: %s".formatted(logLine));
            }

            if (stdoutReader.hasNextLine()) {
                String logLine = stdoutReader.nextLine();
                log.info("STDOUT key:{} {}", logKey, logLine);
                logsProducer.sendLog(logKey, "STDOUT: %s".formatted(logLine));
            }

            if (onlyPrintOne) {
                break;
            }
        }
        log.info("Stopped following input streams");
    }

    public static ProcessMetadata runAsync(String logKey, Path workspacePath, KafkaWorkflowLogsProducer logsProducer, String... commands) {
        return runAsync(logKey, workspacePath, logsProducer, Duration.of(1, ChronoUnit.MINUTES), commands);
    }

    public static ProcessMetadata runAsync(String logKey, Path workspacePath, KafkaWorkflowLogsProducer logsProducer, Duration timeout, String... commands) {
        ExecutorService singleThread = Executors.newSingleThreadExecutor();
        AtomicLong childPid = new AtomicLong();
        return new ProcessMetadata(singleThread, childPid.get(), singleThread.submit(() -> {
            try {
                run(childPid, logKey, workspacePath, logsProducer, timeout, commands);
            } catch (IOException | InterruptedException | ExecutionException e) {
                log.error("Failed to run shell %s".formatted(String.join(" ", commands)), e);
            }
        }));
    }

    public static int run(String logKey, Path workspacePath, KafkaWorkflowLogsProducer logsProducer, String... commands)
            throws IOException, ExecutionException, InterruptedException {
        return run(new AtomicLong(), logKey, workspacePath, logsProducer, Duration.of(1, ChronoUnit.MINUTES), true, commands);
    }

    public static int run(AtomicLong childPid, String logKey, Path workspacePath, KafkaWorkflowLogsProducer logsProducer,
                           Duration timeout, String... commands)
            throws IOException, ExecutionException, InterruptedException {
        return run(childPid, logKey, workspacePath, logsProducer, timeout, true, commands);
    }

    public static int run(String logKey, Path workspacePath, KafkaWorkflowLogsProducer logsProducer,
                           Duration timeout, String... commands)
            throws IOException, ExecutionException, InterruptedException {
        return run(new AtomicLong(), logKey, workspacePath, logsProducer, timeout, true, commands);
    }

    public static int run(AtomicLong childPid, String logKey, Path workspacePath, KafkaWorkflowLogsProducer logsProducer,
                           Duration timeout, boolean exitOnFail, String... commands)
            throws IOException, ExecutionException, InterruptedException {
        log.info("Running {} in {}", String.join(" ", commands), workspacePath.toString());
        logsProducer.sendLog(logKey, "+ %s".formatted(String.join(" ", commands)));
        ProcessBuilder processBuilder = new ProcessBuilder()
                .directory(workspacePath.toFile())
                .command(commands)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE);

        Process proc = processBuilder.start();
        childPid.set(proc.pid());
        try (Scanner stdoutReader = new Scanner(proc.getInputStream());
             Scanner stderrReader = new Scanner(proc.getErrorStream())) {
            while (proc.isAlive()) {
                if (stderrReader.hasNextLine()) {
                    String logLine = stderrReader.nextLine();
                    log.info("STDERR key:{} {}", logKey, logLine);
                    logsProducer.sendLog(logKey, "STDERR: %s".formatted(logLine));
                }

                if (stdoutReader.hasNextLine()) {
                    String logLine = stdoutReader.nextLine();
                    log.info("STDOUT key:{} {}", logKey, logLine);
                    logsProducer.sendLog(logKey, "STDOUT: %s".formatted(logLine));
                }
            }
        }
        return proc.exitValue();
    }

    record ProcessMetadata(ExecutorService executor, long childPid, Future<?> future) {}
}
