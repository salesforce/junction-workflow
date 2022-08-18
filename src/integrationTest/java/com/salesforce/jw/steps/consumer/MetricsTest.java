package com.salesforce.jw.steps.consumer;

import com.github.charithe.kafka.EphemeralKafkaCluster;
import com.salesforce.jw.kafka.KafkaConfig;
import com.salesforce.jw.kafka.KafkaLatestStateProducer;
import com.salesforce.jw.steps.WorkflowProtos;
import com.salesforce.jw.steps.consumer.viz.MetricsPage;
import com.salesforce.jw.steps.producer.TestTopicManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MetricsTest {
    private static EphemeralKafkaCluster kafkaCluster;

    @BeforeAll
    public static void setup() throws Exception {
        kafkaCluster = TestTopicManager.setup();
    }

    @Test
    public void loadMetricsPageTest() throws Exception {
        KafkaLatestStateProducer producer = new KafkaLatestStateProducer();
        producer.sendWorkflowState("vcs:org:repo:branch:sha1234:1", WorkflowProtos.Workflow.newBuilder().setType(WorkflowProtos.Workflow.Type.TEKTON_PIPELINE).build());
        producer.sendWorkflowState("vcs:org:repo:branch:sha1234:2", WorkflowProtos.Workflow.newBuilder().setType(WorkflowProtos.Workflow.Type.TEKTON_PIPELINE).build());
        producer.sendWorkflowState("vcs:org:repo:branch:sha1234:3", WorkflowProtos.Workflow.newBuilder().setType(WorkflowProtos.Workflow.Type.TEKTON_PIPELINE).build());
        producer.sendWorkflowState("vcs:org:repo:branch2:sha1234:1", WorkflowProtos.Workflow.newBuilder().setType(WorkflowProtos.Workflow.Type.TEKTON_PIPELINE).build());
        assertTrue(new MetricsPage().render().contains("%s-0 : ".formatted(KafkaConfig.WORKFLOW_LATEST_STATE_TOPIC_NAME)));
        assertFalse(new MetricsPage().render().contains("%s-0 : ".formatted(KafkaConfig.WORKFLOW_LOGS_TOPIC_NAME)));

    }
}
