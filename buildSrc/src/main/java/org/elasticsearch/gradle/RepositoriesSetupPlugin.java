/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.artifacts.repositories.IvyArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import groovy.lang.Closure;

public class RepositoriesSetupPlugin implements Plugin<Project> {

    private static final List<String> SECURE_URL_SCHEMES = Arrays.asList("file", "https", "s3");
    private static final Pattern LUCENE_SNAPSHOT_REGEX = Pattern.compile("\\w+-snapshot-([a-z0-9]+)");

    @Override
    public void apply(Project project) {
        configureRepositories(project);
    }

    /**
     * Adds repositories used by ES projects and dependencies
     */
    public static void configureRepositories(Project project) {
        System.setProperty("skip_secure_uri_check", "true");
        // ensure all repositories use secure urls
        // TODO: remove this with gradle 7.0, which no longer allows insecure urls
        project.getRepositories().all(repository -> {
            if (repository instanceof MavenArtifactRepository) {
                final MavenArtifactRepository maven = (MavenArtifactRepository) repository;
                assertRepositoryURIIsSecure(maven.getName(), project.getPath(), maven.getUrl());
                for (URI uri : maven.getArtifactUrls()) {
                    assertRepositoryURIIsSecure(maven.getName(), project.getPath(), uri);
                }
            } else if (repository instanceof IvyArtifactRepository) {
                final IvyArtifactRepository ivy = (IvyArtifactRepository) repository;
                assertRepositoryURIIsSecure(ivy.getName(), project.getPath(), ivy.getUrl());
            }
        });
        RepositoryHandler repos = project.getRepositories();
        if (System.getProperty("repos.mavenLocal") != null) {
            // with -Drepos.mavenLocal=true we can force checking the local .m2 repo which is
            // useful for development ie. bwc tests where we install stuff in the local repository
            // such that we don't have to pass hardcoded files to gradle
            repos.mavenLocal();
        }
        repos.maven(mavenArtifactRepository -> {
            mavenArtifactRepository.setName("oss_center");
            mavenArtifactRepository.setUrl("https://cmc.centralrepo.rnd.huawei.com/artifactory/maven-central-repo/");
            mavenArtifactRepository.setAllowInsecureProtocol(true);
        });
        repos.maven(mavenArtifactRepository -> {
            mavenArtifactRepository.setName("opensource");
            mavenArtifactRepository.setUrl("https://maven.repo.cmc.tools.huawei.com/artifactory/maven-oss");
            mavenArtifactRepository.setAllowInsecureProtocol(true);
        });
        repos.maven(mavenArtifactRepository -> {
            mavenArtifactRepository.setName("tool");
            mavenArtifactRepository.setUrl("https://maven.cloudartifact.lfg.dragon.tools.huawei.com/artifactory/cbu-maven-public");
            mavenArtifactRepository.setAllowInsecureProtocol(true);
        });
    }

    private static void assertRepositoryURIIsSecure(final String repositoryName, final String projectPath, final URI uri) {
        if (Objects.equals(System.getProperty("skip_secure_uri_check"), "true")) {
            // for now they don't provide secure uri for artifactory.
            return;
        }

        if (uri != null && SECURE_URL_SCHEMES.contains(uri.getScheme()) == false) {
            String url;
            try {
                url = uri.toURL().toString();
            } catch (MalformedURLException e) {
                throw new IllegalStateException(e);
            }
            final String message = String.format(
                Locale.ROOT,
                "repository [%s] on project with path [%s] is not using a secure protocol for artifacts on [%s]",
                repositoryName,
                projectPath,
                url
            );
            throw new GradleException(message);
        }
    }

}
