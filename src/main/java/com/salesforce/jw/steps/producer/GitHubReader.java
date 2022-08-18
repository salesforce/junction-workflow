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

import com.salesforce.jw.parsers.WorkflowInterface;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

public class GitHubReader {
    private final static Logger log = LoggerFactory.getLogger(GitHub.class);
    private final static AtomicReference<GitHub> ghConnection = new AtomicReference<>();

    public static GitHub init() throws JobExecutionException {
        // TODO: Read and validate configs
        String vcsApiUrl = System.getenv("JW_GH_URL");
        if (vcsApiUrl == null || vcsApiUrl.isEmpty()) {
            vcsApiUrl = "https://api.github.com";
        }
        log.info("Connecting to {}", vcsApiUrl);

        if (ghConnection.get() != null) {
            return ghConnection.get();
        }

        GitHub github;
        try {
            github = new GitHubBuilder()
                    .withEndpoint(vcsApiUrl)
                    .withOAuthToken(System.getenv("JW_GH_TOKEN"))
                    .build();
        } catch (IOException e) {
            throw new JobExecutionException(e);
        }
        ghConnection.set(github);
        return github;
    }

    public static Stream<GHContent> findAndGetFilesFromRepoByName(String organization, String repoName,
                                                                  String branch, WorkflowInterface matchFile) throws IOException {
        GitHub github;
        try {
            github = GitHubReader.init();
        } catch (JobExecutionException e) {
            throw new IOException("Unable to get connection to github and download files");
        }

        var ghRepo = github.getRepository("%s/%s".formatted(organization, repoName));
        if (ghRepo == null) {
            throw new IOException("Unable to retrieve %s/%s".formatted(organization, repoName));
        }
        return ghRepo.getDirectoryContent("/", branch)
                .parallelStream()
                .filter(file -> file.getName().equals(matchFile.getDeclarationFile()));
    }
}
