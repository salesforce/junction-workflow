package com.salesforce.jw.steps.consumer;

import com.github.charithe.kafka.EphemeralKafkaCluster;
import com.salesforce.jw.kafka.KafkaLatestStateProducer;
import com.salesforce.jw.steps.WorkflowProtos;
import com.salesforce.jw.steps.consumer.viz.GraphvizVisualizer;
import com.salesforce.jw.steps.producer.TestTopicManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class GraphvizTest {
    private static EphemeralKafkaCluster kafkaCluster;

    @BeforeAll
    public static void setup() throws Exception {
        kafkaCluster = TestTopicManager.setup();
    }

    @Test
    public void loadMainPageTest() throws Exception {
        KafkaLatestStateProducer producer = new KafkaLatestStateProducer();
        producer.sendWorkflowState("vcs:org:repo:branch:sha1234:1", WorkflowProtos.Workflow.newBuilder().setType(WorkflowProtos.Workflow.Type.TEKTON_PIPELINE).build());
        producer.sendWorkflowState("vcs:org:repo:branch:sha1234:2", WorkflowProtos.Workflow.newBuilder().setType(WorkflowProtos.Workflow.Type.TEKTON_PIPELINE).build());
        producer.sendWorkflowState("vcs:org:repo:branch:sha1234:3", WorkflowProtos.Workflow.newBuilder().setType(WorkflowProtos.Workflow.Type.TEKTON_PIPELINE).build());
        producer.sendWorkflowState("vcs:org:repo:branch2:sha1234:1", WorkflowProtos.Workflow.newBuilder().setType(WorkflowProtos.Workflow.Type.TEKTON_PIPELINE).build());
        GraphvizVisualizer graphvizVisualizer = new GraphvizVisualizer(TimeUnit.SECONDS.toSeconds(10));
        var service = Executors.newSingleThreadExecutor().submit(graphvizVisualizer);
        while (GraphvizVisualizer.workflowsCache.size() < 4) {
            Thread.sleep(100);
        }
        assertEquals(4, GraphvizVisualizer.workflowsCache.size());
        producer.sendWorkflowState("vcs:org:repo:branch2:sha4321:1", WorkflowProtos.Workflow.newBuilder().setType(WorkflowProtos.Workflow.Type.TEKTON_PIPELINE).build());
        service.get();
        assertEquals(5, GraphvizVisualizer.workflowsCache.size());

    }
}
