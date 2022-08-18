package com.salesforce.jw.steps.consumer;

import com.github.charithe.kafka.EphemeralKafkaCluster;
import com.salesforce.jw.steps.producer.TestTopicManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

public class WorkExecTest {
    private static EphemeralKafkaCluster kafkaCluster;

    @BeforeAll
    public static void setup() throws Exception {
        kafkaCluster = TestTopicManager.setup();
    }

    @Test
    public void testWorkExecBasics() {

    }
}
