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

package org.elasticsearch.packaging.test;

import com.carrotsearch.randomizedtesting.JUnit3MethodProvider;
import com.carrotsearch.randomizedtesting.RandomizedRunner;
import com.carrotsearch.randomizedtesting.annotations.TestCaseOrdering;
import com.carrotsearch.randomizedtesting.annotations.TestGroup;
import com.carrotsearch.randomizedtesting.annotations.TestMethodProviders;
import com.carrotsearch.randomizedtesting.annotations.Timeout;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.CheckedConsumer;
import org.elasticsearch.core.internal.io.IOUtils;
import org.elasticsearch.packaging.util.Archives;
import org.elasticsearch.packaging.util.Distribution;
import org.elasticsearch.packaging.util.Docker;
import org.elasticsearch.packaging.util.FileUtils;
import org.elasticsearch.packaging.util.Installation;
import org.elasticsearch.packaging.util.Packages;
import org.elasticsearch.packaging.util.Platforms;
import org.elasticsearch.packaging.util.Shell;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.TestName;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.List;

import static org.elasticsearch.packaging.util.Cleanup.cleanEverything;
import static org.elasticsearch.packaging.util.Docker.ensureImageIsLoaded;
import static org.elasticsearch.packaging.util.Docker.removeContainer;
import static org.elasticsearch.packaging.util.FileExistenceMatchers.fileExists;
import static org.elasticsearch.packaging.util.FileUtils.append;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

/**
 * Class that all packaging test cases should inherit from
 */
@RunWith(RandomizedRunner.class)
@TestMethodProviders({ JUnit3MethodProvider.class })
@Timeout(millis = 20 * 60 * 1000) // 20 min
@TestCaseOrdering(TestCaseOrdering.AlphabeticOrder.class)
public abstract class PackagingTestCase extends Assert {

    /**
     * Annotation for tests which exhibit a known issue and are temporarily disabled.
     */
    @Documented
    @Inherited
    @Retention(RetentionPolicy.RUNTIME)
    @TestGroup(enabled = false, sysProperty = "tests.awaitsfix")
    @interface AwaitsFix {
        /** Point to JIRA entry. */
        String bugUrl();
    }

    protected final Logger logger = LogManager.getLogger(getClass());

    // the distribution being tested
    protected static final Distribution distribution;
    static {
        distribution = new Distribution(Paths.get(System.getProperty("tests.distribution")));
    }

    // the java installation already installed on the system
    protected static final String systemJavaHome;
    static {
        Shell sh = new Shell();
        if (Platforms.WINDOWS) {
            systemJavaHome = sh.run("$Env:SYSTEM_JAVA_HOME").stdout.trim();
        } else {
            assert Platforms.LINUX || Platforms.DARWIN;
            systemJavaHome = sh.run("echo $SYSTEM_JAVA_HOME").stdout.trim();
        }
    }

    // the current installation of the distribution being tested
    protected static Installation installation;

    private static boolean failed;

