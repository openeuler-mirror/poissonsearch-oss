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
package org.elasticsearch.plugins;

import org.apache.http.impl.client.HttpClients;
import org.apache.lucene.util.LuceneTestCase;
import org.elasticsearch.Version;
import org.elasticsearch.common.cli.CliTool;
import org.elasticsearch.common.cli.CliToolTestCase.CaptureOutputTerminal;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.node.internal.InternalSettingsPreparer;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.elasticsearch.test.ElasticsearchIntegrationTest.ClusterScope;
import org.elasticsearch.test.junit.annotations.Network;
import org.elasticsearch.test.rest.client.http.HttpRequestBuilder;
import org.elasticsearch.test.rest.client.http.HttpResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.elasticsearch.common.cli.CliTool.ExitStatus.USAGE;
import static org.elasticsearch.common.cli.CliToolTestCase.args;
import static org.elasticsearch.common.io.FileSystemUtilsTests.assertFileContent;
import static org.elasticsearch.common.settings.Settings.settingsBuilder;
import static org.elasticsearch.test.ElasticsearchIntegrationTest.Scope;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertDirectoryExists;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertFileExists;
import static org.hamcrest.Matchers.*;
import static org.elasticsearch.plugins.PluginInfoTests.writeProperties;

@ClusterScope(scope = Scope.TEST, numDataNodes = 0, transportClientRatio = 0.0)
@LuceneTestCase.SuppressFileSystems("*") // TODO: clean up this test to allow extra files
// TODO: jimfs is really broken here (throws wrong exception from detection method).
// if its in your classpath, then do not use plugins!!!!!!
public class PluginManagerTests extends ElasticsearchIntegrationTest {

    private Tuple<Settings, Environment> initialSettings;
    private CaptureOutputTerminal terminal = new CaptureOutputTerminal();

    @Before
    public void setup() throws Exception {
        initialSettings = buildInitialSettings();
        System.setProperty("es.default.path.home", initialSettings.v1().get("path.home"));
        Path binDir = initialSettings.v2().homeFile().resolve("bin");
        if (!Files.exists(binDir)) {
            Files.createDirectories(binDir);
        }
        Path configDir = initialSettings.v2().configFile();
        if (!Files.exists(configDir)) {
            Files.createDirectories(configDir);
        }
    }

    @After
    public void clearPathHome() {
        System.clearProperty("es.default.path.home");
    }
    
