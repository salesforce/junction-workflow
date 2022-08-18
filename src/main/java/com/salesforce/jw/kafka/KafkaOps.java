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

import com.google.protobuf.InvalidProtocolBufferException;
import com.salesforce.jw.steps.WorkflowProtos;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class KafkaOps {
    private final static Logger log = LoggerFactory.getLogger(KafkaOps.class);

    protected long sendWorkflowState(String gitRepoBranch, WorkflowProtos.Workflow workflow, String topicName) throws InterruptedException, ExecutionException {
        Map<String, Object> kafkaProducerConfig = KafkaConfig.getCommonProducerConfig();
        // TODO: We'll likely want to key on git-repo-branch, which we might want to make a strongly-typed object instead of a String
        kafkaProducerConfig.put(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        kafkaProducerConfig.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.ByteArraySerializer");
        kafkaProducerConfig = Collections.unmodifiableMap(kafkaProducerConfig);
        try (KafkaProducer<String, byte[]> kafkaProducer = new KafkaProducer<>(kafkaProducerConfig)) {
            Future<RecordMetadata> sendRecord =
                    kafkaProducer.send(new ProducerRecord<>(topicName, gitRepoBranch, workflow.toByteArray()));
            var recordMetadata = sendRecord.get();
            return recordMetadata.offset();
        }
    }

    protected Map<String, WorkflowProtos.Workflow> pollWorkFlowMap(KafkaConsumer<String, byte[]> kafkaConsumer, List<TopicPartition> topicPartitions) {
        Map<String, WorkflowProtos.Workflow> workflowMap = new ConcurrentSkipListMap<>();
        ConsumerRecords<String, byte[]> workflowConsumerRecords =
                kafkaConsumer.poll(Duration.of(100, ChronoUnit.MILLIS));

        topicPartitions.parallelStream().forEach(topicPartition ->
                workflowConsumerRecords.records(topicPartition).forEach(record -> {
                    log.info("Got record at offset {}", record.offset());
                    try {
                        workflowMap.put(record.key(), WorkflowProtos.Workflow.parseFrom(record.value()));
                    } catch (InvalidProtocolBufferException e) {
                        log.error("Invalid workflow for {}", record.key(), e);
                    }
                }));
        return workflowMap;
    }

    public Map<String, WorkflowProtos.Workflow> pollFromStart(KafkaConsumer<String,byte[]> kafkaConsumer, List<TopicPartition> topicPartitions) {
        Map<String, WorkflowProtos.Workflow> workflows = new ConcurrentHashMap<>();
        kafkaConsumer.assign(topicPartitions);
        // TODO: Seek from beginning by default and have the timestamp seek a configurable
        //kafkaConsumer.seekToBeginning(topicPartitions);
        Map<TopicPartition, Long> timestampsToSearch = topicPartitions.stream()
                .collect(Collectors.toMap(topicPartition -> topicPartition,
                    notUsed -> System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(15)));
        kafkaConsumer.offsetsForTimes(timestampsToSearch).forEach((topicPartition, offsetAndTimestamp) -> {
            if (offsetAndTimestamp == null) {
                kafkaConsumer.seekToBeginning(Collections.singleton(topicPartition));
            } else {
                kafkaConsumer.seek(topicPartition, offsetAndTimestamp.offset());
            }
        });
        AtomicBoolean endOfLog = new AtomicBoolean(false);
        var startingEndOffsets = kafkaConsumer.endOffsets(topicPartitions);
        var positions = topicPartitions.stream()
                .collect(Collectors.toMap(topicPartition -> topicPartition, kafkaConsumer::position));
        if (positions.entrySet().stream().allMatch(topicPartitionToPosition -> startingEndOffsets.get(topicPartitionToPosition.getKey()).equals(topicPartitionToPosition.getValue()))) {
            log.info("No previous state to read based on positions {} and end offsets: {}", positions, startingEndOffsets);
            return workflows;
        } else {
            log.info("Reading from offsets {} to {}...", positions, startingEndOffsets);
        }
        while (!endOfLog.get()) {
            ConsumerRecords<String, byte[]> records = kafkaConsumer.poll(Duration.ofMillis(100));
            records.forEach(record -> {
                var recordTopicPartition = new TopicPartition(record.topic(), record.partition());
                try {
                    log.trace("Got record by key {}", record.key());
                    workflows.put(record.key(), WorkflowProtos.Workflow.parseFrom(record.value()));
                    var endOffset = kafkaConsumer.endOffsets(Collections.singleton(recordTopicPartition)).get(recordTopicPartition);
                    if (!startingEndOffsets.get(recordTopicPartition).equals(endOffset)) {
                        log.info("End offset increased to {} while reading from beginning", endOffset);
                    }
                    log.trace("offset {} / endoffset {}", record.offset(), endOffset);
                    if (record.offset() == endOffset - 1) {
                        endOfLog.set(true);
                    }
                } catch (InvalidProtocolBufferException e) {
                    log.error("Invalid workflow for {}", record.key(), e);
                }
            });
        }
        return workflows;
    }
}
