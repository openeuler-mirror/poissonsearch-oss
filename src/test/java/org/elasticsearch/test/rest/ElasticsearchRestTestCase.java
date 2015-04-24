/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.test.rest;

import com.carrotsearch.randomizedtesting.RandomizedTest;
import com.carrotsearch.randomizedtesting.annotations.Name;
import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;
import com.carrotsearch.randomizedtesting.annotations.TestGroup;
import com.carrotsearch.randomizedtesting.annotations.TimeoutSuite;
import com.google.common.collect.Lists;
import org.apache.lucene.util.LuceneTestCase.Slow;
import org.apache.lucene.util.LuceneTestCase.SuppressCodecs;
import org.apache.lucene.util.LuceneTestCase.SuppressFsync;
import org.apache.lucene.util.TimeUnits;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.PathUtils;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.node.Node;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.elasticsearch.test.ElasticsearchIntegrationTest.ClusterScope;
import org.elasticsearch.test.rest.client.RestException;
import org.elasticsearch.test.rest.parser.RestTestParseException;
import org.elasticsearch.test.rest.parser.RestTestSuiteParser;
import org.elasticsearch.test.rest.section.*;
import org.elasticsearch.test.rest.spec.RestApi;
import org.elasticsearch.test.rest.spec.RestSpec;
import org.elasticsearch.test.rest.support.FileUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.lang.annotation.*;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.*;

/**
 * Runs the clients test suite against an elasticsearch cluster.
 */
@ElasticsearchRestTestCase.Rest
@Slow
@SuppressFsync // we aren't trying to test this here, and it can make the test slow
@SuppressCodecs("*") // requires custom completion postings format
@ClusterScope(randomDynamicTemplates = false)
@TimeoutSuite(millis = 40 * TimeUnits.MINUTE) // timeout the suite after 40min and fail the test.
public abstract class ElasticsearchRestTestCase extends ElasticsearchIntegrationTest {

    /**
     * Property that allows to control whether the REST tests are run (default) or not
     */
    public static final String TESTS_REST = "tests.rest";

    /**
     * Annotation for REST tests
     */
    @Inherited
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @TestGroup(enabled = true, sysProperty = ElasticsearchRestTestCase.TESTS_REST)
    public @interface Rest {
    }

    /**
     * Property that allows to control which REST tests get run. Supports comma separated list of tests
     * or directories that contain tests e.g. -Dtests.rest.suite=index,get,create/10_with_id
     */
    public static final String REST_TESTS_SUITE = "tests.rest.suite";
    /**
     * Property that allows to blacklist some of the REST tests based on a comma separated list of globs
     * e.g. -Dtests.rest.blacklist=get/10_basic/*
     */
    public static final String REST_TESTS_BLACKLIST = "tests.rest.blacklist";
    /**
     * Property that allows to control whether spec validation is enabled or not (default true).
     */
    public static final String REST_TESTS_VALIDATE_SPEC = "tests.rest.validate_spec";
    /**
     * Property that allows to control where the REST spec files need to be loaded from
     */
    public static final String REST_TESTS_SPEC = "tests.rest.spec";

    private static final String DEFAULT_TESTS_PATH = "/rest-api-spec/test";
    private static final String DEFAULT_SPEC_PATH = "/rest-api-spec/api";

    private static final String PATHS_SEPARATOR = ",";

    private final PathMatcher[] blacklistPathMatchers;
    private static RestTestExecutionContext restTestExecutionContext;

    private final RestTestCandidate testCandidate;

    public ElasticsearchRestTestCase(RestTestCandidate testCandidate) {
        this.testCandidate = testCandidate;
        String[] blacklist = resolvePathsProperty(REST_TESTS_BLACKLIST, null);
        if (blacklist != null) {
            blacklistPathMatchers = new PathMatcher[blacklist.length];
            int i = 0;
            for (String glob : blacklist) {
                blacklistPathMatchers[i++] = PathUtils.getDefaultFileSystem().getPathMatcher("glob:" + glob);
            }
        } else {
            blacklistPathMatchers = new PathMatcher[0];
        }
    }

