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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.script.ScriptService.ScriptType;
import org.elasticsearch.script.expression.ExpressionScriptEngineService;
import org.elasticsearch.script.groovy.GroovyScriptEngineService;
import org.elasticsearch.script.mustache.MustacheScriptEngineService;
import org.elasticsearch.search.lookup.SearchLookup;
import org.elasticsearch.test.ElasticsearchTestCase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;

public class ScriptModesTests extends ElasticsearchTestCase {

    private static final Set<String> ALL_LANGS = ImmutableSet.of(GroovyScriptEngineService.NAME, MustacheScriptEngineService.NAME, ExpressionScriptEngineService.NAME, "custom", "test");

    static final String[] ENABLE_VALUES = new String[]{"on", "true", "yes", "1"};
    static final String[] DISABLE_VALUES = new String[]{"off", "false", "no", "0"};

    private Map<String, ScriptEngineService> scriptEngines;
    private ScriptModes scriptModes;
    private Set<String> checkedSettings;
    private boolean assertAllSettingsWereChecked;
    private boolean assertScriptModesNonNull;

    @Before
    public void setupScriptEngines() {
        scriptEngines = buildScriptEnginesByLangMap(ImmutableSet.of(
                new GroovyScriptEngineService(ImmutableSettings.EMPTY),
                new MustacheScriptEngineService(ImmutableSettings.EMPTY),
                new ExpressionScriptEngineService(ImmutableSettings.EMPTY),
                //add the native engine just to make sure it gets filtered out
                new NativeScriptEngineService(ImmutableSettings.EMPTY, Collections.<String, NativeScriptFactory>emptyMap()),
                new CustomScriptEngineService()));
        checkedSettings = new HashSet<>();
        assertAllSettingsWereChecked = true;
        assertScriptModesNonNull = true;
    }

    @After
    public void assertNativeScriptsAreAlwaysAllowed() {
        if (assertScriptModesNonNull) {
            assertThat(scriptModes.getScriptMode(NativeScriptEngineService.NAME, randomFrom(ScriptType.values()), randomFrom(ScriptContext.values())), equalTo(ScriptMode.ON));
        }
    }

    @After
    public void assertAllSettingsWereChecked() {
        if (assertScriptModesNonNull) {
            assertThat(scriptModes, notNullValue());
            //4 is the number of engines (native excluded), custom is counted twice though as it's associated with two different names
            int numberOfSettings = 5 * ScriptType.values().length * ScriptContext.values().length;
            assertThat(scriptModes.scriptModes.size(), equalTo(numberOfSettings));
            if (assertAllSettingsWereChecked) {
                assertThat(checkedSettings.size(), equalTo(numberOfSettings));
            }
        }
    }