    @Rule
    public final TestWatcher testFailureRule = new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
            failed = true;
        }
    };

    // a shell to run system commands with
    protected static Shell sh;

    @Rule
    public final TestName testNameRule = new TestName();

    @BeforeClass
    public static void init() throws Exception {
        assumeTrue("only compatible distributions", distribution.packaging.compatible);

        // make sure temp dir exists
        if (Files.exists(getRootTempDir()) == false) {
            Files.createDirectories(getRootTempDir());
        }

        // cleanup from previous test
        cleanup();

        // create shell
        if (distribution().isDocker()) {
            ensureImageIsLoaded(distribution);
            sh = new Docker.DockerShell();
        } else {
            sh = new Shell();
        }
    }

    @AfterClass
    public static void cleanupDocker() {
        if (distribution().isDocker()) {
            // runContainer also calls this, so we don't need this method to be annotated as `@After`
            removeContainer();
        }
    }

    @Before
    public void setup() throws Exception {
        assumeFalse(failed); // skip rest of tests once one fails

        sh.reset();
        if (distribution().hasJdk == false) {
            Platforms.onLinux(() -> sh.getEnv().put("JAVA_HOME", systemJavaHome));
            Platforms.onWindows(() -> sh.getEnv().put("JAVA_HOME", systemJavaHome));
        }
    }

    @After
    public void teardown() throws Exception {
        if (installation != null && failed == false) {
            if (Files.exists(installation.logs)) {
                Path logFile = installation.logs.resolve("elasticsearch.log");
                if (Files.exists(logFile)) {
                    logger.warn("Elasticsearch log:\n" + FileUtils.slurpAllLogs(installation.logs, "elasticsearch.log", "*.log.gz"));
                }

                // move log file so we can avoid false positives when grepping for
                // messages in logs during test
                String prefix = this.getClass().getSimpleName() + "." + testNameRule.getMethodName();
                if (Files.exists(logFile)) {
                    Path newFile = installation.logs.resolve(prefix + ".elasticsearch.log");
                    FileUtils.mv(logFile, newFile);
                }
                for (Path rotatedLogFile : FileUtils.lsGlob(installation.logs, "elasticsearch*.tar.gz")) {
                    Path newRotatedLogFile = installation.logs.resolve(prefix + "." + rotatedLogFile.getFileName());
                    FileUtils.mv(rotatedLogFile, newRotatedLogFile);
                }
            }
            if (Files.exists(Archives.getPowershellErrorPath(installation))) {
                FileUtils.rmWithRetries(Archives.getPowershellErrorPath(installation));
            }
        }

    }

    /** The {@link Distribution} that should be tested in this case */
    protected static Distribution distribution() {
        return distribution;
    }

    protected static void install() throws Exception {
        switch (distribution.packaging) {
            case TAR:
            case ZIP:
                installation = Archives.installArchive(sh, distribution);
                Archives.verifyArchiveInstallation(installation, distribution);
                break;
            case DEB:
            case RPM:
                installation = Packages.installPackage(sh, distribution);
                Packages.verifyPackageInstallation(installation, distribution, sh);
                break;
            case DOCKER:
            case DOCKER_UBI:
                installation = Docker.runContainer(distribution);
                Docker.verifyContainerInstallation(installation, distribution);
                break;
            default:
                throw new IllegalStateException("Unknown Elasticsearch packaging type.");
        }
    }

    protected static void cleanup() throws Exception {
        installation = null;
        cleanEverything();
    }

    /**
     * Starts and stops elasticsearch, and performs assertions while it is running.
     */
    protected void assertWhileRunning(Platforms.PlatformAction assertions) throws Exception {
        try {
            awaitElasticsearchStartup(runElasticsearchStartCommand(null, true, false));
        } catch (Exception e) {
            if (Files.exists(installation.home.resolve("elasticsearch.pid"))) {
                String pid = FileUtils.slurp(installation.home.resolve("elasticsearch.pid")).trim();
                logger.info("Dumping jstack of elasticsearch processb ({}) that failed to start", pid);
                sh.runIgnoreExitCode("jstack " + pid);
            }
            if (Files.exists(installation.logs.resolve("elasticsearch.log"))) {
                logger.warn("Elasticsearch log:\n" + FileUtils.slurpAllLogs(installation.logs, "elasticsearch.log", "*.log.gz"));
            }
            if (Files.exists(installation.logs.resolve("output.out"))) {
                logger.warn("Stdout:\n" + FileUtils.slurpTxtorGz(installation.logs.resolve("output.out")));
            }
            if (Files.exists(installation.logs.resolve("output.err"))) {
                logger.warn("Stderr:\n" + FileUtils.slurpTxtorGz(installation.logs.resolve("output.err")));
            }
            throw e;
        }

        try {
            assertions.run();
        } catch (Exception e) {
            logger.warn("Elasticsearch log:\n" + FileUtils.slurpAllLogs(installation.logs, "elasticsearch.log", "*.log.gz"));
            throw e;
        }
        stopElasticsearch();
    }

    /**
     * Run the command to start Elasticsearch, but don't wait or test for success.
     * This method is useful for testing failure conditions in startup. To await success,
     * use {@link #startElasticsearch()}.
     * @param password Password for password-protected keystore, null for no password;
     *                 this option will fail for non-archive distributions
     * @param daemonize Run Elasticsearch in the background
     * @param useTty Use a tty for inputting the password rather than standard input;
     *               this option will fail for non-archive distributions
     * @return Shell results of the startup command.
     * @throws Exception when command fails immediately.
     */
    public Shell.Result runElasticsearchStartCommand(String password, boolean daemonize, boolean useTty) throws Exception {
        if (password != null) {
            assertTrue("Only archives support user-entered passwords", distribution().isArchive());
        }

        switch (distribution.packaging) {
            case TAR:
            case ZIP:
                if (useTty) {
                    return Archives.startElasticsearchWithTty(installation, sh, password, daemonize);
                } else {
                    return Archives.runElasticsearchStartCommand(installation, sh, password, daemonize);
                }
            case DEB:
            case RPM:
                return Packages.runElasticsearchStartCommand(sh);
            case DOCKER:
            case DOCKER_UBI:
                // nothing, "installing" docker image is running it
                return Shell.NO_OP;
            default:
                throw new IllegalStateException("Unknown Elasticsearch packaging type.");
        }
    }

    public void stopElasticsearch() throws Exception {
        switch (distribution.packaging) {
            case TAR:
            case ZIP:
                Archives.stopElasticsearch(installation);
                break;
            case DEB:
            case RPM:
                Packages.stopElasticsearch(sh);
                break;
            case DOCKER:
            case DOCKER_UBI:
                // nothing, "installing" docker image is running it
                break;
            default:
                throw new IllegalStateException("Unknown Elasticsearch packaging type.");
        }
    }

    public void awaitElasticsearchStartup(Shell.Result result) throws Exception {
        assertThat("Startup command should succeed", result.exitCode, equalTo(0));
        switch (distribution.packaging) {
            case TAR:
            case ZIP:
                Archives.assertElasticsearchStarted(installation);
                break;
            case DEB:
            case RPM:
                Packages.assertElasticsearchStarted(sh, installation);
                break;
            case DOCKER:
            case DOCKER_UBI:
                Docker.waitForElasticsearchToStart();
                break;
            default:
                throw new IllegalStateException("Unknown Elasticsearch packaging type.");
        }
    }

    /**
     * Start Elasticsearch and wait until it's up and running. If you just want to run
     * the start command, use {@link #runElasticsearchStartCommand(String, boolean, boolean)}.
     * @throws Exception if Elasticsearch can't start
     */
    public void startElasticsearch() throws Exception {
        awaitElasticsearchStartup(runElasticsearchStartCommand(null, true, false));
    }

    public void assertElasticsearchFailure(Shell.Result result, String expectedMessage, Packages.JournaldWrapper journaldWrapper) {
        assertElasticsearchFailure(result, Collections.singletonList(expectedMessage), journaldWrapper);
    }

    public void assertElasticsearchFailure(Shell.Result result, List<String> expectedMessages, Packages.JournaldWrapper journaldWrapper) {
        @SuppressWarnings("unchecked")
        Matcher<String>[] stringMatchers = expectedMessages.stream().map(CoreMatchers::containsString).toArray(Matcher[]::new);
        if (Files.exists(installation.logs.resolve("elasticsearch.log"))) {

            // If log file exists, then we have bootstrapped our logging and the
            // error should be in the logs
            assertThat(installation.logs.resolve("elasticsearch.log"), fileExists());
            String logfile = FileUtils.slurp(installation.logs.resolve("elasticsearch.log"));

            assertThat(logfile, anyOf(stringMatchers));

        } else if (distribution().isPackage() && Platforms.isSystemd()) {

            // For systemd, retrieve the error from journalctl
            assertThat(result.stderr, containsString("Job for poissonsearch.service failed"));
            Shell.Result error = journaldWrapper.getLogs();
            assertThat(error.stdout, anyOf(stringMatchers));

        } else if (Platforms.WINDOWS && Files.exists(Archives.getPowershellErrorPath(installation))) {

            // In Windows, we have written our stdout and stderr to files in order to run
            // in the background
            String wrapperPid = result.stdout.trim();
            sh.runIgnoreExitCode("Wait-Process -Timeout " + Archives.ES_STARTUP_SLEEP_TIME_SECONDS + " -Id " + wrapperPid);
            sh.runIgnoreExitCode(
                "Get-EventSubscriber | "
                    + "where {($_.EventName -eq 'OutputDataReceived' -Or $_.EventName -eq 'ErrorDataReceived' |"
                    + "Unregister-EventSubscriber -Force"
            );
            assertThat(FileUtils.slurp(Archives.getPowershellErrorPath(installation)), anyOf(stringMatchers));

        } else {

            // Otherwise, error should be on shell stderr
            assertThat(result.stderr, anyOf(stringMatchers));
        }
    }

    public static Path getRootTempDir() {
        if (distribution().isPackage()) {
            // The custom config directory is not under /tmp or /var/tmp because
            // systemd's private temp directory functionally means different
            // processes can have different views of what's in these directories
            return Paths.get("/var/test-tmp").toAbsolutePath();
        } else {
            // vagrant creates /tmp for us in windows so we use that to avoid long paths
            return Paths.get("/tmp").toAbsolutePath();
        }
    }

    private static final FileAttribute<?>[] NEW_DIR_PERMS;
    static {
        if (Platforms.WINDOWS) {
            NEW_DIR_PERMS = new FileAttribute<?>[0];
        } else {
            NEW_DIR_PERMS = new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")) };
        }
    }

    public static Path createTempDir(String prefix) throws IOException {
        return Files.createTempDirectory(getRootTempDir(), prefix, NEW_DIR_PERMS);
    }

    /**
     * Run the given action with a temporary copy of the config directory.
     *
     * Files under the path passed to the action may be modified as necessary for the
     * test to execute, and running Elasticsearch with {@link #startElasticsearch()} will
     * use the temporary directory.
     */
    public void withCustomConfig(CheckedConsumer<Path, Exception> action) throws Exception {
        Path tempDir = createTempDir("custom-config");
        Path tempConf = tempDir.resolve("elasticsearch");
        FileUtils.copyDirectory(installation.config, tempConf);

        Platforms.onLinux(() -> sh.run("chown -R elasticsearch:elasticsearch " + tempDir));

        if (distribution.isPackage()) {
            Files.copy(installation.envFile, tempDir.resolve("elasticsearch.bk"));// backup
            append(installation.envFile, "ES_PATH_CONF=" + tempConf + "\n");
        } else {
            sh.getEnv().put("ES_PATH_CONF", tempConf.toString());
        }

        action.accept(tempConf);
        if (distribution.isPackage()) {
            IOUtils.rm(installation.envFile);
            Files.copy(tempDir.resolve("elasticsearch.bk"), installation.envFile);
        } else {
            sh.getEnv().remove("ES_PATH_CONF");
        }
        IOUtils.rm(tempDir);
    }
}