    @Override
    protected void afterIfFailed(List<Throwable> errors) {
        logger.info("Stash dump on failure [{}]", XContentHelper.toString(restTestExecutionContext.stash()));
        super.afterIfFailed(errors);
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return ImmutableSettings.builder()
            .put(Node.HTTP_ENABLED, true)
            .put(super.nodeSettings(nodeOrdinal)).build();
    }
    
    public static Iterable<Object[]> createParameters(int id, int count) throws IOException, RestTestParseException {
        TestGroup testGroup = Rest.class.getAnnotation(TestGroup.class);
        String sysProperty = TestGroup.Utilities.getSysProperty(Rest.class);
        boolean enabled;
        try {
            enabled = RandomizedTest.systemPropertyAsBoolean(sysProperty, testGroup.enabled());
        } catch (IllegalArgumentException e) {
            // Ignore malformed system property, disable the group if malformed though.
            enabled = false;
        }
        if (!enabled) {
            return Lists.newArrayList();
        }
        //parse tests only if rest test group is enabled, otherwise rest tests might not even be available on file system
        List<RestTestCandidate> restTestCandidates = collectTestCandidates(id, count);
        List<Object[]> objects = Lists.newArrayList();
        for (RestTestCandidate restTestCandidate : restTestCandidates) {
            objects.add(new Object[]{restTestCandidate});
        }
        return objects;
    }

    private static List<RestTestCandidate> collectTestCandidates(int id, int count) throws RestTestParseException, IOException {
        String[] paths = resolvePathsProperty(REST_TESTS_SUITE, DEFAULT_TESTS_PATH);
        Map<String, Set<Path>> yamlSuites = FileUtils.findYamlSuites(DEFAULT_TESTS_PATH, paths);

        List<RestTestCandidate> testCandidates = Lists.newArrayList();
        RestTestSuiteParser restTestSuiteParser = new RestTestSuiteParser();
        //yaml suites are grouped by directory (effectively by api)
        for (String api : yamlSuites.keySet()) {
            List<Path> yamlFiles = Lists.newArrayList(yamlSuites.get(api));
            for (Path yamlFile : yamlFiles) {
                String key = api + yamlFile.getFileName().toString();
                if (mustExecute(key, id, count)) {
                    RestTestSuite restTestSuite = restTestSuiteParser.parse(api, yamlFile);
                    for (TestSection testSection : restTestSuite.getTestSections()) {
                        testCandidates.add(new RestTestCandidate(restTestSuite, testSection));
                    }
                }
            }
        }

        //sort the candidates so they will always be in the same order before being shuffled, for repeatability
        Collections.sort(testCandidates, new Comparator<RestTestCandidate>() {
            @Override
            public int compare(RestTestCandidate o1, RestTestCandidate o2) {
                return o1.getTestPath().compareTo(o2.getTestPath());
            }
        });

        return testCandidates;
    }
    
    private static boolean mustExecute(String test, int id, int count) {
        int hash = (int) (Math.abs((long)test.hashCode()) % count);
        return hash == id;
    }

    private static String[] resolvePathsProperty(String propertyName, String defaultValue) {
        String property = System.getProperty(propertyName);
        if (!Strings.hasLength(property)) {
            return defaultValue == null ? null : new String[]{defaultValue};
        } else {
            return property.split(PATHS_SEPARATOR);
        }
    }

    @BeforeClass
    public static void initExecutionContext() throws IOException, RestException {
        String[] specPaths = resolvePathsProperty(REST_TESTS_SPEC, DEFAULT_SPEC_PATH);
        RestSpec restSpec = RestSpec.parseFrom(DEFAULT_SPEC_PATH, specPaths);
        validateSpec(restSpec);
        restTestExecutionContext = new RestTestExecutionContext(restSpec);
    }