    /** creates a plugin .zip and returns the url for testing */
    private String createPlugin(final Path structure, String... properties) throws IOException {
        writeProperties(structure, properties);
        Path zip = createTempDir().resolve(structure.getFileName() + ".zip");
        try (ZipOutputStream stream = new ZipOutputStream(Files.newOutputStream(zip))) {
            Files.walkFileTree(structure, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    stream.putNextEntry(new ZipEntry(structure.relativize(file).toString()));
                    Files.copy(file, stream);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return zip.toUri().toURL().toString();
    }

    @Test
    public void testThatPluginNameMustBeSupplied() throws IOException {
        Path pluginDir = createTempDir().resolve("fake-plugin");
        String pluginUrl = createPlugin(pluginDir,
            "description", "fake desc",
            "version", "1.0",
            "elasticsearch.version", Version.CURRENT.toString(),
            "jvm", "true",
            "classname", "FakePlugin");
        assertStatus("install --url " + pluginUrl, USAGE);
    }

    @Test
    public void testLocalPluginInstallWithBinAndConfig() throws Exception {
        String pluginName = "fake-plugin";
        Path pluginDir = createTempDir().resolve(pluginName);
        // create bin/tool and config/file
        Files.createDirectories(pluginDir.resolve("bin"));
        Files.createFile(pluginDir.resolve("bin").resolve("tool"));
        Files.createDirectories(pluginDir.resolve("config"));
        Files.createFile(pluginDir.resolve("config").resolve("file"));
        
        String pluginUrl = createPlugin(pluginDir,
            "description", "fake desc",
            "version", "1.0",
            "elasticsearch.version", Version.CURRENT.toString(),
            "java.version", System.getProperty("java.specification.version"),
            "jvm", "true",
            "classname", "FakePlugin");
        
        Environment env = initialSettings.v2();
        Path binDir = env.homeFile().resolve("bin");
        Path pluginBinDir = binDir.resolve(pluginName);

        Path pluginConfigDir = env.configFile().resolve(pluginName);
        assertStatusOk("install " + pluginName + " --url " + pluginUrl + " --verbose");

        terminal.getTerminalOutput().clear();
        assertStatusOk("list");
        assertThat(terminal.getTerminalOutput(), hasItem(containsString(pluginName)));

        assertDirectoryExists(pluginBinDir);
        assertDirectoryExists(pluginConfigDir);
        Path toolFile = pluginBinDir.resolve("tool");
        assertFileExists(toolFile);

        // check that the file is marked executable, without actually checking that we can execute it.
        PosixFileAttributeView view = Files.getFileAttributeView(toolFile, PosixFileAttributeView.class);
        // the view might be null, on e.g. windows, there is nothing to check there!
        if (view != null) {
            PosixFileAttributes attributes = view.readAttributes();
            assertThat(attributes.permissions(), hasItem(PosixFilePermission.OWNER_EXECUTE));
            assertThat(attributes.permissions(), hasItem(PosixFilePermission.OWNER_READ));
        }
    }

    /**
     * Test for #7890
     */
    @Test
    public void testLocalPluginInstallWithBinAndConfigInAlreadyExistingConfigDir_7890() throws Exception {
        String pluginName = "fake-plugin";
        Path pluginDir = createTempDir().resolve(pluginName);
        // create config/test.txt with contents 'version1'
        Files.createDirectories(pluginDir.resolve("config"));
        Files.write(pluginDir.resolve("config").resolve("test.txt"), "version1".getBytes(StandardCharsets.UTF_8));
        
        String pluginUrl = createPlugin(pluginDir,
            "description", "fake desc",
            "version", "1.0",
            "elasticsearch.version", Version.CURRENT.toString(),
            "java.version", System.getProperty("java.specification.version"),
            "jvm", "true",
            "classname", "FakePlugin");
        
        Environment env = initialSettings.v2();
        Path pluginConfigDir = env.configFile().resolve(pluginName);

        assertStatusOk(String.format(Locale.ROOT, "install %s --url %s --verbose", pluginName, pluginUrl));

        /*
        First time, our plugin contains:
        - config/test.txt (version1)
         */
        assertFileContent(pluginConfigDir, "test.txt", "version1");

        // We now remove the plugin
        assertStatusOk("remove " + pluginName);

        // We should still have test.txt
        assertFileContent(pluginConfigDir, "test.txt", "version1");

        // Installing a new plugin version
        /*
        Second time, our plugin contains:
        - config/test.txt (version2)
        - config/dir/testdir.txt (version1)
        - config/dir/subdir/testsubdir.txt (version1)
         */
        Files.write(pluginDir.resolve("config").resolve("test.txt"), "version2".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(pluginDir.resolve("config").resolve("dir").resolve("subdir"));
        Files.write(pluginDir.resolve("config").resolve("dir").resolve("testdir.txt"), "version1".getBytes(StandardCharsets.UTF_8));
        Files.write(pluginDir.resolve("config").resolve("dir").resolve("subdir").resolve("testsubdir.txt"), "version1".getBytes(StandardCharsets.UTF_8));
        pluginUrl = createPlugin(pluginDir,
                "description", "fake desc",
                "version", "2.0",
                "elasticsearch.version", Version.CURRENT.toString(),
                "java.version", System.getProperty("java.specification.version"),
                "jvm", "true",
                "classname", "FakePlugin");
 
        assertStatusOk(String.format(Locale.ROOT, "install %s --url %s --verbose", pluginName, pluginUrl));

        assertFileContent(pluginConfigDir, "test.txt", "version1");
        assertFileContent(pluginConfigDir, "test.txt.new", "version2");
        assertFileContent(pluginConfigDir, "dir/testdir.txt", "version1");
        assertFileContent(pluginConfigDir, "dir/subdir/testsubdir.txt", "version1");

        // Removing
        assertStatusOk("remove " + pluginName);
        assertFileContent(pluginConfigDir, "test.txt", "version1");
        assertFileContent(pluginConfigDir, "test.txt.new", "version2");
        assertFileContent(pluginConfigDir, "dir/testdir.txt", "version1");
        assertFileContent(pluginConfigDir, "dir/subdir/testsubdir.txt", "version1");

        // Installing a new plugin version
        /*
        Third time, our plugin contains:
        - config/test.txt (version3)
        - config/test2.txt (version1)
        - config/dir/testdir.txt (version2)
        - config/dir/testdir2.txt (version1)
        - config/dir/subdir/testsubdir.txt (version2)
         */
        Files.write(pluginDir.resolve("config").resolve("test.txt"), "version3".getBytes(StandardCharsets.UTF_8));
        Files.write(pluginDir.resolve("config").resolve("test2.txt"), "version1".getBytes(StandardCharsets.UTF_8));
        Files.write(pluginDir.resolve("config").resolve("dir").resolve("testdir.txt"), "version2".getBytes(StandardCharsets.UTF_8));
        Files.write(pluginDir.resolve("config").resolve("dir").resolve("testdir2.txt"), "version1".getBytes(StandardCharsets.UTF_8));
        Files.write(pluginDir.resolve("config").resolve("dir").resolve("subdir").resolve("testsubdir.txt"), "version2".getBytes(StandardCharsets.UTF_8));
        pluginUrl = createPlugin(pluginDir,
                "description", "fake desc",
                "version", "3.0",
                "elasticsearch.version", Version.CURRENT.toString(),
                "java.version", System.getProperty("java.specification.version"),
                "jvm", "true",
                "classname", "FakePlugin");

        assertStatusOk(String.format(Locale.ROOT, "install %s --url %s --verbose", pluginName, pluginUrl));

        assertFileContent(pluginConfigDir, "test.txt", "version1");
        assertFileContent(pluginConfigDir, "test2.txt", "version1");
        assertFileContent(pluginConfigDir, "test.txt.new", "version3");
        assertFileContent(pluginConfigDir, "dir/testdir.txt", "version1");
        assertFileContent(pluginConfigDir, "dir/testdir.txt.new", "version2");
        assertFileContent(pluginConfigDir, "dir/testdir2.txt", "version1");
        assertFileContent(pluginConfigDir, "dir/subdir/testsubdir.txt", "version1");
        assertFileContent(pluginConfigDir, "dir/subdir/testsubdir.txt.new", "version2");
    }

    // For #7152
    @Test
    public void testLocalPluginInstallWithBinOnly_7152() throws Exception {
        String pluginName = "fake-plugin";
        Path pluginDir = createTempDir().resolve(pluginName);
        // create bin/tool
        Files.createDirectories(pluginDir.resolve("bin"));
        Files.createFile(pluginDir.resolve("bin").resolve("tool"));;
        String pluginUrl = createPlugin(pluginDir,
            "description", "fake desc",
            "version", "1.0",
            "elasticsearch.version", Version.CURRENT.toString(),
            "java.version", System.getProperty("java.specification.version"),
            "jvm", "true",
            "classname", "FakePlugin");
        
        Environment env = initialSettings.v2();
        Path binDir = env.homeFile().resolve("bin");
        Path pluginBinDir = binDir.resolve(pluginName);

        assertStatusOk(String.format(Locale.ROOT, "install %s --url %s --verbose", pluginName, pluginUrl));
        assertThatPluginIsListed(pluginName);
        assertDirectoryExists(pluginBinDir);
    }

    @Test
    public void testListInstalledEmpty() throws IOException {
        assertStatusOk("list");
        assertThat(terminal.getTerminalOutput(), hasItem(containsString("No plugin detected")));
    }

    @Test
    public void testListInstalledEmptyWithExistingPluginDirectory() throws IOException {
        Files.createDirectory(initialSettings.v2().pluginsFile());
        assertStatusOk("list");
        assertThat(terminal.getTerminalOutput(), hasItem(containsString("No plugin detected")));
    }

    @Test
    public void testInstallPlugin() throws IOException {
        String pluginName = "fake-plugin";
        Path pluginDir = createTempDir().resolve(pluginName);
        String pluginUrl = createPlugin(pluginDir,
            "description", "fake desc",
            "version", "1.0",
            "elasticsearch.version", Version.CURRENT.toString(),
            "java.version", System.getProperty("java.specification.version"),
            "jvm", "true",
            "classname", "FakePlugin");
        assertStatusOk(String.format(Locale.ROOT, "install %s --url %s --verbose", pluginName, pluginUrl));
        assertThatPluginIsListed(pluginName);
    }

    @Test
    public void testInstallSitePlugin() throws IOException {
        String pluginName = "fake-plugin";
        Path pluginDir = createTempDir().resolve(pluginName);
        Files.createDirectories(pluginDir.resolve("_site"));
        Files.createFile(pluginDir.resolve("_site").resolve("somefile"));
        String pluginUrl = createPlugin(pluginDir,
            "description", "fake desc",
            "version", "1.0",
            "site", "true");
        assertStatusOk(String.format(Locale.ROOT, "install %s --url %s --verbose", pluginName, pluginUrl));
        assertThatPluginIsListed(pluginName);
        // We want to check that Plugin Manager moves content to _site
        assertFileExists(initialSettings.v2().pluginsFile().resolve(pluginName).resolve("_site"));
    }


    private void singlePluginInstallAndRemove(String pluginDescriptor, String pluginName, String pluginCoordinates) throws IOException {
        logger.info("--> trying to download and install [{}]", pluginDescriptor);
        if (pluginCoordinates == null) {
            assertStatusOk(String.format(Locale.ROOT, "install %s --verbose", pluginDescriptor));
        } else {
            assertStatusOk(String.format(Locale.ROOT, "install %s --url %s --verbose", pluginDescriptor, pluginCoordinates));
        }
        assertThatPluginIsListed(pluginName);

        terminal.getTerminalOutput().clear();
        assertStatusOk("remove " + pluginDescriptor);
        assertThat(terminal.getTerminalOutput(), hasItem(containsString("Removing " + pluginDescriptor)));

        // not listed anymore
        terminal.getTerminalOutput().clear();
        assertStatusOk("list");
        assertThat(terminal.getTerminalOutput(), not(hasItem(containsString(pluginName))));
    }

    /**
     * We are ignoring by default these tests as they require to have an internet access
     * To activate the test, use -Dtests.network=true
     * We test regular form: username/reponame/version
     * It should find it in download.elasticsearch.org service
     */
    @Test
    @Network
    @AwaitsFix(bugUrl = "fails with jar hell failures - http://build-us-00.elastic.co/job/es_core_master_oracle_6/519/testReport/")
    public void testInstallPluginWithElasticsearchDownloadService() throws IOException {
        assumeTrue("download.elastic.co is accessible", isDownloadServiceWorking("download.elastic.co", 80, "/elasticsearch/ci-test.txt"));
        singlePluginInstallAndRemove("elasticsearch/elasticsearch-transport-thrift/2.4.0", "elasticsearch-transport-thrift", null);
    }

    /**
     * We are ignoring by default these tests as they require to have an internet access
     * To activate the test, use -Dtests.network=true
     * We test regular form: groupId/artifactId/version
     * It should find it in maven central service
     */
    @Test
    @Network
    @AwaitsFix(bugUrl = "fails with jar hell failures - http://build-us-00.elastic.co/job/es_core_master_oracle_6/519/testReport/")
    public void testInstallPluginWithMavenCentral() throws IOException {
        assumeTrue("search.maven.org is accessible", isDownloadServiceWorking("search.maven.org", 80, "/"));
        assumeTrue("repo1.maven.org is accessible", isDownloadServiceWorking("repo1.maven.org", 443, "/maven2/org/elasticsearch/elasticsearch-transport-thrift/2.4.0/elasticsearch-transport-thrift-2.4.0.pom"));
        singlePluginInstallAndRemove("org.elasticsearch/elasticsearch-transport-thrift/2.4.0", "elasticsearch-transport-thrift", null);
    }

    /**
     * We are ignoring by default these tests as they require to have an internet access
     * To activate the test, use -Dtests.network=true
     * We test site plugins from github: userName/repoName
     * It should find it on github
     */
    @Test
    @Network @AwaitsFix(bugUrl = "needs to be adapted to 2.0")
    public void testInstallPluginWithGithub() throws IOException {
        assumeTrue("github.com is accessible", isDownloadServiceWorking("github.com", 443, "/"));
        singlePluginInstallAndRemove("elasticsearch/kibana", "kibana", null);
    }

    private boolean isDownloadServiceWorking(String host, int port, String resource) {
        try {
            String protocol = port == 443 ? "https" : "http";
            HttpResponse response = new HttpRequestBuilder(HttpClients.createDefault()).protocol(protocol).host(host).port(port).path(resource).execute();
            if (response.getStatusCode() != 200) {
                logger.warn("[{}{}] download service is not working. Disabling current test.", host, resource);
                return false;
            }
            return true;
        } catch (Throwable t) {
            logger.warn("[{}{}] download service is not working. Disabling current test.", host, resource);
        }
        return false;
    }

    @Test
    public void testRemovePlugin() throws Exception {
        String pluginName = "plugintest";
        Path pluginDir = createTempDir().resolve(pluginName);        
        String pluginUrl = createPlugin(pluginDir,
            "description", "fake desc",
            "version", "1.0.0",
            "elasticsearch.version", Version.CURRENT.toString(),
            "java.version", System.getProperty("java.specification.version"),
            "jvm", "true",
            "classname", "FakePlugin");
        
        // We want to remove plugin with plugin short name
        singlePluginInstallAndRemove("plugintest", "plugintest", pluginUrl);

        // We want to remove plugin with groupid/artifactid/version form
        singlePluginInstallAndRemove("groupid/plugintest/1.0.0", "plugintest", pluginUrl);

        // We want to remove plugin with groupid/artifactid form
        singlePluginInstallAndRemove("groupid/plugintest", "plugintest", pluginUrl);
    }

    @Test
    public void testRemovePlugin_NullName_ThrowsException() throws IOException {
        int status = new PluginManagerCliParser(terminal).execute(args("remove "));
        assertThat("Terminal output was: " + terminal.getTerminalOutput(), status, is(USAGE.status()));
    }

    @Test
    public void testRemovePluginWithURLForm() throws Exception {
        int status = new PluginManagerCliParser(terminal).execute(args("remove file://whatever"));
        assertThat(terminal.getTerminalOutput(), hasItem(containsString("Illegal plugin name")));
        assertThat("Terminal output was: " + terminal.getTerminalOutput(), status, is(USAGE.status()));
    }

    @Test
    public void testForbiddenPluginNames() throws IOException {
        assertStatus("remove elasticsearch", USAGE);
        assertStatus("remove elasticsearch.bat", USAGE);
        assertStatus("remove elasticsearch.in.sh", USAGE);
        assertStatus("remove plugin", USAGE);
        assertStatus("remove plugin.bat", USAGE);
        assertStatus("remove service.bat", USAGE);
        assertStatus("remove ELASTICSEARCH", USAGE);
        assertStatus("remove ELASTICSEARCH.IN.SH", USAGE);
    }

    @Test
    public void testOfficialPluginName_ThrowsException() throws IOException {
        PluginManager.checkForOfficialPlugins("elasticsearch-analysis-icu");
        PluginManager.checkForOfficialPlugins("elasticsearch-analysis-kuromoji");
        PluginManager.checkForOfficialPlugins("elasticsearch-analysis-phonetic");
        PluginManager.checkForOfficialPlugins("elasticsearch-analysis-smartcn");
        PluginManager.checkForOfficialPlugins("elasticsearch-analysis-stempel");
        PluginManager.checkForOfficialPlugins("elasticsearch-cloud-aws");
        PluginManager.checkForOfficialPlugins("elasticsearch-cloud-azure");
        PluginManager.checkForOfficialPlugins("elasticsearch-cloud-gce");
        PluginManager.checkForOfficialPlugins("elasticsearch-delete-by-query");
        PluginManager.checkForOfficialPlugins("elasticsearch-lang-javascript");
        PluginManager.checkForOfficialPlugins("elasticsearch-lang-python");

        try {
            PluginManager.checkForOfficialPlugins("elasticsearch-mapper-attachment");
            fail("elasticsearch-mapper-attachment should not be allowed");
        } catch (IllegalArgumentException e) {
            // We expect that error
        }
    }

    private Tuple<Settings, Environment> buildInitialSettings() throws IOException {
        Settings settings = settingsBuilder()
                .put("discovery.zen.ping.multicast.enabled", false)
                .put("http.enabled", true)
                .put("path.home", createTempDir()).build();
        return InternalSettingsPreparer.prepareSettings(settings, false);
    }

    private void assertStatusOk(String command) {
        assertStatus(command, CliTool.ExitStatus.OK);
    }

    private void assertStatus(String command, CliTool.ExitStatus exitStatus) {
        int status = new PluginManagerCliParser(terminal).execute(args(command));
        assertThat("Terminal output was: " + terminal.getTerminalOutput(), status, is(exitStatus.status()));
    }

    private void assertThatPluginIsListed(String pluginName) {
        terminal.getTerminalOutput().clear();
        assertStatusOk("list");
        String message = String.format(Locale.ROOT, "Terminal output was: %s", terminal.getTerminalOutput());
        assertThat(message, terminal.getTerminalOutput(), hasItem(containsString(pluginName)));
    }
}
