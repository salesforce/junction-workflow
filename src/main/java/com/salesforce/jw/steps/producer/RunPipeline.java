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

import com.salesforce.jw.kafka.KafkaHistoricStateProducer;
import com.salesforce.jw.kafka.KafkaLatestStateProducer;
import com.salesforce.jw.parsers.WorkflowInterface;
import com.salesforce.jw.steps.WorkflowProtos;
import io.vertx.core.AbstractVerticle;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

public class RunPipeline extends AbstractVerticle {
    private final static Logger log = LoggerFactory.getLogger(RunPipeline.class);

    @Override
    public void start() throws Exception {
        KafkaLatestStateProducer latestStateTopic = new KafkaLatestStateProducer();
        KafkaHistoricStateProducer historicStateTopic = new KafkaHistoricStateProducer();
        ProcessWorkflow processWorkflow = new ProcessWorkflow(latestStateTopic, historicStateTopic);

        Router router = Router.router(vertx);
        router.get("/run")
                .respond(
                        ctx -> {
                            String vcs = ctx.queryParams().get("vcs");
                            if (vcs == null) {
                                throw new RuntimeException("You must specify the vcs (e.g. github.com). Your query params: %s"
                                        .formatted(ctx.queryParams()));
                            }
                            String org = ctx.queryParams().get("org");
                            if (org == null) {
                                throw new RuntimeException("You must specify the org. Your query params: %s"
                                        .formatted(ctx.queryParams()));
                            }
                            String repo = ctx.queryParams().get("repo");
                            if (repo == null) {
                                throw new RuntimeException("You must specify the repo. Your query params: %s"
                                        .formatted(ctx.queryParams()));
                            }
                            String branch = ctx.queryParams().get("ghbranch");
                            String gitsha = ctx.queryParams().get("gitsha");

                            var unprocessedWorkflow = new UnprocessedWorkflow(vcs, org, repo, branch, gitsha);
                            processWorkflow.queue(unprocessedWorkflow);
                            return ctx
                                    .response()
                                    .putHeader("Content-Type", "text/html")
                                    // TODO: Respond with error if no response set
                                    .end(unprocessedWorkflow.getKey());
                        });
        router.route().handler(BodyHandler.create());

        vertx.createHttpServer()
                .requestHandler(router)
                // TODO: Pass through env/systemProps config
                .listen(8889)
                .onSuccess(server ->
                        log.info("HTTP server started on port http://localhost:{}", server.actualPort())
                )
                .onFailure(handler -> {
                    throw new RuntimeException(handler);
                });
    }
}
