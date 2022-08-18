package com.salesforce.jw.steps.kafka;

import com.github.charithe.kafka.EphemeralKafkaCluster;
import com.salesforce.jw.kafka.KafkaLatestStateConsumer;
import com.salesforce.jw.kafka.KafkaLatestStateProducer;
import com.salesforce.jw.steps.WorkflowOperations;
import com.salesforce.jw.steps.WorkflowProtos;
import com.salesforce.jw.steps.producer.TestTopicManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class KafkaE2ETest {
    private static Logger log = LoggerFactory.getLogger(KafkaE2ETest.class);

    private static EphemeralKafkaCluster kafkaCluster;

    @BeforeAll
    public static void setup() throws Exception {
        kafkaCluster = EphemeralKafkaCluster.create(1);

        long maxWaitMillis = TimeUnit.MINUTES.toMillis(2);
        for (long wait = 0; wait < maxWaitMillis; wait += TimeUnit.SECONDS.toMillis(1)) {
            if (kafkaCluster.isRunning() && kafkaCluster.isHealthy()) {
                break;
            } else {
                TimeUnit.SECONDS.sleep(1);
            }
        }
        Assertions.assertTrue(kafkaCluster.isHealthy(), "Cluster is not healthy");

        System.setProperty("bootstrap.servers", "%s".formatted(
                kafkaCluster.getBrokers().get(0).getBrokerList().get()));

        TestTopicManager.createTopics(kafkaCluster);
    }

    @Test
    public void produceAndConsumeAllTest() throws Exception {
        KafkaLatestStateProducer producer = new KafkaLatestStateProducer();
        producer.sendWorkflowState("vcs:org:repo:branch:sha1234:1", WorkflowProtos.Workflow.newBuilder().setType(WorkflowProtos.Workflow.Type.TEKTON_PIPELINE).build());
        producer.sendWorkflowState("vcs:org:repo:branch:sha1234:2", WorkflowProtos.Workflow.newBuilder().setType(WorkflowProtos.Workflow.Type.TEKTON_PIPELINE).build());
        producer.sendWorkflowState("vcs:org:repo:branch:sha1234:3", WorkflowProtos.Workflow.newBuilder().setType(WorkflowProtos.Workflow.Type.TEKTON_PIPELINE).build());

        try (KafkaLatestStateConsumer consumer = new KafkaLatestStateConsumer()) {
            var actualMap = consumer.getAllFromStart();
            assertEquals(3, actualMap.size());
        }
    }

    @Test
    public void setCurrentRunIdTest() throws Exception {
        KafkaLatestStateProducer producer = new KafkaLatestStateProducer();
        producer.sendWorkflowState("vcs:org:repo:branch:sha1234:1", WorkflowProtos.Workflow.newBuilder().setType(WorkflowProtos.Workflow.Type.TEKTON_PIPELINE).build());
        producer.sendWorkflowState("vcs:org:repo:branch:sha1234:2", WorkflowProtos.Workflow.newBuilder().setType(WorkflowProtos.Workflow.Type.TEKTON_PIPELINE).build());
        producer.sendWorkflowState("vcs:org:repo:branch:sha1234:3", WorkflowProtos.Workflow.newBuilder().setType(WorkflowProtos.Workflow.Type.TEKTON_PIPELINE).build());
        producer.sendWorkflowState("vcs:org:repo:branch:shaabcd:1", WorkflowProtos.Workflow.newBuilder().setType(WorkflowProtos.Workflow.Type.TEKTON_PIPELINE).build());
        producer.sendWorkflowState("vcs:org:repo:branch:shaabcd:2", WorkflowProtos.Workflow.newBuilder().setType(WorkflowProtos.Workflow.Type.TEKTON_PIPELINE).build());

        WorkflowOperations workflowOperations = new WorkflowOperations();
        var result = workflowOperations.getCurrentRunIdMap();
        assertEquals(2, result.size());
        log.info("Result map: {}", result);
        assertEquals(3, result.get("vcs:org:repo:branch:sha1234").get());
        assertEquals(2, result.get("vcs:org:repo:branch:shaabcd").get());
    }
}
