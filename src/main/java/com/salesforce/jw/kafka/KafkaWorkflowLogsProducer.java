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

import com.salesforce.jw.kafka.KafkaConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static com.salesforce.jw.kafka.KafkaConfig.WORKFLOW_LOGS_TOPIC_NAME;

public class KafkaWorkflowLogsProducer {
    public void sendLog(String gitRepoBranch, String logMsg) {
        Map<String, Object> kafkaProducerConfig = KafkaConfig.getCommonProducerConfig();
        // TODO: We'll likely want to key on git-repo-branch, which we might want to make a strongly-typed object instead of a String
        kafkaProducerConfig.put(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        kafkaProducerConfig.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        kafkaProducerConfig = Collections.unmodifiableMap(kafkaProducerConfig);
        try (KafkaProducer<String, String> kafkaProducer = new KafkaProducer<>(kafkaProducerConfig)) {
            Future<RecordMetadata> sendRecord =
                    kafkaProducer.send(new ProducerRecord<>(WORKFLOW_LOGS_TOPIC_NAME, 0,
                            gitRepoBranch, logMsg));
            try {
                sendRecord.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException("Failed to sent log to %s".formatted(WORKFLOW_LOGS_TOPIC_NAME), e);
            }
        }
    }
}
