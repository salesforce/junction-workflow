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
import io.vertx.core.AbstractVerticle;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class PipelinesWeb extends AbstractVerticle {
    private final static Logger log = LoggerFactory.getLogger(PipelinesWeb.class);

    @Override
    public void start() throws Exception {
        Router router = Router.router(vertx);
        router.get("/")
                .respond(
                        ctx -> ctx
                                .response()
                                .putHeader("Content-Type", "text/html")
                                .end(new MainPage(new WorkflowOperations()).render()));
        router.get("/metrics")
                .respond(
                        ctx -> ctx
                                .response()
                                .putHeader("Content-Type", "text/html")
                                .end(new MetricsPage().render()));
        router.get("/pipeline")
                .respond(
                        ctx -> {
                            try {
                                return ctx
                                        .response()
                                        .putHeader("Content-Type", "text/html")
                                        .end(new PipelinePage().render(
                                                ctx.queryParams().get("vcs"),
                                                ctx.queryParams().get("org"),
                                                ctx.queryParams().get("project"),
                                                ctx.queryParams().get("branch"),
                                                ctx.queryParams().get("gitsha"),
                                                ctx.queryParams().get("runid")));
                            } catch (IOException | InterruptedException e) {
                                log.error("Unable to render pipeline with query params: %s".formatted(ctx.queryParams()), e);
                                return ctx.response()
                                        .putHeader("Content-Type", "text/html")
                                        .end("Internal Server Error: %s".formatted(e));
                            }
                        });
        router.route().handler(BodyHandler.create());

        vertx.createHttpServer()
                .requestHandler(router)
                // TODO: Pass through env/systemProps config
                .listen(8888)
                .onSuccess(server -> {
                            log.info("HTTP server started on port {}", server.actualPort());
                        }
                )
                .onFailure(handler -> {
                    throw new RuntimeException(handler);
                });
    }
}