    private static void validateSpec(RestSpec restSpec) {
        boolean validateSpec = RandomizedTest.systemPropertyAsBoolean(REST_TESTS_VALIDATE_SPEC, true);
        if (validateSpec) {
            StringBuilder errorMessage = new StringBuilder();
            for (RestApi restApi : restSpec.getApis()) {
                if (restApi.getMethods().contains("GET") && restApi.isBodySupported()) {
                    if (!restApi.getMethods().contains("POST")) {
                        errorMessage.append("\n- ").append(restApi.getName()).append(" supports GET with a body but doesn't support POST");
                    }
                    if (!restApi.getParams().contains("source")) {
                        errorMessage.append("\n- ").append(restApi.getName()).append(" supports GET with a body but doesn't support the source query string parameter");
                    }
                }
            }
            if (errorMessage.length() > 0) {
                throw new IllegalArgumentException(errorMessage.toString());
            }
        }
    }

    @AfterClass
    public static void close() {
        if (restTestExecutionContext != null) {
            restTestExecutionContext.close();
            restTestExecutionContext = null;
        }
    }

    @Override
    protected int maximumNumberOfShards() {
        return 3; // never go crazy in the REST tests
    }

    @Override
    protected int maximumNumberOfReplicas() {
        return 1; // never go crazy in the REST tests
    }

    /**
     * Used to obtain settings for the REST client that is used to send REST requests.
     */
    protected Settings restClientSettings() {
        return ImmutableSettings.EMPTY;
    }

    @Before
    public void reset() throws IOException, RestException {
        //skip test if it matches one of the blacklist globs
        for (PathMatcher blacklistedPathMatcher : blacklistPathMatchers) {
            //we need to replace a few characters otherwise the test section name can't be parsed as a path on windows
            String testSection = testCandidate.getTestSection().getName().replace("*", "").replace("\\", "/").replaceAll("\\s+/", "/").trim();
            String testPath = testCandidate.getSuitePath() + "/" + testSection;
            assumeFalse("[" + testCandidate.getTestPath() + "] skipped, reason: blacklisted", blacklistedPathMatcher.matches(PathUtils.get(testPath)));
        }
        //The client needs non static info to get initialized, therefore it can't be initialized in the before class
        restTestExecutionContext.initClient(cluster().httpAddresses(), restClientSettings());
        restTestExecutionContext.clear();

        //skip test if the whole suite (yaml file) is disabled
        assumeFalse(buildSkipMessage(testCandidate.getSuitePath(), testCandidate.getSetupSection().getSkipSection()),
                testCandidate.getSetupSection().getSkipSection().skip(restTestExecutionContext.esVersion()));
        //skip test if test section is disabled
        assumeFalse(buildSkipMessage(testCandidate.getTestPath(), testCandidate.getTestSection().getSkipSection()),
                testCandidate.getTestSection().getSkipSection().skip(restTestExecutionContext.esVersion()));
    }

    private static String buildSkipMessage(String description, SkipSection skipSection) {
        StringBuilder messageBuilder = new StringBuilder();
        if (skipSection.isVersionCheck()) {
            messageBuilder.append("[").append(description).append("] skipped, reason: [").append(skipSection.getReason()).append("] ");
        } else {
            messageBuilder.append("[").append(description).append("] skipped, reason: features ").append(skipSection.getFeatures()).append(" not supported");
        }
        return messageBuilder.toString();
    }

    @Test
    public void test() throws IOException {
        //let's check that there is something to run, otherwise there might be a problem with the test section
        if (testCandidate.getTestSection().getExecutableSections().size() == 0) {
            throw new IllegalArgumentException("No executable sections loaded for [" + testCandidate.getTestPath() + "]");
        }

        if (!testCandidate.getSetupSection().isEmpty()) {
            logger.info("start setup test [{}]", testCandidate.getTestPath());
            for (DoSection doSection : testCandidate.getSetupSection().getDoSections()) {
                doSection.execute(restTestExecutionContext);
            }
            logger.info("end setup test [{}]", testCandidate.getTestPath());
        }

        restTestExecutionContext.clear();

        for (ExecutableSection executableSection : testCandidate.getTestSection().getExecutableSections()) {
            executableSection.execute(restTestExecutionContext);
        }
    }
}
