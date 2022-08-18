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

import com.github.charithe.kafka.EphemeralKafkaCluster;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Assertions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.salesforce.jw.kafka.KafkaConfig.*;

public class TestTopicManager {
    private static Logger log = LoggerFactory.getLogger(TestTopicManager.class);

    public static void createTopics(EphemeralKafkaCluster kafkaCluster) {
        Properties adminClientProps = new Properties();
        log.info("Using bootstrap.servers {}",
                kafkaCluster.getBrokers().get(0).getBrokerList().get());
        adminClientProps.setProperty(
                "bootstrap.servers", "%s".formatted(
                        kafkaCluster.getBrokers().get(0).getBrokerList().get()));
        try (AdminClient kafkaAdminClient = AdminClient.create(adminClientProps)) {
            // Partition sizing is mostly arbitrary - set to >1 partitions to be in the mindset of being able to scale
            // partitions later on
            NewTopic workflowHistoricStateTopic = new NewTopic(WORKFLOW_HISTORIC_STATE_TOPIC_NAME, 5, (short) 1);
            NewTopic workflowLatestStateTopic = new NewTopic(WORKFLOW_LATEST_STATE_TOPIC_NAME, 5, (short) 1);
            NewTopic workflowLogsTopic = new NewTopic(WORKFLOW_LOGS_TOPIC_NAME, 5, (short) 1);
            kafkaAdminClient.createTopics(List.of(workflowHistoricStateTopic, workflowLatestStateTopic, workflowLogsTopic));
            kafkaAdminClient.incrementalAlterConfigs(
                    Map.of(
                            new ConfigResource(ConfigResource.Type.TOPIC, workflowLatestStateTopic.name()),
                            Collections.singleton(
                                    new AlterConfigOp(
                                            new ConfigEntry(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT),
                                            AlterConfigOp.OpType.SET))));
        }
    }

    public static EphemeralKafkaCluster setup() throws Exception {
        var kafkaCluster = EphemeralKafkaCluster.create(1);

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
        return kafkaCluster;
    }
}
