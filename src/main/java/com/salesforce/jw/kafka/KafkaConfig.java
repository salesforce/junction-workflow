/*
 *
 *  * Copyright (c) 2022, salesforce.com, inc.
 *  * All rights reserved.
 *  * Licensed under the BSD 3-Clause license.
 *  * For full license text, see LICENSE.txt file in the repo root or
 *  * https://opensource.org/licenses/BSD-3-Clause
 *
 */

package com.salesforce.jw.kafka;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.ProducerConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class KafkaConfig {
    public final static String WORKFLOW_STATE_TOPIC_CONSUMER_JOB_DATA_KEY = "stepStateTopicConsumer";
    public final static String WORKFLOW_HISTORIC_STATE_TOPIC_PRODUCER_JOB_DATA_KEY = "historicStateTopicProducer";
    public final static String WORKFLOW_LATEST_STATE_TOPIC_PRODUCER_JOB_DATA_KEY = "latestStateTopicProducer";
    public final static String WORKFLOW_LOGS_TOPIC_PRODUCER_JOB_DATA_KEY = "logsTopicProducer";
    public final static String WORKFLOW_HISTORIC_STATE_TOPIC_NAME = "hist-workflow-state";
    public final static String WORKFLOW_LATEST_STATE_TOPIC_NAME = "latest-workflow-state";
    public final static String WORKFLOW_LOGS_TOPIC_NAME = "workflow-logs";

    protected static Map<String, Object> getCommonConsumerConfigNoGroup() {
        Map<String, Object> kafkaConsumerConfig = new HashMap<>();

        kafkaConsumerConfig.putAll(
                ConsumerConfig.configNames().stream()
                        .filter(configKey -> System.getenv(configKey) != null)
                        .collect(Collectors.toMap(configKey -> configKey, System::getenv, (a, b) -> b)));
        // Java properties override env properties
        kafkaConsumerConfig.putAll(ConsumerConfig.configNames().stream()
                .filter(configKey -> System.getProperty(configKey) != null)
                .collect(Collectors.toMap(configKey -> configKey, System::getProperty, (a, b) -> b)));
        return kafkaConsumerConfig;
    }

    protected static Map<String, Object> getCommonConsumerConfig(String groupId, OffsetResetStrategy offsetStrategy) {
        return getCommonConsumerConfig(groupId, offsetStrategy, true);
    }

    protected static Map<String, Object> getCommonConsumerConfig(String groupId, OffsetResetStrategy offsetStrategy, boolean autoCommitOffset) {
        Map<String, Object> kafkaConsumerConfig = getCommonConsumerConfigNoGroup();
        kafkaConsumerConfig.putAll(Map.of(
                ConsumerConfig.GROUP_ID_CONFIG, groupId,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, offsetStrategy.name().toLowerCase(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, autoCommitOffset
        ));

        return kafkaConsumerConfig;
    }

    protected static Map<String, Object> getCommonProducerConfig() {
        if (!System.getenv().containsKey("bootstrap.servers") && !System.getProperties().containsKey("bootstrap.servers")) {
            throw new RuntimeException("Error: Set your kafka bootstrap.servers in either your OS environment or java properties");
        }
        Map<String, Object> kafkaProducerConfig =
                ProducerConfig.configNames().stream()
                        .filter(configKey -> System.getenv(configKey) != null)
                        .collect(Collectors.toMap(configKey -> configKey, System::getenv, (a, b) -> b));
        // Java properties override env properties
        kafkaProducerConfig.putAll(ProducerConfig.configNames().stream()
                .filter(configKey -> System.getProperty(configKey) != null)
                .collect(Collectors.toMap(configKey -> configKey, System::getProperty, (a, b) -> b)));

        // Don't allow override of this; we want as much durability as possible
        kafkaProducerConfig.put("acks", "all");
        return kafkaProducerConfig;
    }

    public static Map<String, Object> getCommonAdminClientConfig() {
        if (!System.getenv().containsKey("bootstrap.servers") && !System.getProperties().containsKey("bootstrap.servers")) {
            throw new RuntimeException("Error: Set your kafka bootstrap.servers in either your OS environment or java properties");
        }
        Map<String, Object> kafkaAdminConfig =
                AdminClientConfig.configNames().stream()
                        .filter(configKey -> System.getenv(configKey) != null)
                        .collect(Collectors.toMap(configKey -> configKey, System::getenv, (a, b) -> b));
        // Java properties override env properties
        kafkaAdminConfig.putAll(ProducerConfig.configNames().stream()
                .filter(configKey -> System.getProperty(configKey) != null)
                .collect(Collectors.toMap(configKey -> configKey, System::getProperty, (a, b) -> b)));

        // Don't allow override of this; we want as much durability as possible
        kafkaAdminConfig.put("acks", "all");
        return kafkaAdminConfig;
    }
}
