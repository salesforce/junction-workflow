package com.salesforce.jw.steps.consumer;

import com.salesforce.jw.kafka.KafkaWorkflowLogsProducer;
import com.salesforce.jw.parsers.WorkflowInterface;
import com.salesforce.jw.steps.WorkflowProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

public class Workspace {
    private final static Logger log = LoggerFactory.getLogger(Workspace.class);

    private static String getGitSshUrl(WorkflowProtos.Workflow value) {
        return "https://%s/%s/%s.git".formatted(value.getVcs(), value.getOrganization(), value.getProject());
    }

    public static Path setupAndGetProjectPath(String key, KafkaWorkflowLogsProducer logsProducer, WorkflowProtos.Workflow workflow, WorkflowInterface wfInterface)
            throws IOException, ExecutionException, InterruptedException {
        String agentName = "Unknown Agent";
        try {
            agentName = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            throw new RuntimeException("Failed to get hostname", e);
        }

        Path workspacePath = Path.of("/tmp", key.replace(":", "-"));
        Path projectPath = Path.of(workspacePath.toString(), workflow.getProject());
        if (workspacePath.toFile().exists()
                && workspacePath.toFile().listFiles() != null
                && Objects.requireNonNull(workspacePath.toFile().listFiles()).length > 0) {
            // TODO: For production setups we'll want to wipe the ws before each run
            log.warn("Workspace [{}] already exists on agent [{}]. Attempting to re-use.", workspacePath, agentName);
        } else {
            try {
                Files.createDirectories(workspacePath);
                log.info("Created {}", workspacePath);
            } catch (IOException e) {
                throw new RuntimeException("Unable to create path: %s".formatted(workspacePath), e);
            }

            ShellCmd.run(key, workspacePath, logsProducer, "git", "clone", getGitSshUrl(workflow));
            ShellCmd.run(key, projectPath, logsProducer, "git", "checkout", workflow.getUuid());
            if (Files.notExists(Path.of(projectPath.toString(), wfInterface.getDeclarationFile()))) {
                throw new FileNotFoundException("There is no %s in this project, cannot execute".formatted(wfInterface.getDeclarationFile()));
            }
        }

        logsProducer.sendLog(key, "INFO: Running %s workflow on agent [%s]".formatted(wfInterface.name(), agentName));

        return projectPath;
    }

    public static void clean(String key) throws IOException {
        Path workspacePath = Path.of("/tmp", key.replace(":", "-"));
        if (Files.exists(workspacePath)) {
            Files.walk(workspacePath)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }
}
