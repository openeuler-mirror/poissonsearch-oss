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
package org.elasticsearch.script;

import com.google.common.collect.ImmutableSet;
import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.node.settings.NodeSettingsService;
import org.elasticsearch.script.expression.ExpressionScriptEngineService;
import org.elasticsearch.script.groovy.GroovyScriptEngineService;
import org.elasticsearch.search.lookup.SearchLookup;
import org.elasticsearch.test.ElasticsearchTestCase;
import org.elasticsearch.watcher.ResourceWatcherService;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.elasticsearch.common.settings.ImmutableSettings.settingsBuilder;
import static org.hamcrest.Matchers.*;

/**
 *
 */
public class ScriptServiceTests extends ElasticsearchTestCase {

    private ResourceWatcherService resourceWatcherService;
    private ScriptService scriptService;
    private Path scriptsFilePath;

    @Before
    public void setup() throws IOException {
        Path genericConfigFolder = newTempDirPath();

        Settings settings = settingsBuilder()
                .put("path.conf", genericConfigFolder)
                .build();
        Environment environment = new Environment(settings);

        resourceWatcherService = new ResourceWatcherService(settings, null);

        logger.info("--> setup script service");
        scriptService = new ScriptService(settings, environment,
                ImmutableSet.of(new TestEngineService(), new GroovyScriptEngineService(settings), new ExpressionScriptEngineService(settings)),
                resourceWatcherService, new NodeSettingsService(settings));
        scriptsFilePath = genericConfigFolder.resolve("scripts");
        Files.createDirectories(scriptsFilePath);
    }

    @Test
    public void testScriptsWithoutExtensions() throws IOException {

        logger.info("--> setup two test files one with extension and another without");
        Path testFileNoExt = scriptsFilePath.resolve("test_no_ext");
        Path testFileWithExt = scriptsFilePath.resolve("test_script.tst");
        Streams.copy("test_file_no_ext".getBytes("UTF-8"), Files.newOutputStream(testFileNoExt));
        Streams.copy("test_file".getBytes("UTF-8"), Files.newOutputStream(testFileWithExt));
        resourceWatcherService.notifyNow();

        logger.info("--> verify that file with extension was correctly processed");
        CompiledScript compiledScript = scriptService.compile("test", "test_script", ScriptService.ScriptType.FILE);
        assertThat(compiledScript.compiled(), equalTo((Object) "compiled_test_file"));

        logger.info("--> delete both files");
        Files.delete(testFileNoExt);
        Files.delete(testFileWithExt);
        resourceWatcherService.notifyNow();

        logger.info("--> verify that file with extension was correctly removed");
        try {
            scriptService.compile("test", "test_script", ScriptService.ScriptType.FILE);
            fail("the script test_script should no longer exist");
        } catch (ElasticsearchIllegalArgumentException ex) {
            assertThat(ex.getMessage(), containsString("Unable to find on disk script test_script"));
        }
    }

    @Test
    public void testScriptsSameNameDifferentLanguage() throws IOException {
        Path groovyScriptPath = scriptsFilePath.resolve("script.groovy");
        Path expScriptPath = scriptsFilePath.resolve("script.expression");
        Streams.copy("10".getBytes("UTF-8"), Files.newOutputStream(groovyScriptPath));
        Streams.copy("20".getBytes("UTF-8"), Files.newOutputStream(expScriptPath));
        resourceWatcherService.notifyNow();

        CompiledScript groovyScript = scriptService.compile(GroovyScriptEngineService.NAME, "script", ScriptService.ScriptType.FILE);
        assertThat(groovyScript.lang(), equalTo(GroovyScriptEngineService.NAME));
        CompiledScript expressionScript = scriptService.compile(ExpressionScriptEngineService.NAME, "script", ScriptService.ScriptType.FILE);
        assertThat(expressionScript.lang(), equalTo(ExpressionScriptEngineService.NAME));
    }

    @Test
    public void testInlineScriptCompiledOnceMultipleLangAcronyms() throws IOException {
        CompiledScript compiledScript1 = scriptService.compile("test", "test_script", ScriptService.ScriptType.INLINE);
        CompiledScript compiledScript2 = scriptService.compile("test2", "test_script", ScriptService.ScriptType.INLINE);
        assertThat(compiledScript1, sameInstance(compiledScript2));
    }

    @Test
    public void testFileScriptCompiledOnceMultipleLangAcronyms() throws IOException {
        Path scriptPath = scriptsFilePath.resolve("test_script.tst");
        Streams.copy("test_file".getBytes("UTF-8"), Files.newOutputStream(scriptPath));
        resourceWatcherService.notifyNow();

        CompiledScript compiledScript1 = scriptService.compile("test", "test_script", ScriptService.ScriptType.FILE);
        CompiledScript compiledScript2 = scriptService.compile("test2", "test_script", ScriptService.ScriptType.FILE);
        assertThat(compiledScript1, sameInstance(compiledScript2));
    }

    public static class TestEngineService implements ScriptEngineService {

        @Override
        public String[] types() {
            return new String[] {"test", "test2"};
        }

        @Override
        public String[] extensions() {
            return new String[] {"test", "tst"};
        }

        @Override
        public boolean sandboxed() {
            return true;
        }

        @Override
        public Object compile(String script) {
            return "compiled_" + script;
        }

        @Override
        public ExecutableScript executable(final Object compiledScript, @Nullable Map<String, Object> vars) {
            return null;
        }

        @Override
        public SearchScript search(Object compiledScript, SearchLookup lookup, @Nullable Map<String, Object> vars) {
            return null;
        }

        @Override
        public Object execute(Object compiledScript, Map<String, Object> vars) {
            return null;
        }

        @Override
        public Object unwrap(Object value) {
            return null;
        }

        @Override
        public void close() {

        }

        @Override
        public void scriptRemoved(CompiledScript script) {
            // Nothing to do here
        }
    }

}
