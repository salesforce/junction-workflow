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

import com.salesforce.jw.steps.WorkflowProtos.Workflow;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.salesforce.jw.kafka.KafkaConfig.WORKFLOW_HISTORIC_STATE_TOPIC_NAME;

public class KafkaHistoricStateConsumer implements Closeable {
    private final static Logger log = LoggerFactory.getLogger(KafkaHistoricStateConsumer.class);

    // TODO: No need to hardcode this - pass through constructor and create this in main

    private final KafkaConsumer<String, byte[]> kafkaConsumer;
    private final List<TopicPartition> topicPartitions;

    public KafkaHistoricStateConsumer(String groupId, OffsetResetStrategy offsetStrategy) {
        Map<String, Object> kafkaConsumerConfig = KafkaConfig.getCommonConsumerConfig(groupId, offsetStrategy);

        kafkaConsumerConfig.put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        kafkaConsumerConfig.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.ByteArrayDeserializer");

        kafkaConsumer = new KafkaConsumer<>(kafkaConsumerConfig);
        List<PartitionInfo> partitions = kafkaConsumer.partitionsFor(WORKFLOW_HISTORIC_STATE_TOPIC_NAME);
        topicPartitions = partitions.parallelStream().map(partitionInfo ->
                new TopicPartition(partitionInfo.topic(), partitionInfo.partition())).collect(Collectors.toList());
        kafkaConsumer.subscribe(Collections.singleton(WORKFLOW_HISTORIC_STATE_TOPIC_NAME));
    }



    public KafkaHistoricStateConsumer(String groupId) {
        this(groupId, OffsetResetStrategy.EARLIEST);
    }

    /**
     * We return the latest record for each repo-branch key
     * TODO: We need a better way to key things on. For example, we might at some point want workflows to be run on the
     *  same pipeline concurrently. Thus we'd need to also key on run-id or something like that. Also, repo-branch
     *  assumes these workflows are for git projects only - ideally we'd be more generic than that.
     *
     * @return
     */
    public Map<String, Workflow> pollWorkflows() {
        return new KafkaOps().pollWorkFlowMap(kafkaConsumer, topicPartitions);
    }

    @Override
    public void close() throws IOException {
        if (kafkaConsumer != null) {
            kafkaConsumer.close();
        }
    }
}
