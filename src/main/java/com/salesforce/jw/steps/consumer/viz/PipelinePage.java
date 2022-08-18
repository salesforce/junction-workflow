/*
 *
 *  * Copyright (c) 2022, salesforce.com, inc.
 *  * All rights reserved.
 *  * Licensed under the BSD 3-Clause license.
 *  * For full license text, see LICENSE.txt file in the repo root or
 *  * https://opensource.org/licenses/BSD-3-Clause
 *
 */

package com.salesforce.jw.steps.consumer.viz;

import com.salesforce.jw.steps.WorkflowOperations;
import com.salesforce.jw.steps.WorkflowProtos.Workflow;
import guru.nidi.graphviz.attribute.Color;
import guru.nidi.graphviz.attribute.Font;
import guru.nidi.graphviz.attribute.Rank;
import guru.nidi.graphviz.engine.Format;
import guru.nidi.graphviz.engine.Graphviz;
import guru.nidi.graphviz.model.Graph;
import guru.nidi.graphviz.model.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.salesforce.jw.steps.WorkflowProtos.Step;
import static com.salesforce.jw.steps.WorkflowProtos.Steps;
import static guru.nidi.graphviz.attribute.Attributes.attr;
import static guru.nidi.graphviz.model.Factory.graph;
import static guru.nidi.graphviz.model.Factory.node;

public class PipelinePage {
    public enum DisplayState {
        NOT_STARTED(
                Step.State.NOT_STARTED, Color.GREY, "Not Started"),
        RUNNING(
                Step.State.RUNNING, Color.YELLOW, "Running"),
        COMPLETED(
                Step.State.COMPLETED, Color.GREEN, "Completed"),
        PAUSED(
                Step.State.PAUSED, Color.BLACK, "Paused"),
        SKIPPED(
                Step.State.SKIPPED, Color.LIGHTGREY, "Skipped"),
        TIMED_OUT(
                Step.State.TIMED_OUT, Color.RED, "Timed Out"),
        ERRORED(
                Step.State.ERRORED, Color.RED, "Error");

        private Step.State state;
        private Color color;
        private String description;
        private final static Map<Step.State, DisplayState> displayStateMap;

        DisplayState(Step.State state, Color color, String description) {
            this.state = state;
            this.color = color;
            this.description = description;
        }

        public Step.State getState() {
            return state;
        }

        public String getDescription() {
            return description;
        }

        public Color getColor() {
            return color;
        }

        static {
            Map<Step.State, DisplayState> map = new ConcurrentHashMap<>();
            for (DisplayState displayState : DisplayState.values()) {
                map.put(displayState.getState(), displayState);
            }
            displayStateMap = Collections.unmodifiableMap(map);
        }

        public static DisplayState getDisplayState (Step.State state) {
            return displayStateMap.get(state);
        }
    }

    private final static Logger log = LoggerFactory.getLogger(PipelinePage.class);
    private final WorkflowOperations workflowOperations = new WorkflowOperations();

    private Color getColorForState(Step.State state) {
        DisplayState displayState = DisplayState.getDisplayState(state);
        return displayState.getColor();
    }

    private String getLabelForState(Step step) {
        DisplayState displayState = DisplayState.getDisplayState(step.getState());
        return displayState.getDescription() + "\n" + step.getName();
    }

    private String getLabelForState(Steps steps) {
        DisplayState displayState = DisplayState.
                getDisplayState(workflowOperations.lastReportedState(steps));
        return displayState.getDescription() + "\n" + steps.getName();
    }

    private Node appendSequentialStep(Node from, List<Node> to) {
        if (to.size() == 0) {
            return from;
        }
        log.info("Link from {} to {}", from.get("label"), to.get(0).get("label"));
        if (to.size() == 1) {
            return from.link(to);
        } else {
            return from.link(appendSequentialStep(to.get(0), to.subList(1, to.size())));
        }
    }

