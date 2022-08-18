package com.salesforce.jw.steps.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.salesforce.jw.kafka.KafkaHistoricStateProducer;
import com.salesforce.jw.kafka.KafkaLatestStateConsumer;
import com.salesforce.jw.kafka.KafkaLatestStateProducer;
import com.salesforce.jw.kafka.KafkaWorkflowLogsProducer;
import com.salesforce.jw.parsers.WorkflowInterface;
import com.salesforce.jw.parsers.tekton.TektonPipeline;
import com.salesforce.jw.steps.WorkflowOperations;
import com.salesforce.jw.steps.WorkflowProtos;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesApi;
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesObject;
import io.kubernetes.client.util.generic.options.CreateOptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doAnswer;

class TektonPipelineRunWorkerTest {
    private static Logger log = LoggerFactory.getLogger(TektonPipelineRunWorkerTest.class);

    @Test
    public void testCreateAndRunPipeline() throws Exception {
        var mockProducer = Mockito.mock(KafkaWorkflowLogsProducer.class);
        doAnswer(invocation -> {
            log.info("Invocation: {}", invocation);
            return null;
        }).when(mockProducer).sendLog(anyString(), anyString());

        var mockLatestStateProducer = Mockito.mock(KafkaLatestStateProducer.class);
        doAnswer(invocation -> {
            log.trace("Invocation: {}", invocation);
            return null;
        }).when(mockLatestStateProducer).sendWorkflowState(anyString(), any(WorkflowProtos.Workflow.class));

        var mockHistoricStateProducer = Mockito.mock(KafkaHistoricStateProducer.class);
        doAnswer(invocation -> {
            log.trace("Invocation: {}", invocation);
            return null;
        }).when(mockHistoricStateProducer).sendWorkflowState(anyString(), any(WorkflowProtos.Workflow.class));

        var mockLatestStateConsumer = Mockito.mock(KafkaLatestStateConsumer.class);
        doAnswer(invocation -> {
            log.trace("Invocation: {}", invocation);
            return null;
        }).when(mockLatestStateConsumer).commit();

        var key = UUID.randomUUID().toString();
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        TektonPipeline tektonPipeline = mapper.readValue(
                ClassLoader.getSystemClassLoader().getResourceAsStream("tekton-examples/tekton-pipeline.yaml"), TektonPipeline.class);
        var tektonWorkflow = tektonPipeline.toWorkflowProto(Optional.empty());

        var worker = new TektonPipelineRunWorker(key, tektonWorkflow,
                mockHistoricStateProducer, mockLatestStateProducer, mockProducer, new WorkflowOperations());
        Path projectPath = Paths.get(ClassLoader.getSystemClassLoader().getResource("tekton-examples/tekton-pipeline.yaml").getPath()).getParent();
        worker.setupK8sClient();
        var ns = worker.createNamespace(key);
        worker.applyAndRunPipeline(projectPath, "pipename123", key);
        worker.pollPodsTillPodsCompleteOrTimeout(key);
        worker.deleteNamespace(ns);

        // TODO: Assert that every node got marked COMPLETED
    }

    @Test
    @Disabled // Api needs a tekton extention for us to be productive
    public void testCheckK8sApi() throws Exception {
        String key = UUID.randomUUID().toString();
        Path projectPath = Paths.get(ClassLoader.getSystemClassLoader().getResource("tekton-examples/tekton-pipeline.yaml").getPath());

        ApiClient k8sClient = null;
        try {
            k8sClient = Config.defaultClient();
        } catch (IOException e) {
            throw new RuntimeException("Failed to configure k8s client", e);
        }
        Configuration.setDefaultApiClient(k8sClient);

        CoreV1Api api = new CoreV1Api();
        //V1Namespace namespaceForRun = new V1Namespace().metadata(new V1ObjectMeta().namespace(key));
        //api.createNamespace(namespaceForRun, "false", "false", null);

        DynamicKubernetesApi dynamicKubernetesApi = new DynamicKubernetesApi("", "v1", "namespaces", k8sClient);
        var pipelineFile = Paths.get(projectPath.getParent().toString(), WorkflowInterface.TEKTON_PIPELINE.getDeclarationFile());
        DynamicKubernetesObject tektonPipeline = new DynamicKubernetesObject(JsonParser.parseString(new GsonBuilder().create().toJson(Files.readString(pipelineFile))).getAsJsonObject());
        dynamicKubernetesApi.create(key, tektonPipeline, new CreateOptions());
    }
}