    @Test
    public void testDefaultSettings() {
        this.scriptModes = new ScriptModes(scriptEngines, ImmutableSettings.EMPTY, Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllOps(ScriptMode.ON, ALL_LANGS, ScriptType.FILE);
        assertScriptModesAllOps(ScriptMode.SANDBOX, ALL_LANGS, ScriptType.INDEXED, ScriptType.INLINE);
    }

    @Test
    public void testDefaultSettingsDisableDynamicTrue() {
        //verify that disable_dynamic setting gets still read and applied, iff new settings are not present
        this.scriptModes = new ScriptModes(scriptEngines, ImmutableSettings.builder().put(ScriptService.DISABLE_DYNAMIC_SCRIPTING_SETTING, randomFrom("true", "all")).build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllOps(ScriptMode.ON, ALL_LANGS, ScriptType.FILE);
        assertScriptModesAllOps(ScriptMode.OFF, ALL_LANGS, ScriptType.INDEXED, ScriptType.INLINE);
    }

    @Test
    public void testDefaultSettingsEnableDynamicFalse() {
        //verify that disable_dynamic setting gets still read and applied, iff new settings are not present
        this.scriptModes = new ScriptModes(scriptEngines, ImmutableSettings.builder().put(ScriptService.DISABLE_DYNAMIC_SCRIPTING_SETTING, randomFrom("false", "none")).build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllOps(ScriptMode.ON, ALL_LANGS, ScriptType.FILE, ScriptType.INDEXED, ScriptType.INLINE);
    }

    @Test
    public void testDefaultSettingsDisableDynamicSandbox() {
        //verify that disable_dynamic setting gets still read and applied, iff new settings are not present
        this.scriptModes = new ScriptModes(scriptEngines, ImmutableSettings.builder().put(ScriptService.DISABLE_DYNAMIC_SCRIPTING_SETTING, ScriptMode.SANDBOX).build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllOps(ScriptMode.ON, ALL_LANGS, ScriptType.FILE);
        assertScriptModesAllOps(ScriptMode.SANDBOX, ALL_LANGS, ScriptType.INDEXED, ScriptType.INLINE);
    }

    @Test
    public void testConflictingSettings() {
        assertScriptModesNonNull = false;
        ImmutableSettings.Builder builder = ImmutableSettings.builder()
                .put(ScriptService.DISABLE_DYNAMIC_SCRIPTING_SETTING, randomFrom("all", "true", "none", "false", "sandbox", "sandboxed"));

        int iterations = randomIntBetween(1, 5);
        for (int i = 0; i < iterations; i++) {
            if (randomBoolean()) {
                builder.put("script." + randomFrom(ScriptType.values()), randomFrom(ScriptMode.values()));
            } else {
                if (randomBoolean()) {
                    builder.put(ScriptModes.SCRIPT_SETTINGS_PREFIX + randomFrom(ScriptContext.values()), randomFrom(ScriptMode.values()));
                } else {
                    builder.put(specificEngineOpSettings(GroovyScriptEngineService.NAME, randomFrom(ScriptType.values()), randomFrom(ScriptContext.values())), randomFrom(ScriptMode.values()));
                }
            }
        }

        Settings settings = builder.build();
        try {
            this.scriptModes = new ScriptModes(scriptEngines, settings, Loggers.getLogger(ScriptModesTests.class));
            fail("ScriptModes should have thrown an error due to conflicting settings");
        } catch(ElasticsearchIllegalArgumentException e) {
            assertThat(e.getMessage(), containsString("conflicting scripting settings have been specified"));
            for (Map.Entry<String, String> scriptSettingEntry : settings.getAsSettings("script").getAsMap().entrySet()) {
                assertThat(e.getMessage(), containsString(ScriptModes.SCRIPT_SETTINGS_PREFIX + scriptSettingEntry.getKey() + ": " + scriptSettingEntry.getValue()));
            }
        }
    }

    @Test(expected = ElasticsearchIllegalArgumentException.class)
    public void testMissingSetting() {
        assertAllSettingsWereChecked = false;
        this.scriptModes = new ScriptModes(scriptEngines, ImmutableSettings.EMPTY, Loggers.getLogger(ScriptModesTests.class));
        scriptModes.getScriptMode("non_existing", randomFrom(ScriptType.values()), randomFrom(ScriptContext.values()));
    }

    @Test
    public void testEnableInlineGenericSettings() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put("script.inline", randomFrom(ENABLE_VALUES));
        this.scriptModes = new ScriptModes(scriptEngines, builder.build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllOps(ScriptMode.ON, ALL_LANGS, ScriptType.FILE, ScriptType.INLINE);
        assertScriptModesAllOps(ScriptMode.SANDBOX, ALL_LANGS, ScriptType.INDEXED);
    }

    @Test
    public void testDisableInlineGenericSettings() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put("script.inline", randomFrom(DISABLE_VALUES));
        this.scriptModes = new ScriptModes(scriptEngines, builder.build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllOps(ScriptMode.ON, ALL_LANGS, ScriptType.FILE);
        assertScriptModesAllOps(ScriptMode.SANDBOX, ALL_LANGS, ScriptType.INDEXED);
        assertScriptModesAllOps(ScriptMode.OFF, ALL_LANGS, ScriptType.INLINE);
    }

    @Test
    public void testSandboxInlineGenericSettings() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put("script.inline", randomFrom(ScriptMode.SANDBOX));
        //nothing changes if setting set is same as default
        this.scriptModes = new ScriptModes(scriptEngines, builder.build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllOps(ScriptMode.ON, ALL_LANGS, ScriptType.FILE);
        assertScriptModesAllOps(ScriptMode.SANDBOX, ALL_LANGS, ScriptType.INDEXED, ScriptType.INLINE);
    }

    @Test
    public void testEnableIndexedGenericSettings() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put("script.indexed", randomFrom(ENABLE_VALUES));
        this.scriptModes = new ScriptModes(scriptEngines, builder.build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllOps(ScriptMode.ON, ALL_LANGS, ScriptType.FILE, ScriptType.INDEXED);
        assertScriptModesAllOps(ScriptMode.SANDBOX, ALL_LANGS, ScriptType.INLINE);
    }

    @Test
    public void testDisableIndexedGenericSettings() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put("script.indexed", randomFrom(DISABLE_VALUES));
        this.scriptModes = new ScriptModes(scriptEngines, builder.build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllOps(ScriptMode.ON, ALL_LANGS, ScriptType.FILE);
        assertScriptModesAllOps(ScriptMode.OFF, ALL_LANGS, ScriptType.INDEXED);
        assertScriptModesAllOps(ScriptMode.SANDBOX, ALL_LANGS, ScriptType.INLINE);
    }

    @Test
    public void testSandboxIndexedGenericSettings() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put("script.indexed", ScriptMode.SANDBOX);
        //nothing changes if setting set is same as default
        this.scriptModes = new ScriptModes(scriptEngines, builder.build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllOps(ScriptMode.ON, ALL_LANGS, ScriptType.FILE);
        assertScriptModesAllOps(ScriptMode.SANDBOX, ALL_LANGS, ScriptType.INDEXED, ScriptType.INLINE);
    }

    @Test
    public void testEnableFileGenericSettings() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put("script.file", randomFrom(ENABLE_VALUES));
        //nothing changes if setting set is same as default
        this.scriptModes = new ScriptModes(scriptEngines, builder.build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllOps(ScriptMode.ON, ALL_LANGS, ScriptType.FILE);
        assertScriptModesAllOps(ScriptMode.SANDBOX, ALL_LANGS, ScriptType.INDEXED, ScriptType.INLINE);
    }

    @Test
    public void testDisableFileGenericSettings() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put("script.file", randomFrom(DISABLE_VALUES));
        this.scriptModes = new ScriptModes(scriptEngines, builder.build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllOps(ScriptMode.OFF, ALL_LANGS, ScriptType.FILE);
        assertScriptModesAllOps(ScriptMode.SANDBOX, ALL_LANGS, ScriptType.INDEXED, ScriptType.INLINE);
    }

    @Test
    public void testSandboxFileGenericSettings() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put("script.file", ScriptMode.SANDBOX);
        //nothing changes if setting set is same as default
        this.scriptModes = new ScriptModes(scriptEngines, builder.build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllOps(ScriptMode.SANDBOX, ALL_LANGS, ScriptType.FILE, ScriptType.INDEXED, ScriptType.INLINE);
    }

    @Test
    public void testMultipleScriptTypeGenericSettings() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put("script.file", ScriptMode.SANDBOX).put("script.inline", randomFrom(DISABLE_VALUES));
        this.scriptModes = new ScriptModes(scriptEngines, builder.build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllOps(ScriptMode.SANDBOX, ALL_LANGS, ScriptType.FILE);
        assertScriptModesAllOps(ScriptMode.SANDBOX, ALL_LANGS, ScriptType.INDEXED);
        assertScriptModesAllOps(ScriptMode.OFF, ALL_LANGS, ScriptType.INLINE);
    }

    @Test
    public void testEnableMappingGenericSettings() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put("script.mapping", randomFrom(ENABLE_VALUES));
        this.scriptModes = new ScriptModes(scriptEngines, builder.build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllTypes(ScriptMode.ON, ALL_LANGS, ScriptContext.MAPPING);
        assertScriptModes(ScriptMode.ON, ALL_LANGS, new ScriptType[]{ScriptType.FILE}, ScriptContext.AGGS, ScriptContext.SEARCH, ScriptContext.UPDATE);
        assertScriptModes(ScriptMode.SANDBOX, ALL_LANGS, new ScriptType[]{ScriptType.INDEXED, ScriptType.INLINE}, ScriptContext.AGGS, ScriptContext.SEARCH, ScriptContext.UPDATE);
    }

    @Test
    public void testDisableMappingGenericSettings() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put("script.mapping", randomFrom(DISABLE_VALUES));
        this.scriptModes = new ScriptModes(scriptEngines, builder.build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllTypes(ScriptMode.OFF, ALL_LANGS, ScriptContext.MAPPING);
        assertScriptModes(ScriptMode.ON, ALL_LANGS, new ScriptType[]{ScriptType.FILE}, ScriptContext.AGGS, ScriptContext.SEARCH, ScriptContext.UPDATE);
        assertScriptModes(ScriptMode.SANDBOX, ALL_LANGS, new ScriptType[]{ScriptType.INDEXED, ScriptType.INLINE}, ScriptContext.AGGS, ScriptContext.SEARCH, ScriptContext.UPDATE);
    }

    @Test
    public void testSandboxMappingGenericSettings() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put("script.mapping", ScriptMode.SANDBOX);
        this.scriptModes = new ScriptModes(scriptEngines, builder.build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllTypes(ScriptMode.SANDBOX, ALL_LANGS, ScriptContext.MAPPING);
        assertScriptModes(ScriptMode.ON, ALL_LANGS, new ScriptType[]{ScriptType.FILE}, ScriptContext.AGGS, ScriptContext.SEARCH, ScriptContext.UPDATE);
        assertScriptModes(ScriptMode.SANDBOX, ALL_LANGS, new ScriptType[]{ScriptType.INDEXED, ScriptType.INLINE}, ScriptContext.AGGS, ScriptContext.SEARCH, ScriptContext.UPDATE);
    }

    @Test
    public void testEnableSearchGenericSettings() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put("script.search", randomFrom(ENABLE_VALUES));
        this.scriptModes = new ScriptModes(scriptEngines, builder.build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllTypes(ScriptMode.ON, ALL_LANGS, ScriptContext.SEARCH);
        assertScriptModes(ScriptMode.ON, ALL_LANGS, new ScriptType[]{ScriptType.FILE}, ScriptContext.AGGS, ScriptContext.MAPPING, ScriptContext.UPDATE);
        assertScriptModes(ScriptMode.SANDBOX, ALL_LANGS, new ScriptType[]{ScriptType.INDEXED, ScriptType.INLINE}, ScriptContext.AGGS, ScriptContext.MAPPING, ScriptContext.UPDATE);
    }

    @Test
    public void testDisableSearchGenericSettings() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put("script.search", randomFrom(DISABLE_VALUES));
        this.scriptModes = new ScriptModes(scriptEngines, builder.build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllTypes(ScriptMode.OFF, ALL_LANGS, ScriptContext.SEARCH);
        assertScriptModes(ScriptMode.ON, ALL_LANGS, new ScriptType[]{ScriptType.FILE}, ScriptContext.AGGS, ScriptContext.MAPPING, ScriptContext.UPDATE);
        assertScriptModes(ScriptMode.SANDBOX, ALL_LANGS, new ScriptType[]{ScriptType.INDEXED, ScriptType.INLINE}, ScriptContext.AGGS, ScriptContext.MAPPING, ScriptContext.UPDATE);
    }

    @Test
    public void testSandboxSearchGenericSettings() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put("script.search", ScriptMode.SANDBOX);
        this.scriptModes = new ScriptModes(scriptEngines, builder.build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllTypes(ScriptMode.SANDBOX, ALL_LANGS, ScriptContext.SEARCH);
        assertScriptModes(ScriptMode.ON, ALL_LANGS, new ScriptType[]{ScriptType.FILE}, ScriptContext.AGGS, ScriptContext.MAPPING, ScriptContext.UPDATE);
        assertScriptModes(ScriptMode.SANDBOX, ALL_LANGS, new ScriptType[]{ScriptType.INDEXED, ScriptType.INLINE}, ScriptContext.AGGS, ScriptContext.MAPPING, ScriptContext.UPDATE);
    }

    @Test
    public void testEnableAggsGenericSettings() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put("script.aggs", randomFrom(ENABLE_VALUES));
        this.scriptModes = new ScriptModes(scriptEngines, builder.build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllTypes(ScriptMode.ON, ALL_LANGS, ScriptContext.AGGS);
        assertScriptModes(ScriptMode.ON, ALL_LANGS, new ScriptType[]{ScriptType.FILE}, ScriptContext.SEARCH, ScriptContext.MAPPING, ScriptContext.UPDATE);
        assertScriptModes(ScriptMode.SANDBOX, ALL_LANGS, new ScriptType[]{ScriptType.INDEXED, ScriptType.INLINE}, ScriptContext.SEARCH, ScriptContext.MAPPING, ScriptContext.UPDATE);
    }

    @Test
    public void testDisableAggsGenericSettings() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put("script.aggs", randomFrom(DISABLE_VALUES));
        this.scriptModes = new ScriptModes(scriptEngines, builder.build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllTypes(ScriptMode.OFF, ALL_LANGS, ScriptContext.AGGS);
        assertScriptModes(ScriptMode.ON, ALL_LANGS, new ScriptType[]{ScriptType.FILE}, ScriptContext.SEARCH, ScriptContext.MAPPING, ScriptContext.UPDATE);
        assertScriptModes(ScriptMode.SANDBOX, ALL_LANGS, new ScriptType[]{ScriptType.INDEXED, ScriptType.INLINE}, ScriptContext.SEARCH, ScriptContext.MAPPING, ScriptContext.UPDATE);
    }

    @Test
    public void testSandboxAggsGenericSettings() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put("script.aggs", ScriptMode.SANDBOX);
        this.scriptModes = new ScriptModes(scriptEngines, builder.build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllTypes(ScriptMode.SANDBOX, ALL_LANGS, ScriptContext.AGGS);
        assertScriptModes(ScriptMode.ON, ALL_LANGS, new ScriptType[]{ScriptType.FILE}, ScriptContext.SEARCH, ScriptContext.MAPPING, ScriptContext.UPDATE);
        assertScriptModes(ScriptMode.SANDBOX, ALL_LANGS, new ScriptType[]{ScriptType.INDEXED, ScriptType.INLINE}, ScriptContext.SEARCH, ScriptContext.MAPPING, ScriptContext.UPDATE);
    }

    @Test
    public void testEnableUpdateGenericSettings() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put("script.update", randomFrom(ENABLE_VALUES));
        this.scriptModes = new ScriptModes(scriptEngines, builder.build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllTypes(ScriptMode.ON, ALL_LANGS, ScriptContext.UPDATE);
        assertScriptModes(ScriptMode.ON, ALL_LANGS, new ScriptType[]{ScriptType.FILE}, ScriptContext.SEARCH, ScriptContext.MAPPING, ScriptContext.AGGS);
        assertScriptModes(ScriptMode.SANDBOX, ALL_LANGS, new ScriptType[]{ScriptType.INDEXED, ScriptType.INLINE}, ScriptContext.SEARCH, ScriptContext.MAPPING, ScriptContext.AGGS);
    }

    @Test
    public void testDisableUpdateGenericSettings() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put("script.update", randomFrom(DISABLE_VALUES));
        this.scriptModes = new ScriptModes(scriptEngines, builder.build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllTypes(ScriptMode.OFF, ALL_LANGS, ScriptContext.UPDATE);
        assertScriptModes(ScriptMode.ON, ALL_LANGS, new ScriptType[]{ScriptType.FILE}, ScriptContext.SEARCH, ScriptContext.MAPPING, ScriptContext.AGGS);
        assertScriptModes(ScriptMode.SANDBOX, ALL_LANGS, new ScriptType[]{ScriptType.INDEXED, ScriptType.INLINE}, ScriptContext.SEARCH, ScriptContext.MAPPING, ScriptContext.AGGS);
    }

    @Test
    public void testSandboxUpdateGenericSettings() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put("script.update", ScriptMode.SANDBOX);
        this.scriptModes = new ScriptModes(scriptEngines, builder.build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllTypes(ScriptMode.SANDBOX, ALL_LANGS, ScriptContext.UPDATE);
        assertScriptModes(ScriptMode.ON, ALL_LANGS, new ScriptType[]{ScriptType.FILE}, ScriptContext.SEARCH, ScriptContext.MAPPING, ScriptContext.AGGS);
        assertScriptModes(ScriptMode.SANDBOX, ALL_LANGS, new ScriptType[]{ScriptType.INDEXED, ScriptType.INLINE}, ScriptContext.SEARCH, ScriptContext.MAPPING, ScriptContext.AGGS);
    }

    @Test
    public void testMultipleScriptContextGenericSettings() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put("script.update", ScriptMode.SANDBOX)
                .put("script.aggs", randomFrom(DISABLE_VALUES))
                .put("script.search", randomFrom(ENABLE_VALUES));
        this.scriptModes = new ScriptModes(scriptEngines, builder.build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllTypes(ScriptMode.SANDBOX, ALL_LANGS, ScriptContext.UPDATE);
        assertScriptModesAllTypes(ScriptMode.OFF, ALL_LANGS, ScriptContext.AGGS);
        assertScriptModesAllTypes(ScriptMode.ON, ALL_LANGS, ScriptContext.SEARCH);
        assertScriptModes(ScriptMode.ON, ALL_LANGS, new ScriptType[]{ScriptType.FILE}, ScriptContext.MAPPING);
        assertScriptModes(ScriptMode.SANDBOX, ALL_LANGS, new ScriptType[]{ScriptType.INDEXED, ScriptType.INLINE}, ScriptContext.MAPPING);
    }

    @Test
    public void testConflictingScriptTypeAndOpGenericSettings() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put("script.update", randomFrom(DISABLE_VALUES))
                .put("script.indexed", randomFrom(ENABLE_VALUES)).put("script.inline", ScriptMode.SANDBOX);
        //operations generic settings have precedence over script type generic settings
        this.scriptModes = new ScriptModes(scriptEngines, builder.build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModesAllTypes(ScriptMode.OFF, ALL_LANGS, ScriptContext.UPDATE);
        assertScriptModes(ScriptMode.ON, ALL_LANGS, new ScriptType[]{ScriptType.FILE, ScriptType.INDEXED}, ScriptContext.MAPPING, ScriptContext.AGGS, ScriptContext.SEARCH);
        assertScriptModes(ScriptMode.SANDBOX, ALL_LANGS, new ScriptType[]{ScriptType.INLINE}, ScriptContext.MAPPING, ScriptContext.AGGS, ScriptContext.SEARCH);
    }

    @Test
    public void testEngineSpecificSettings() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder()
                .put(specificEngineOpSettings(GroovyScriptEngineService.NAME, ScriptType.INLINE, ScriptContext.MAPPING), randomFrom(DISABLE_VALUES))
                .put(specificEngineOpSettings(GroovyScriptEngineService.NAME, ScriptType.INLINE, ScriptContext.UPDATE), randomFrom(DISABLE_VALUES));
        ImmutableSet<String> groovyLangSet = ImmutableSet.of(GroovyScriptEngineService.NAME);
        Set<String> allButGroovyLangSet = new HashSet<>(ALL_LANGS);
        allButGroovyLangSet.remove(GroovyScriptEngineService.NAME);
        this.scriptModes = new ScriptModes(scriptEngines, builder.build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModes(ScriptMode.OFF, groovyLangSet, new ScriptType[]{ScriptType.INLINE}, ScriptContext.MAPPING, ScriptContext.UPDATE);
        assertScriptModes(ScriptMode.SANDBOX, groovyLangSet, new ScriptType[]{ScriptType.INLINE}, ScriptContext.SEARCH, ScriptContext.AGGS);
        assertScriptModesAllOps(ScriptMode.SANDBOX, allButGroovyLangSet, ScriptType.INLINE);
        assertScriptModesAllOps(ScriptMode.SANDBOX, ALL_LANGS, ScriptType.INDEXED);
        assertScriptModesAllOps(ScriptMode.ON, ALL_LANGS, ScriptType.FILE);
    }

    @Test
    public void testInteractionBetweenGenericAndEngineSpecificSettings() {
        ImmutableSettings.Builder builder = ImmutableSettings.builder().put("script.inline", randomFrom(DISABLE_VALUES))
                .put(specificEngineOpSettings(MustacheScriptEngineService.NAME, ScriptType.INLINE, ScriptContext.AGGS), randomFrom(ENABLE_VALUES))
                .put(specificEngineOpSettings(MustacheScriptEngineService.NAME, ScriptType.INLINE, ScriptContext.SEARCH), randomFrom(ENABLE_VALUES));
        ImmutableSet<String> mustacheLangSet = ImmutableSet.of(MustacheScriptEngineService.NAME);
        Set<String> allButMustacheLangSet = new HashSet<>(ALL_LANGS);
        allButMustacheLangSet.remove(MustacheScriptEngineService.NAME);
        this.scriptModes = new ScriptModes(scriptEngines, builder.build(), Loggers.getLogger(ScriptModesTests.class));
        assertScriptModes(ScriptMode.ON, mustacheLangSet, new ScriptType[]{ScriptType.INLINE}, ScriptContext.AGGS, ScriptContext.SEARCH);
        assertScriptModes(ScriptMode.OFF, mustacheLangSet, new ScriptType[]{ScriptType.INLINE}, ScriptContext.MAPPING, ScriptContext.UPDATE);
        assertScriptModesAllOps(ScriptMode.OFF, allButMustacheLangSet, ScriptType.INLINE);
        assertScriptModesAllOps(ScriptMode.SANDBOX, ALL_LANGS, ScriptType.INDEXED);
        assertScriptModesAllOps(ScriptMode.ON, ALL_LANGS, ScriptType.FILE);
    }

    @Test
    public void testDefaultSettingsToString() {
        assertAllSettingsWereChecked = false;
        this.scriptModes = new ScriptModes(scriptEngines, ImmutableSettings.EMPTY, Loggers.getLogger(ScriptModesTests.class));
        assertThat(scriptModes.toString(), equalTo(
                        "script.engine.custom.file.aggs: on\n" +
                        "script.engine.custom.file.mapping: on\n" +
                        "script.engine.custom.file.search: on\n" +
                        "script.engine.custom.file.update: on\n" +
                        "script.engine.custom.indexed.aggs: sandbox\n" +
                        "script.engine.custom.indexed.mapping: sandbox\n" +
                        "script.engine.custom.indexed.search: sandbox\n" +
                        "script.engine.custom.indexed.update: sandbox\n" +
                        "script.engine.custom.inline.aggs: sandbox\n" +
                        "script.engine.custom.inline.mapping: sandbox\n" +
                        "script.engine.custom.inline.search: sandbox\n" +
                        "script.engine.custom.inline.update: sandbox\n" +
                        "script.engine.expression.file.aggs: on\n" +
                        "script.engine.expression.file.mapping: on\n" +
                        "script.engine.expression.file.search: on\n" +
                        "script.engine.expression.file.update: on\n" +
                        "script.engine.expression.indexed.aggs: sandbox\n" +
                        "script.engine.expression.indexed.mapping: sandbox\n" +
                        "script.engine.expression.indexed.search: sandbox\n" +
                        "script.engine.expression.indexed.update: sandbox\n" +
                        "script.engine.expression.inline.aggs: sandbox\n" +
                        "script.engine.expression.inline.mapping: sandbox\n" +
                        "script.engine.expression.inline.search: sandbox\n" +
                        "script.engine.expression.inline.update: sandbox\n" +
                        "script.engine.groovy.file.aggs: on\n" +
                        "script.engine.groovy.file.mapping: on\n" +
                        "script.engine.groovy.file.search: on\n" +
                        "script.engine.groovy.file.update: on\n" +
                        "script.engine.groovy.indexed.aggs: sandbox\n" +
                        "script.engine.groovy.indexed.mapping: sandbox\n" +
                        "script.engine.groovy.indexed.search: sandbox\n" +
                        "script.engine.groovy.indexed.update: sandbox\n" +
                        "script.engine.groovy.inline.aggs: sandbox\n" +
                        "script.engine.groovy.inline.mapping: sandbox\n" +
                        "script.engine.groovy.inline.search: sandbox\n" +
                        "script.engine.groovy.inline.update: sandbox\n" +
                        "script.engine.mustache.file.aggs: on\n" +
                        "script.engine.mustache.file.mapping: on\n" +
                        "script.engine.mustache.file.search: on\n" +
                        "script.engine.mustache.file.update: on\n" +
                        "script.engine.mustache.indexed.aggs: sandbox\n" +
                        "script.engine.mustache.indexed.mapping: sandbox\n" +
                        "script.engine.mustache.indexed.search: sandbox\n" +
                        "script.engine.mustache.indexed.update: sandbox\n" +
                        "script.engine.mustache.inline.aggs: sandbox\n" +
                        "script.engine.mustache.inline.mapping: sandbox\n" +
                        "script.engine.mustache.inline.search: sandbox\n" +
                        "script.engine.mustache.inline.update: sandbox\n" +
                        "script.engine.test.file.aggs: on\n" +
                        "script.engine.test.file.mapping: on\n" +
                        "script.engine.test.file.search: on\n" +
                        "script.engine.test.file.update: on\n" +
                        "script.engine.test.indexed.aggs: sandbox\n" +
                        "script.engine.test.indexed.mapping: sandbox\n" +
                        "script.engine.test.indexed.search: sandbox\n" +
                        "script.engine.test.indexed.update: sandbox\n" +
                        "script.engine.test.inline.aggs: sandbox\n" +
                        "script.engine.test.inline.mapping: sandbox\n" +
                        "script.engine.test.inline.search: sandbox\n" +
                        "script.engine.test.inline.update: sandbox\n"));
    }

    private void assertScriptModesAllOps(ScriptMode expectedScriptMode, Set<String> langs, ScriptType... scriptTypes) {
        assertScriptModes(expectedScriptMode, langs, scriptTypes, ScriptContext.values());
    }

    private void assertScriptModesAllTypes(ScriptMode expectedScriptMode, Set<String> langs, ScriptContext... scriptContexts) {
        assertScriptModes(expectedScriptMode, langs, ScriptType.values(), scriptContexts);
    }

    private void assertScriptModes(ScriptMode expectedScriptMode, Set<String> langs, ScriptType[] scriptTypes, ScriptContext... scriptContexts) {
        assert langs.size() > 0;
        assert scriptTypes.length > 0;
        assert scriptContexts.length > 0;
        for (String lang : langs) {
            for (ScriptType scriptType : scriptTypes) {
                for (ScriptContext scriptContext : scriptContexts) {
                    assertThat(lang + "." + scriptType + "." + scriptContext + " doesn't have the expected value", scriptModes.getScriptMode(lang, scriptType, scriptContext), equalTo(expectedScriptMode));
                    checkedSettings.add(lang + "." + scriptType + "." + scriptContext);
                }
            }
        }
    }

    private static String specificEngineOpSettings(String lang, ScriptType scriptType, ScriptContext scriptContext) {
        return ScriptModes.ENGINE_SETTINGS_PREFIX + "." + lang + "." + scriptType + "." + scriptContext;
    }

    static ImmutableMap<String, ScriptEngineService> buildScriptEnginesByLangMap(Set<ScriptEngineService> scriptEngines) {
        ImmutableMap.Builder<String, ScriptEngineService> builder = ImmutableMap.builder();
        for (ScriptEngineService scriptEngine : scriptEngines) {
            for (String type : scriptEngine.types()) {
                builder.put(type, scriptEngine);
            }
        }
        return builder.build();
    }

    private static class CustomScriptEngineService implements ScriptEngineService {
        @Override
        public String[] types() {
            return new String[]{"custom", "test"};
        }

        @Override
        public String[] extensions() {
            return new String[0];
        }

        @Override
        public boolean sandboxed() {
            return false;
        }

        @Override
        public Object compile(String script) {
            return null;
        }

        @Override
        public ExecutableScript executable(Object compiledScript, @Nullable Map<String, Object> vars) {
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
        public void scriptRemoved(@Nullable CompiledScript script) {

        }
    }
}
