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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public record MainPage(WorkflowOperations workflowOperations) {
    private final static Logger log = LoggerFactory.getLogger(MainPage.class);
    static final String COMMON_HEADER = """
                <head>
                    <title>%s</title>
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <style>
                        .topnav {
                            overflow: hidden;
                            background-color: #ffffff;
                        }
                        .topnav a:hover {
                            color: black;
                        }
                                
                        .topnav a.active {
                            background-color: #2196F3;
                            color: white;
                        }
                                
                        .topnav .login-container {
                            float: right;
                        }
                                
                        .topnav input[type=text] {
                            padding: 6px;
                            margin-top: 8px;
                            font-size: 17px;
                            border: none;
                            width:120px;
                        }
                                
                        .topnav .login-container button {
                            float: right;
                            padding: 6px 10px;
                            background-color: #555;
                            color: white;
                            font-size: 17px;
                            border: none;
                            cursor: pointer;
                        }
                                
                        .topnav .login-container button:hover {
                            background-color: green;
                        }
                                
                        .logo {
                            float: left;
                            padding-right: 2px;
                        }
                        .login-container a {
                            display: block;
                            color: #2196F3;
                            text-align: center;
                            padding: 5px 16px;
                            text-decoration: none;
                            font-size: 16px;
                        }
                                
                        /* Logo-3 */
                        .logo-3 {
                            float: left;
                            text-align: center;
                        }
                                
                        .logo-3 h3 {
                            color: #016201;
                            font-family: 'Oswald', sans-serif;
                            font-weight: 300;
                            font-size: 16px;
                            line-height:1.3;
                            /*padding: 2px;*/
                            /*padding-top: 10px;*/
                        }
                        .logo-3 p {
                            font-size: 10px;
                            letter-spacing: 7px;
                            text-transform: uppercase;
                            background: #34495e;
                            font-weight: 400;
                            color: #fff;
                            padding-left: 5px;
                        }
                                
                        /*Manju Changes*/
                        .c-menu ul {
                            list-style-type: none;
                            margin: 0;
                            padding: 0;
                            overflow: hidden;
                            background-color: #2596be;
                        }
                                
                        .c-menu li {
                            float: left;
                        }
                                
                        .c-menu li a {
                            display: block;
                            color: white;
                            text-align: center;
                            padding: 14px 16px;
                            text-decoration: none;
                        }
                                
                        .c-menu li a:hover:not(.active) {
                            background-color: #111;
                        }
                                
                        .c-menu .active {
                            background-color: #e28743;
                        }
                                
                        .c-menu .login-container {
                            float: right;
                        }
                                
                        .dataTable table {
                            font-family: arial, sans-serif;
                            border-collapse: collapse;
                            width: 80%%;
                        }
                                
                        .dataTable th {
                            background-color: #cce7e8;
                        }
                                
                        .dataTable td, th {
                            border: 1px solid #dddddd;
                            text-align: left;
                            padding: 8px;
                        }
                                
                        .dataTable tr:nth-child(even) {
                            background-color: #f8f8f8;
                        }
                        .dataTable {
                            padding: 10px;
                        }
                    
                        .cssLogo {
                            margin-left: 5px;
                        }
                        .cssLogo div {
                            display:inline-block;
                            font-size:50px;
                        }
                        .duetText {
                            margin-left:40px;
                            /* purple/Violet */
                            color:#28275b;
                        }
                        .healthText {
                            /* Green Color again */
                            color:#00b971;
                        }
                        .patientImage {
                            border-radius:50%%;
                            margin-bottom:15px;
                            position:absolute;
                            height:17px;
                            width:17px;
                        }
                        .greenBody {
                            /*Green color*/
                            background-color:#00b971;
                            border:2px solid white;
                            z-index:200;
                        }
                        .violetBody {
                            margin-top:2px;
                            margin-left:13px;
                            z-index:-200;
                            /*Violet blue like color*/
                            background-color:#28275b;
                                
                        }
                        .upperSection {
                            border-radius:50%%;
                            height:18px;
                            width:18px;
                            margin-top:-18px;
                            position:absolute;
                        }
                        .firstPart {
                            margin-left:-5px;
                            background-color:#00b971;
                        }
                        .middlePart {
                            margin-left:10px;
                            background-color:#00b971;
                            height:18px;
                            width:11px;
                            border-radius: 12px 6px 0 12px;
                        }
                        .lastPart {
                            height:18px;
                            width:11px;
                            border-radius: 0 11px 11px 6px;
                            margin-left:24px;
                            background-color:#28275b;
                        }
                        .PlusSignHorizontalBar {
                            position:absolute;
                            width:16px;
                            height:3px;
                            margin-top:-10px;
                            margin-left:14px;
                            background-color:white;
                        }
                        .plusSign {
                            margin-top:40px;
                        }
                        .horizontalGreenFiller {
                            position:absolute;
                            height:5px;
                            width:10px;
                            margin-top:-5px;
                            margin-left:5px;
                            z-index:200;
                            /* Green color again */
                            /* Bar which connects two circle and eliminates weird gap between   two */
                            background-color:#00b971;
                        }
                                
                        .dataTable a:link{
                            text-decoration: none;
                            color:#134896;
                        }
                        .dataTable a:hover{
                            text-decoration: none;
                            color:#942A5F;
                        }
                    </style>
                </head>""";

    static final String BODY_COMMON = """
                       <div class="cssLogo">
                           <div class='patientImage greenBody'></div>
                           <div class='patientImage violetBody'></div>
                           <div class='duetText'>Junction</div>
                           <div class='healthText'>Workflow</div>
                       </div>
                       
                       <div class="c-menu">
                           <ul>
                               <li class="nav-item dropdown">
                                   <a class="active" href="/"> Dashboard</a>
                               </li>
                               <li class="nav-item dropdown">
                                   <a href="/metrics"> Metrics</a>
                               </li>
                               <li class="nav-item dropdown">
                                   <a href="#"> About</a>
                               </li>
                               <li class="nav-item dropdown">
                                   <a href="#"> Help</a>
                               </li>
                           </ul>
                       </div>""";

    public String render() {
        List<String> headers = Arrays.asList("Version Control", "Organization", "Project", "Branch", "Short Sha", "Run Id", "Status");
        StringBuilder tableHeaders = new StringBuilder("<tr>\n");
        headers.forEach(header -> {
            tableHeaders.append("<th>").append(header).append("</th>").append("\n");
        });
        tableHeaders.append("</tr>");

        log.info("Rendering {} pipelines", GraphvizVisualizer.workflowsCache.size());

        StringBuilder tableRows = new StringBuilder("\n");
        GraphvizVisualizer.workflowsCache.forEach((key, workflow) -> {
            // TODO: We need to make our key strongly typed
            var keyElements = key.split(":");
            if (keyElements.length != 6) {
                log.warn("Unrecognized key, not rendering: {}", key);
                return;
            }
            AtomicReference<String> keySha = new AtomicReference<>("");
            if (keyElements.length >= 5) {
                keySha.set(key.split(":")[4]);
            }
            AtomicReference<String> runId = new AtomicReference<>("");
            if (keyElements.length >= 6) {
                runId.set(key.split(":")[5]);
            }
            var vcs = keyElements[0];
            var org = keyElements[1];
            var repo = keyElements[2];
            var branch = keyElements[3];
            tableRows.append("<tr>\n");
            Arrays.asList(vcs, org, repo, branch, keySha.get(), runId.get(),
                            workflowOperations.getLifecycleState(workflow))
                    // TODO: People would want to link to the repo and pull-request/branch
                    .forEach(coordinate -> {
                        log.debug("Rendering workflow: {}", workflow);
                        tableRows
                                .append("<td>")
                                .append("<a href=/pipeline?vcs=%s&org=%s&project=%s&branch=%s&gitsha=%s&runid=%s>%s</a>"
                                        .formatted(vcs, org, repo, branch, keySha.get(), runId.get(), coordinate))
                                .append("</td>\n");
            });
            tableRows.append("</tr>\n");
        });

        // TODO: Would be nice to have a filter by vcs, org, and project -
        //      also a tickbox to only show protected branches
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
