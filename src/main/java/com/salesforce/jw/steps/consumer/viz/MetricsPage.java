package com.salesforce.jw.steps.consumer.viz;

import com.salesforce.jw.kafka.KafkaConfig;
import com.salesforce.jw.kafka.KafkaLatestStateConsumer;
import com.salesforce.jw.steps.consumer.WorkExec;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.KafkaAdminClient;
import org.apache.kafka.clients.admin.LogDirDescription;
import org.apache.kafka.common.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static com.salesforce.jw.steps.consumer.viz.MainPage.BODY_COMMON;
import static com.salesforce.jw.steps.consumer.viz.MainPage.COMMON_HEADER;

public record MetricsPage() {
    private final static Logger log = LoggerFactory.getLogger(MetricsPage.class);

    public String render() {
        List<String> headers = Arrays.asList("Metric Name", "Current Value");
        StringBuilder tableHeaders = new StringBuilder("<tr>\n");
        headers.forEach(header -> {
            tableHeaders.append("<th>").append(header).append("</th>").append("\n");
        });
        tableHeaders.append("</tr>");

        StringBuilder tableRows = new StringBuilder("\n");

        // TODO: Cache this
        Map<String, String> metricToValues;
        try (AdminClient adminClient = KafkaAdminClient.create(KafkaConfig.getCommonAdminClientConfig());
             KafkaLatestStateConsumer latestStateConsumer = new KafkaLatestStateConsumer()) {
            try {
                var brokersIds = adminClient.describeCluster().nodes().get().stream().map(Node::id).toList();
                var descriptions = adminClient.describeLogDirs(brokersIds)
                        .allDescriptions().get().values().stream()
                        .map(stringLogDirDescriptionMap -> stringLogDirDescriptionMap.values().stream()
                                .map(LogDirDescription::replicaInfos)
                                .flatMap(rInfo -> rInfo.entrySet().stream()
                                        .filter(entry ->
                                                entry.getKey().topic().contains(KafkaConfig.WORKFLOW_LATEST_STATE_TOPIC_NAME)))
                                .toList())
                        .flatMap(Collection::stream)
                        .toList();
                var partitionSizes = descriptions.stream()
                        .collect(Collectors.groupingBy(Map.Entry::getKey,
                                Collectors.summingLong(tpRInfo -> tpRInfo.getValue().size())))
                        .entrySet().stream()
                        .map(aggTPSize -> "%s : %s".formatted(aggTPSize.getKey().toString(), aggTPSize.getValue()))
                        .sorted()
                        .collect(Collectors.joining("<br>"));
                var consumerGroupInfo =
                        adminClient.listConsumerGroupOffsets(WorkExec.class.getSimpleName());
                var endOffsets = latestStateConsumer.getEndOffsets();
                var offsetDeltas = consumerGroupInfo.partitionsToOffsetAndMetadata().get().entrySet().stream()
                        .map(topicPartitionAndOffsetMetadata -> {
                            var topicParition = topicPartitionAndOffsetMetadata.getKey();
                            var consumerOffset = topicPartitionAndOffsetMetadata.getValue().offset();
                            return "Partition %s : %s".formatted(topicParition.toString(),
                                    endOffsets.get(topicParition) - consumerOffset);
                        }).toList();
                metricToValues = new ConcurrentHashMap<>(Map.of(
                        "Partition Sizes", partitionSizes,
                        "Partition queued items", offsetDeltas.stream().sorted().collect(Collectors.joining("<br>"))
                ));
            } catch (InterruptedException | ExecutionException e) {
                log.error("Failed to get metrics", e);
                return """
                    <html>
                      %s
                      <body>
                        %s
                        %s
                      </body>
                    </html>
                """.formatted(COMMON_HEADER.formatted("Junction Workflow Metrics"), BODY_COMMON, e);
            }
        }

        metricToValues.forEach((metricName, metricValue) -> {
            tableRows.append("<tr>\n");
            Arrays.asList(metricName, metricValue)
                    .forEach(coordinate -> {
                        tableRows
                                .append("<td>")
                                .append(coordinate)
                                .append("</td>\n");
                    });
            tableRows.append("</tr>\n");
        });


        return """
                <html>
                  %s
                  <body>
                    %s
                    <div class="dataTable">
                        <table>
                            %s
                            %s
                        </table>
                    </div>
                                
                  </body>
                </html>
                """.formatted(
                COMMON_HEADER.formatted("Junction Workflow"),
                BODY_COMMON,
                tableHeaders.toString(),
                tableRows.toString());
    }
}