    private Node appendStepListToParent(String workflowKey, Node parent, boolean parallel, List<Step> stepList, List<Steps> stepGroups) {
        List<Node> stepNodes = stepList.stream()
                .map(step -> node(step.getUuid())
                        .with(List.of(
                                attr("label", getLabelForState(step)),
                                getColorForState(step.getState()),
                                attr("URL", "step-log?key=%s:%s:%s"
                                        .formatted(workflowKey, step.getName(), step.getName())))))
                .collect(Collectors.toList());
        stepNodes.addAll(stepGroups.stream()
                .map(steps -> {
                    Node subParent = node(steps.getUuid())
                            .with(List.of(
                                    attr("label", getLabelForState(steps)),
                                    getColorForState(workflowOperations.lastReportedState(steps)),
                                    attr("URL", "step-log?key=%s:%s".formatted(workflowKey, steps.getName())),
                                    attr("shape", "box")));
                    log.info("Continuing with parent node {}", steps.getName());
                    return appendStepListToParent(workflowKey, subParent, steps.getParallel(), steps.getStepList(), steps.getStepsList());
                }).toList());
        if (parallel) {
            return parent.link(stepNodes);
        } else {
            log.info("Link from {} to sequential steps", parent.get("label"));
            parent = parent.link(appendSequentialStep(stepNodes.get(0), stepNodes.subList(1, stepNodes.size())));
        }
        return parent;
    }

    private Graph appendGroupToGraph(String workflowKey, List<Steps> steps) {
        Graph subGraph = graph().directed();
        for (Steps subSteps : steps) {
            Node stepsNode = node(subSteps.getUuid())
                    .with(List.of(
                            attr("label", subSteps.getName()),
                            attr("URL", "step-log?key=%s:%s".formatted(workflowKey, subSteps.getName())),
                            attr("shape", "box"),
                            attr("comment", "test comment")));
            log.info("Starting with parent node: {}", subSteps.getName());
            subGraph = subGraph
                    .with(appendStepListToParent(workflowKey, stepsNode, subSteps.getParallel(), subSteps.getStepList(), subSteps.getStepsList()));
        }
        return subGraph;
    }

    public String render(String vcs, String org, String project, String branch, String gitsha, String runid) throws IOException, InterruptedException {
        // TODO: Strong-type the key
        String workflowKey = "%s:%s:%s:%s:%s:%s".formatted(vcs, org, project, branch, gitsha, runid);
        Workflow workflow = GraphvizVisualizer.workflowsCache.get(workflowKey);
        if (workflow == null) {
            throw new IOException("Unable to find workflow from key %s. Available workflows keys: %s".formatted(workflowKey, GraphvizVisualizer.workflowsCache.keySet()));
        }
        Graph graphvizGraph = graph("%s - %s".formatted(project, branch)).directed()
                .graphAttr().with(Rank.dir(Rank.RankDir.TOP_TO_BOTTOM))
                .nodeAttr().with(List.of(Font.name("Comic Sans"), Color.BLUE))
                .with(appendGroupToGraph(workflowKey, workflow.getStepsList()));

        var svgRenderer = Graphviz.fromGraph(graphvizGraph).height(500).render(Format.SVG);
        var svgStr = svgRenderer.toString();
        if (svgStr.isEmpty()) {
            log.error("Failed render svg from graph: {}", graphvizGraph);
            svgStr = "Error: failed to render from graph: %s".formatted(graphvizGraph);
        }
        log.debug("svg to be rendered: {}", svgStr);
        return """
                <html>
                       %s
                       <body>
                         <!-- TODO: Show summary table for last x workflows with links to those results -->
                       
                         %s
                         <div class="detailsPage">
                           <p>
                             %s
                           </p>
                           <p>
                             %s
                           </p>                              
                         </div>
                       </body>
                </html>""".formatted(
                        MainPage.COMMON_HEADER.formatted("Junction Workflow for " + workflowKey),
                        MainPage.BODY_COMMON,
                        svgStr,
                        getEntireLogFor(workflowKey)
                );
    }

    // TODO: This isn't a great place for a sync Kafka consumer - we should have a separate cache or service primed
    private String getEntireLogFor(String workflowKey) throws InterruptedException {
        //KafkaWorkflowLogsConsumer logsConsumer = new KafkaWorkflowLogsConsumer();
        //KafkaWorkflowLogsConsumer logsConsumer = new KafkaWorkflowLogsConsumer(UUID.randomUUID().toString(), OffsetResetStrategy.EARLIEST);
        if (GraphvizVisualizer.logCache != null && GraphvizVisualizer.logCache.containsKey(workflowKey)) {
            return String.join("\n<br>", GraphvizVisualizer.logCache.get(workflowKey));
        } else {
            return "Waiting for logs...";
        }
    }
}
