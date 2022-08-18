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

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.salesforce.jw.kafka.KafkaConfig.WORKFLOW_LOGS_TOPIC_NAME;

public class KafkaWorkflowLogsConsumer implements Closeable {
    private final static Logger log = LoggerFactory.getLogger(KafkaWorkflowLogsConsumer.class);

    private final KafkaConsumer<String, String> kafkaConsumer;
    // TODO: Why is this only set on create? # partitions can increase...
    private final List<TopicPartition> topicPartitions;

    public KafkaWorkflowLogsConsumer(String groupId, OffsetResetStrategy offsetStrategy) {
        Map<String, Object> kafkaConsumerConfig = KafkaConfig.getCommonConsumerConfig(groupId, offsetStrategy);

        kafkaConsumerConfig.put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        kafkaConsumerConfig.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");

        kafkaConsumer = new KafkaConsumer<>(kafkaConsumerConfig);
        List<PartitionInfo> partitions = kafkaConsumer.partitionsFor(WORKFLOW_LOGS_TOPIC_NAME);
        topicPartitions = partitions.parallelStream().map(partitionInfo ->
                new TopicPartition(partitionInfo.topic(), partitionInfo.partition())).collect(Collectors.toList());
        kafkaConsumer.subscribe(Collections.singleton(WORKFLOW_LOGS_TOPIC_NAME));
    }

    public KafkaWorkflowLogsConsumer() {
        Map<String, Object> kafkaConsumerConfig = KafkaConfig.getCommonConsumerConfigNoGroup();

        kafkaConsumerConfig.put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        kafkaConsumerConfig.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");

        kafkaConsumer = new KafkaConsumer<>(kafkaConsumerConfig);
        List<PartitionInfo> partitions = kafkaConsumer.partitionsFor(WORKFLOW_LOGS_TOPIC_NAME);
        topicPartitions = partitions.parallelStream().map(partitionInfo ->
                new TopicPartition(partitionInfo.topic(), partitionInfo.partition())).collect(Collectors.toList());
    }

    public KafkaWorkflowLogsConsumer(String groupId) {
        this(groupId, OffsetResetStrategy.EARLIEST);
    }

    public synchronized Map<String, List<String>> getLatestLogs() {
        Map<String, List<String>> formattedLogLines = new ConcurrentHashMap<>();
        ConsumerRecords<String, String> logRecords =
                kafkaConsumer.poll(Duration.of(100, ChronoUnit.MILLIS));

        logRecords.records(WORKFLOW_LOGS_TOPIC_NAME).forEach(record -> {
            String formatted = "[%s] [%s] %s".formatted(record.timestamp(), record.key(), record.value());
            if (formattedLogLines.containsKey(record.key())) {
                formattedLogLines.get(record.key()).add(formatted);
            } else {
                List<String> formattedSeedList = new LinkedList<>();
                formattedSeedList.add(formatted);
                formattedLogLines.put(record.key(), formattedSeedList);
            }
        });
        return formattedLogLines;
    }

    public List<String> getLatestLogs(String key) {
        List<String> formattedLogLines = new LinkedList<>();
        ConsumerRecords<String, String> logRecords =
                kafkaConsumer.poll(Duration.of(100, ChronoUnit.MILLIS));

        logRecords.records(WORKFLOW_LOGS_TOPIC_NAME).forEach(record -> {
            if (record.key().equals(key)) {
                formattedLogLines.add("[%s] [%s] %s"
                        .formatted(record.timestamp(), record.key(), record.value()));
            }
        });
        return formattedLogLines;
    }

    public List<String> getLogsSync(String key) throws InterruptedException {
        List<String> formattedLogLines = new LinkedList<>();

        kafkaConsumer.assign(topicPartitions);
        kafkaConsumer.seekToBeginning(topicPartitions);
        boolean isEnd = false;
        while (!isEnd) {
            ConsumerRecords<String, String> logRecords = kafkaConsumer.poll(Duration.of(100, ChronoUnit.MILLIS));
            for (ConsumerRecord<String, String> record : logRecords.records(WORKFLOW_LOGS_TOPIC_NAME)) {
                if (record.key().equals(key)) {
                    formattedLogLines.add(formatLogLine(record));
                }
                TopicPartition recordPartition = new TopicPartition(WORKFLOW_LOGS_TOPIC_NAME, record.partition());
                if (record.offset() == kafkaConsumer.position(recordPartition) - 1) {
                    isEnd = true;
                }
            }
            if (logRecords.count() == 0 && formattedLogLines.size() == 0) {
                return Collections.singletonList("Waiting for logs...");
            }
        }
        /*Duration waitTime = Duration.of(3, ChronoUnit.SECONDS);
        long sleepDurationMs = 100L;
        for (int i = 0; i < waitTime.toMillis(); i += sleepDurationMs) {
            ConsumerRecords<String, String> logRecords =
                    kafkaConsumer.poll(Duration.of(100, ChronoUnit.MILLIS));

            logRecords.records(WORKFLOW_LOGS_TOPIC_NAME).forEach(record -> {
                if (record.key().equals(key)) {
                    formattedLogLines.add("[%s] [%s] %s"
                            .formatted(record.timestamp(), record.key(), record.value()));
                }
            });
            Thread.sleep(sleepDurationMs);
        }*/
        return formattedLogLines;
    }

    private String formatLogLine(ConsumerRecord<String, String> record) {
        long timestamp = record.timestamp();
        String key = record.key();
        String value = record.value();
        String text;
        Date date=new Date(timestamp);
        if (value.contains("STDERR")) {
            text = ("<span style=\"font-family:'Courier New';font-size:13.3px; line-height: 20px; color:;\">[%s] [%s] " +
                    "<span style=\"color:red;\">" +
                    "%s</span> </span>").
                    formatted(date, record.key(), record.value());
        } else {
            text = ("<span style=\"font-family:'Courier New';font-size:13.3px; line-height: 20px; color:;\">[%s] [%s] " +
                    "<span style=\"color:;\">" +
                    "%s</span> </span>").
                    formatted(date, record.key(), record.value());
        }
        return text;
    }
    
    @Override
    public void close() throws IOException {
        if (kafkaConsumer != null) {
            kafkaConsumer.close();
        }
    }
}
