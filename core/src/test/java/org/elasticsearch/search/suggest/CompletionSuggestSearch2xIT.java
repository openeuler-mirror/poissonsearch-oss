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
package org.elasticsearch.search.suggest;

import com.carrotsearch.hppc.ObjectLongHashMap;
import com.carrotsearch.randomizedtesting.generators.RandomStrings;

import org.apache.lucene.util.LuceneTestCase.SuppressCodecs;
import org.elasticsearch.Version;
import org.elasticsearch.action.admin.indices.forcemerge.ForceMergeResponse;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingResponse;
import org.elasticsearch.action.admin.indices.segments.IndexShardSegments;
import org.elasticsearch.action.admin.indices.segments.ShardSegments;
import org.elasticsearch.action.admin.indices.stats.IndicesStatsResponse;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchPhaseExecutionException;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.mapper.CompletionFieldMapper2x;
import org.elasticsearch.index.mapper.MapperException;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.Aggregator.SubAggCollectionMode;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.suggest.completion.CompletionStats;
import org.elasticsearch.search.suggest.completion.CompletionSuggestionBuilder;
import org.elasticsearch.search.suggest.completion.FuzzyOptions;
import org.elasticsearch.search.suggest.completion2x.CompletionSuggestion;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.InternalSettingsPlugin;
import org.elasticsearch.test.VersionUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static com.carrotsearch.randomizedtesting.RandomizedTest.getRandom;
import static org.elasticsearch.cluster.metadata.IndexMetaData.SETTING_NUMBER_OF_REPLICAS;
import static org.elasticsearch.cluster.metadata.IndexMetaData.SETTING_NUMBER_OF_SHARDS;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAllSuccessful;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsCollectionContaining.hasItem;
import static org.hamcrest.core.IsInstanceOf.instanceOf;
import static org.hamcrest.core.IsNull.notNullValue;

@SuppressCodecs("*") // requires custom completion format
public class CompletionSuggestSearch2xIT extends ESIntegTestCase {

    private final String INDEX = RandomStrings.randomAsciiOfLength(getRandom(), 10).toLowerCase(Locale.ROOT);
    private final String TYPE = RandomStrings.randomAsciiOfLength(getRandom(), 10).toLowerCase(Locale.ROOT);
    private final String FIELD = RandomStrings.randomAsciiOfLength(getRandom(), 10).toLowerCase(Locale.ROOT);
    private final Version PRE2X_VERSION = VersionUtils.randomVersionBetween(getRandom(), Version.V_2_0_0, Version.V_2_3_1);
    private final CompletionMappingBuilder completionMappingBuilder = new CompletionMappingBuilder();

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Arrays.asList(InternalSettingsPlugin.class);
    }

    public void testSimple() throws Exception {
        createIndexAndMapping(completionMappingBuilder);
        String[][] input = {{"Foo Fighters"}, {"Foo Fighters"}, {"Foo Fighters"}, {"Foo Fighters"},
            {"Generator", "Foo Fighters Generator"}, {"Learn to Fly", "Foo Fighters Learn to Fly"},
            {"The Prodigy"}, {"The Prodigy"}, {"The Prodigy"}, {"Firestarter", "The Prodigy Firestarter"},
            {"Turbonegro"}, {"Turbonegro"}, {"Get it on", "Turbonegro Get it on"}}; // work with frequencies
        for (int i = 0; i < input.length; i++) {
            client().prepareIndex(INDEX, TYPE, "" + i)
                .setSource(jsonBuilder()
                    .startObject().startObject(FIELD)
                    .field("input", input[i])
                    .endObject()
                    .endObject()
                )
                .execute().actionGet();
        }

        refresh();

        assertSuggestionsNotInOrder("f", "Foo Fighters", "Firestarter", "Foo Fighters Generator", "Foo Fighters Learn to Fly");
        assertSuggestionsNotInOrder("t", "The Prodigy", "Turbonegro", "Turbonegro Get it on", "The Prodigy Firestarter");
    }

    public void testBasicPrefixSuggestion() throws Exception {
        completionMappingBuilder.payloads(true);
        createIndexAndMapping(completionMappingBuilder);
        for (int i = 0; i < 2; i++) {
            createData(i == 0);
            assertSuggestions("f", "Firestarter - The Prodigy", "Foo Fighters",
                "Generator - Foo Fighters", "Learn to Fly - Foo Fighters");
            assertSuggestions("ge", "Generator - Foo Fighters", "Get it on - Turbonegro");
            assertSuggestions("ge", "Generator - Foo Fighters", "Get it on - Turbonegro");
            assertSuggestions("t", "The Prodigy", "Firestarter - The Prodigy", "Get it on - Turbonegro", "Turbonegro");
        }
    }

    public void testThatWeightsAreWorking() throws Exception {
        createIndexAndMapping(completionMappingBuilder);

        List<String> similarNames = Arrays.asList("the", "The Prodigy", "The Verve", "The the");
        // the weight is 1000 divided by string length, so the results are easy to to check
        for (String similarName : similarNames) {
            client().prepareIndex(INDEX, TYPE, similarName).setSource(jsonBuilder()
                .startObject().startObject(FIELD)
                .startArray("input").value(similarName).endArray()
                .field("weight", 1000 / similarName.length())
                .endObject().endObject()
            ).get();
        }

        refresh();

        assertSuggestions("the", "the", "The the", "The Verve", "The Prodigy");
    }

    public void testThatWeightMustBeAnInteger() throws Exception {
        createIndexAndMapping(completionMappingBuilder);

        try {
            client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
                .startObject().startObject(FIELD)
                .startArray("input").value("sth").endArray()
                .field("weight", 2.5)
                .endObject().endObject()
            ).get();
            fail("Indexing with a float weight was successful, but should not be");
        } catch (MapperParsingException e) {
            assertThat(e.toString(), containsString("2.5"));
        }
    }

    public void testThatWeightCanBeAString() throws Exception {
        createIndexAndMapping(completionMappingBuilder);

        client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
            .startObject().startObject(FIELD)
            .startArray("input").value("testing").endArray()
            .field("weight", "10")
            .endObject().endObject()
        ).get();

        refresh();

        SearchResponse suggestResponse = client().prepareSearch(INDEX).suggest(
            new SuggestBuilder().addSuggestion("testSuggestions",
                new CompletionSuggestionBuilder(FIELD).text("test").size(10))
        ).execute().actionGet();

        assertSuggestions(suggestResponse, "testSuggestions", "testing");
        Suggest.Suggestion.Entry.Option option = suggestResponse.getSuggest().getSuggestion("testSuggestions")
            .getEntries().get(0).getOptions().get(0);
        assertThat(option, is(instanceOf(CompletionSuggestion.Entry.Option.class)));
        CompletionSuggestion.Entry.Option prefixOption = (CompletionSuggestion.Entry.Option) option;

        assertThat(prefixOption.getText().string(), equalTo("testing"));
        assertThat(prefixOption.getScore(), equalTo(10F));
    }

    public void testThatWeightMustNotBeANonNumberString() throws Exception {
        createIndexAndMapping(completionMappingBuilder);

        try {
            client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
                .startObject().startObject(FIELD)
                .startArray("input").value("sth").endArray()
                .field("weight", "thisIsNotValid")
                .endObject().endObject()
            ).get();
            fail("Indexing with a non-number representing string as weight was successful, but should not be");
        } catch (MapperParsingException e) {
            assertThat(e.toString(), containsString("thisIsNotValid"));
        }
    }

    public void testThatWeightAsStringMustBeInt() throws Exception {
        createIndexAndMapping(completionMappingBuilder);

        String weight = String.valueOf(Long.MAX_VALUE - 4);
        try {
            client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
                .startObject().startObject(FIELD)
                .startArray("input").value("testing").endArray()
                .field("weight", weight)
                .endObject().endObject()
            ).get();
            fail("Indexing with weight string representing value > Int.MAX_VALUE was successful, but should not be");
        } catch (MapperParsingException e) {
            assertThat(e.toString(), containsString(weight));
        }
    }

    public void testThatInputCanBeAStringInsteadOfAnArray() throws Exception {
        createIndexAndMapping(completionMappingBuilder);

        client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
            .startObject().startObject(FIELD)
            .field("input", "Foo Fighters")
            .field("output", "Boo Fighters")
            .endObject().endObject()
        ).get();

        refresh();

        assertSuggestions("f", "Boo Fighters");
    }

    public void testThatPayloadsAreArbitraryJsonObjects() throws Exception {
        completionMappingBuilder.payloads(true);
        createIndexAndMapping(completionMappingBuilder);

        client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
            .startObject().startObject(FIELD)
            .startArray("input").value("Foo Fighters").endArray()
            .field("output", "Boo Fighters")
            .startObject("payload").field("foo", "bar").startArray("test").value("spam").value("eggs")
            .endArray().endObject()
            .endObject().endObject()
        ).get();

        refresh();

        SearchResponse suggestResponse = client().prepareSearch(INDEX).suggest(
            new SuggestBuilder().addSuggestion("testSuggestions",
                new CompletionSuggestionBuilder(FIELD).text("foo").size(10))
        ).execute().actionGet();

        assertSuggestions(suggestResponse, "testSuggestions", "Boo Fighters");
        Suggest.Suggestion.Entry.Option option = suggestResponse.getSuggest()
            .getSuggestion("testSuggestions").getEntries().get(0).getOptions().get(0);
        assertThat(option, is(instanceOf(CompletionSuggestion.Entry.Option.class)));
        CompletionSuggestion.Entry.Option prefixOption = (CompletionSuggestion.Entry.Option) option;
        assertThat(prefixOption.getPayload(), is(notNullValue()));

        // parse JSON
        Map<String, Object> jsonMap = prefixOption.getPayloadAsMap();
        assertThat(jsonMap.size(), is(2));
        assertThat(jsonMap.get("foo").toString(), is("bar"));
        assertThat(jsonMap.get("test"), is(instanceOf(List.class)));
        List<String> listValues = (List<String>) jsonMap.get("test");
        assertThat(listValues, hasItems("spam", "eggs"));
    }

    public void testPayloadAsNumeric() throws Exception {
        completionMappingBuilder.payloads(true);
        createIndexAndMapping(completionMappingBuilder);

        client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
            .startObject().startObject(FIELD)
            .startArray("input").value("Foo Fighters").endArray()
            .field("output", "Boo Fighters")
            .field("payload", 1)
            .endObject().endObject()
        ).get();

        refresh();

        SearchResponse suggestResponse = client().prepareSearch(INDEX).suggest(
            new SuggestBuilder().addSuggestion("testSuggestions",
                new CompletionSuggestionBuilder(FIELD).text("foo").size(10))
        ).execute().actionGet();

        assertSuggestions(suggestResponse, "testSuggestions", "Boo Fighters");
        Suggest.Suggestion.Entry.Option option = suggestResponse.getSuggest()
            .getSuggestion("testSuggestions").getEntries().get(0).getOptions().get(0);
        assertThat(option, is(instanceOf(CompletionSuggestion.Entry.Option.class)));
        CompletionSuggestion.Entry.Option prefixOption = (CompletionSuggestion.Entry.Option) option;
        assertThat(prefixOption.getPayload(), is(notNullValue()));

        assertThat(prefixOption.getPayloadAsLong(), equalTo(1L));
    }

    public void testPayloadAsString() throws Exception {
        completionMappingBuilder.payloads(true);
        createIndexAndMapping(completionMappingBuilder);

        client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
            .startObject().startObject(FIELD)
            .startArray("input").value("Foo Fighters").endArray()
            .field("output", "Boo Fighters")
            .field("payload", "test")
            .endObject().endObject()
        ).get();

        refresh();

        SearchResponse suggestResponse = client().prepareSearch(INDEX).suggest(
            new SuggestBuilder().addSuggestion("testSuggestions",
                new CompletionSuggestionBuilder(FIELD).text("foo").size(10))
        ).execute().actionGet();

        assertSuggestions(suggestResponse, "testSuggestions", "Boo Fighters");
        Suggest.Suggestion.Entry.Option option = suggestResponse.getSuggest()
            .getSuggestion("testSuggestions").getEntries().get(0).getOptions().get(0);
        assertThat(option, is(instanceOf(CompletionSuggestion.Entry.Option.class)));
        CompletionSuggestion.Entry.Option prefixOption = (CompletionSuggestion.Entry.Option) option;
        assertThat(prefixOption.getPayload(), is(notNullValue()));

        assertThat(prefixOption.getPayloadAsString(), equalTo("test"));
    }

    public void testThatExceptionIsThrownWhenPayloadsAreDisabledButInIndexRequest() throws Exception {
        completionMappingBuilder.payloads(false);
        createIndexAndMapping(completionMappingBuilder);

        try {
            client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
                .startObject().startObject(FIELD)
                .startArray("input").value("Foo Fighters").endArray()
                .field("output", "Boo Fighters")
                .startArray("payload").value("spam").value("eggs").endArray()
                .endObject().endObject()
            ).get();
            fail("expected MapperException");
        } catch (MapperException expected) {
        }
    }

    public void testDisabledPreserveSeparators() throws Exception {
        completionMappingBuilder.preserveSeparators(false);
        createIndexAndMapping(completionMappingBuilder);

        client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
            .startObject().startObject(FIELD)
            .startArray("input").value("Foo Fighters").endArray()
            .field("weight", 10)
            .endObject().endObject()
        ).get();

        client().prepareIndex(INDEX, TYPE, "2").setSource(jsonBuilder()
            .startObject().startObject(FIELD)
            .startArray("input").value("Foof").endArray()
            .field("weight", 20)
            .endObject().endObject()
        ).get();

        refresh();

        assertSuggestions("foof", "Foof", "Foo Fighters");
    }

    public void testEnabledPreserveSeparators() throws Exception {
        completionMappingBuilder.preserveSeparators(true);
        createIndexAndMapping(completionMappingBuilder);

        client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
            .startObject().startObject(FIELD)
            .startArray("input").value("Foo Fighters").endArray()
            .endObject().endObject()
        ).get();

        client().prepareIndex(INDEX, TYPE, "2").setSource(jsonBuilder()
            .startObject().startObject(FIELD)
            .startArray("input").value("Foof").endArray()
            .endObject().endObject()
        ).get();

        refresh();

        assertSuggestions("foof", "Foof");
    }

    public void testThatMultipleInputsAreSupported() throws Exception {
        createIndexAndMapping(completionMappingBuilder);

        client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
            .startObject().startObject(FIELD)
            .startArray("input").value("Foo Fighters").value("Fu Fighters").endArray()
            .field("output", "The incredible Foo Fighters")
            .endObject().endObject()
        ).get();

        refresh();

        assertSuggestions("foo", "The incredible Foo Fighters");
        assertSuggestions("fu", "The incredible Foo Fighters");
    }

    public void testThatShortSyntaxIsWorking() throws Exception {
        createIndexAndMapping(completionMappingBuilder);

        client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
            .startObject().startArray(FIELD)
            .value("The Prodigy Firestarter").value("Firestarter")
            .endArray().endObject()
        ).get();

        refresh();

        assertSuggestions("t", "The Prodigy Firestarter");
        assertSuggestions("f", "Firestarter");
    }

    public void testThatDisablingPositionIncrementsWorkForStopwords() throws Exception {
        // analyzer which removes stopwords... so may not be the simple one
        completionMappingBuilder.searchAnalyzer("classic").indexAnalyzer("classic").preservePositionIncrements(false);
        createIndexAndMapping(completionMappingBuilder);

        client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
            .startObject().startObject(FIELD)
            .startArray("input").value("The Beatles").endArray()
            .endObject().endObject()
        ).get();

        refresh();

        assertSuggestions("b", "The Beatles");
    }

    public void testThatSynonymsWork() throws Exception {
        Settings.Builder settingsBuilder = Settings.builder()
            .put("analysis.analyzer.suggest_analyzer_synonyms.type", "custom")
            .put("analysis.analyzer.suggest_analyzer_synonyms.tokenizer", "standard")
            .putArray("analysis.analyzer.suggest_analyzer_synonyms.filter", "standard", "lowercase", "my_synonyms")
            .put("analysis.filter.my_synonyms.type", "synonym")
            .putArray("analysis.filter.my_synonyms.synonyms", "foo,renamed");
        completionMappingBuilder.searchAnalyzer("suggest_analyzer_synonyms").indexAnalyzer("suggest_analyzer_synonyms");
        createIndexAndMappingAndSettings(settingsBuilder.build(), completionMappingBuilder);

        client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
            .startObject().startObject(FIELD)
            .startArray("input").value("Foo Fighters").endArray()
            .endObject().endObject()
        ).get();

        refresh();

        // get suggestions for renamed
        assertSuggestions("r", "Foo Fighters");
    }

    public void testThatUpgradeToMultiFieldTypeWorks() throws Exception {
        final XContentBuilder mapping = jsonBuilder()
            .startObject()
            .startObject(TYPE)
            .startObject("properties")
            .startObject(FIELD)
            .field("type", "string")
            .endObject()
            .endObject()
            .endObject()
            .endObject();
        assertAcked(prepareCreate(INDEX)
            .setSettings(Settings.builder().put(IndexMetaData.SETTING_VERSION_CREATED, PRE2X_VERSION.id))
            .addMapping(TYPE, mapping));
        client().prepareIndex(INDEX, TYPE, "1")
            .setRefreshPolicy(RefreshPolicy.IMMEDIATE)
            .setSource(jsonBuilder().startObject().field(FIELD, "Foo Fighters").endObject()).get();
        ensureGreen(INDEX);

        PutMappingResponse putMappingResponse = client().admin().indices().preparePutMapping(INDEX).setType(TYPE)
            .setSource(jsonBuilder().startObject()
            .startObject(TYPE).startObject("properties")
            .startObject(FIELD)
            .field("type", "string")
            .startObject("fields")
            .startObject("suggest").field("type", "completion").field("analyzer", "simple").endObject()
            .endObject()
            .endObject()
            .endObject().endObject()
            .endObject())
            .get();
        assertThat(putMappingResponse.isAcknowledged(), is(true));

        SearchResponse suggestResponse = client().prepareSearch(INDEX).suggest(new SuggestBuilder().addSuggestion("suggs",
            new CompletionSuggestionBuilder(FIELD + ".suggest").text("f").size(10))
        ).execute().actionGet();
        assertSuggestions(suggestResponse, "suggs");

        client().prepareIndex(INDEX, TYPE, "1").setRefreshPolicy(RefreshPolicy.IMMEDIATE)
            .setSource(jsonBuilder().startObject().field(FIELD, "Foo Fighters").endObject()).get();
        ensureGreen(INDEX);

        SearchResponse afterReindexingResponse = client().prepareSearch(INDEX).suggest(
            new SuggestBuilder().addSuggestion("suggs",
                SuggestBuilders.completionSuggestion(FIELD + ".suggest").text("f").size(10))
        ).execute().actionGet();
        assertSuggestions(afterReindexingResponse, "suggs", "Foo Fighters");
    }

    public void testThatUpgradeToMultiFieldsWorks() throws Exception {
        final XContentBuilder mapping = jsonBuilder()
            .startObject()
            .startObject(TYPE)
            .startObject("properties")
            .startObject(FIELD)
            .field("type", "string")
            .endObject()
            .endObject()
            .endObject()
            .endObject();
        assertAcked(prepareCreate(INDEX)
            .addMapping(TYPE, mapping)
            .setSettings(Settings.builder().put(IndexMetaData.SETTING_VERSION_CREATED, PRE2X_VERSION.id)));
        client().prepareIndex(INDEX, TYPE, "1")
            .setRefreshPolicy(RefreshPolicy.IMMEDIATE)
            .setSource(jsonBuilder().startObject().field(FIELD, "Foo Fighters").endObject()).get();
        ensureGreen(INDEX);

        PutMappingResponse putMappingResponse = client().admin().indices()
            .preparePutMapping(INDEX).setType(TYPE).setSource(jsonBuilder().startObject()
            .startObject(TYPE).startObject("properties")
            .startObject(FIELD)
            .field("type", "string")
            .startObject("fields")
            .startObject("suggest").field("type", "completion").field("analyzer", "simple").endObject()
            .endObject()
            .endObject()
            .endObject().endObject()
            .endObject())
            .get();
        assertThat(putMappingResponse.isAcknowledged(), is(true));

        SearchResponse suggestResponse = client().prepareSearch(INDEX).suggest(new SuggestBuilder().addSuggestion("suggs",
            SuggestBuilders.completionSuggestion(FIELD + ".suggest").text("f").size(10))
        ).execute().actionGet();
        assertSuggestions(suggestResponse, "suggs");

        client().prepareIndex(INDEX, TYPE, "1").setRefreshPolicy(RefreshPolicy.IMMEDIATE)
            .setSource(jsonBuilder().startObject().field(FIELD, "Foo Fighters").endObject()).get();
        ensureGreen(INDEX);

        SearchResponse afterReindexingResponse = client().prepareSearch(INDEX).suggest(
            new SuggestBuilder().addSuggestion("suggs",
                SuggestBuilders.completionSuggestion(FIELD + ".suggest").text("f").size(10))
        ).execute().actionGet();
        assertSuggestions(afterReindexingResponse, "suggs", "Foo Fighters");
    }

    public void testThatFuzzySuggesterWorks() throws Exception {
        createIndexAndMapping(completionMappingBuilder);

        client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
            .startObject().startObject(FIELD)
            .startArray("input").value("Nirvana").endArray()
            .endObject().endObject()
        ).get();

        refresh();

        SearchResponse suggestResponse = client().prepareSearch(INDEX).suggest(new SuggestBuilder().addSuggestion("foo",
            SuggestBuilders.completionSuggestion(FIELD).prefix("Nirv", Fuzziness.ONE).size(10))
        ).execute().actionGet();
        assertSuggestions(suggestResponse, false, "foo", "Nirvana");

        suggestResponse = client().prepareSearch(INDEX).suggest(new SuggestBuilder().addSuggestion("foo",
            SuggestBuilders.completionSuggestion(FIELD).prefix("Nirw", Fuzziness.ONE).size(10))
        ).execute().actionGet();
        assertSuggestions(suggestResponse, false, "foo", "Nirvana");
    }

    public void testThatFuzzySuggesterSupportsEditDistances() throws Exception {
        createIndexAndMapping(completionMappingBuilder);

        client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
            .startObject().startObject(FIELD)
            .startArray("input").value("Nirvana").endArray()
            .endObject().endObject()
        ).get();

        refresh();

        // edit distance 1
        SearchResponse suggestResponse = client().prepareSearch(INDEX).suggest(new SuggestBuilder().addSuggestion("foo",
            SuggestBuilders.completionSuggestion(FIELD).prefix("Norw", Fuzziness.ONE).size(10))
        ).execute().actionGet();
        assertSuggestions(suggestResponse, false, "foo");

        // edit distance 2
        suggestResponse = client().prepareSearch(INDEX).suggest(new SuggestBuilder().addSuggestion("foo",
            SuggestBuilders.completionSuggestion(FIELD).prefix("Norw", Fuzziness.TWO).size(10))
        ).execute().actionGet();
        assertSuggestions(suggestResponse, false, "foo", "Nirvana");
    }

    public void testThatFuzzySuggesterSupportsTranspositions() throws Exception {
        createIndexAndMapping(completionMappingBuilder);

        client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
            .startObject().startObject(FIELD)
            .startArray("input").value("Nirvana").endArray()
            .endObject().endObject()
        ).get();

        refresh();

        SearchResponse suggestResponse = client().prepareSearch(INDEX).suggest(new SuggestBuilder().addSuggestion("foo",
            SuggestBuilders.completionSuggestion(FIELD).prefix("Nriv",
                FuzzyOptions.builder().setTranspositions(false).build()).size(10))
        ).execute().actionGet();
        assertSuggestions(suggestResponse, false, "foo");

        suggestResponse = client().prepareSearch(INDEX).suggest(new SuggestBuilder().addSuggestion("foo",
            SuggestBuilders.completionSuggestion(FIELD).prefix("Nriv",
                FuzzyOptions.builder().setTranspositions(true).build()).size(10))
        ).execute().actionGet();
        assertSuggestions(suggestResponse, false, "foo", "Nirvana");
    }

    public void testThatFuzzySuggesterSupportsMinPrefixLength() throws Exception {
        createIndexAndMapping(completionMappingBuilder);

        client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
            .startObject().startObject(FIELD)
            .startArray("input").value("Nirvana").endArray()
            .endObject().endObject()
        ).get();

        refresh();

        SearchResponse suggestResponse = client().prepareSearch(INDEX).suggest(
            new SuggestBuilder().addSuggestion("foo",
                SuggestBuilders.completionSuggestion(FIELD).prefix("Nriva",
                    FuzzyOptions.builder().setFuzzyMinLength(6).build()).size(10))
        ).execute().actionGet();
        assertSuggestions(suggestResponse, false, "foo");

        suggestResponse = client().prepareSearch(INDEX).suggest(
            new SuggestBuilder().addSuggestion("foo",
                SuggestBuilders.completionSuggestion(FIELD).prefix("Nrivan",
                    FuzzyOptions.builder().setFuzzyMinLength(6).build()).size(10))
        ).execute().actionGet();
        assertSuggestions(suggestResponse, false, "foo", "Nirvana");
    }

    public void testThatFuzzySuggesterSupportsNonPrefixLength() throws Exception {
        createIndexAndMapping(completionMappingBuilder);

        client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
            .startObject().startObject(FIELD)
            .startArray("input").value("Nirvana").endArray()
            .endObject().endObject()
        ).get();

        refresh();

        SearchResponse suggestResponse = client().prepareSearch(INDEX).suggest(
            new SuggestBuilder().addSuggestion("foo",
                SuggestBuilders.completionSuggestion(FIELD).prefix("Nirw",
                    FuzzyOptions.builder().setFuzzyPrefixLength(4).build()).size(10))
        ).execute().actionGet();
        assertSuggestions(suggestResponse, false, "foo");

        suggestResponse = client().prepareSearch(INDEX).suggest(
            new SuggestBuilder().addSuggestion("foo",
                SuggestBuilders.completionSuggestion(FIELD).prefix("Nirvo",
                    FuzzyOptions.builder().setFuzzyPrefixLength(4).build()).size(10))
        ).execute().actionGet();
        assertSuggestions(suggestResponse, false, "foo", "Nirvana");
    }

    public void testThatFuzzySuggesterIsUnicodeAware() throws Exception {
        createIndexAndMapping(completionMappingBuilder);

        client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
            .startObject().startObject(FIELD)
            .startArray("input").value("ööööö").endArray()
            .endObject().endObject()
        ).get();

        refresh();

        // suggestion with a character, which needs unicode awareness
        CompletionSuggestionBuilder completionSuggestionBuilder =
            SuggestBuilders.completionSuggestion(FIELD).prefix("öööи",
                FuzzyOptions.builder().setUnicodeAware(true).build()).size(10);

        SearchResponse suggestResponse = client().prepareSearch(INDEX).suggest(
            new SuggestBuilder().addSuggestion("foo", completionSuggestionBuilder)).execute().actionGet();
        assertSuggestions(suggestResponse, false, "foo", "ööööö");

        // removing unicode awareness leads to no result
        completionSuggestionBuilder.prefix("öööи", FuzzyOptions.builder().setUnicodeAware(false).build());
        suggestResponse = client().prepareSearch(INDEX).suggest(
            new SuggestBuilder().addSuggestion("foo" ,completionSuggestionBuilder)).execute().actionGet();
        assertSuggestions(suggestResponse, false, "foo");

        // increasing edit distance instead of unicode awareness works again, as this is only a single character
        completionSuggestionBuilder.prefix("öööи", FuzzyOptions.builder().setFuzziness(2).build());
        suggestResponse = client().prepareSearch(INDEX).suggest(
            new SuggestBuilder().addSuggestion("foo", completionSuggestionBuilder)).execute().actionGet();
        assertSuggestions(suggestResponse, false, "foo", "ööööö");
    }

    public void testThatStatsAreWorking() throws Exception {
        String otherField = "testOtherField";

        assertAcked(prepareCreate(INDEX)
            .setSettings(Settings.builder().put(IndexMetaData.SETTING_VERSION_CREATED, PRE2X_VERSION.id)));

        PutMappingResponse putMappingResponse = client().admin().indices()
            .preparePutMapping(INDEX).setType(TYPE).setSource(jsonBuilder().startObject()
            .startObject(TYPE).startObject("properties")
            .startObject(FIELD.toString())
            .field("type", "completion").field("analyzer", "simple")
            .endObject()
            .startObject(otherField)
            .field("type", "completion").field("analyzer", "simple")
            .endObject()
            .endObject().endObject().endObject())
            .get();
        assertThat(putMappingResponse.isAcknowledged(), is(true));

        // Index two entities
        client().prepareIndex(INDEX, TYPE, "1").setRefreshPolicy(RefreshPolicy.IMMEDIATE)
            .setSource(jsonBuilder().startObject().field(FIELD, "Foo Fighters").field(otherField, "WHATEVER").endObject())
            .get();
        client().prepareIndex(INDEX, TYPE, "2").setRefreshPolicy(RefreshPolicy.IMMEDIATE)
            .setSource(jsonBuilder().startObject().field(FIELD, "Bar Fighters").field(otherField, "WHATEVER2").endObject())
            .get();

        // Get all stats
        IndicesStatsResponse indicesStatsResponse = client().admin().indices().prepareStats(INDEX).setIndices(INDEX)
            .setCompletion(true).get();
        CompletionStats completionStats = indicesStatsResponse.getIndex(INDEX).getPrimaries().completion;
        assertThat(completionStats, notNullValue());
        long totalSizeInBytes = completionStats.getSizeInBytes();
        assertThat(totalSizeInBytes, is(greaterThan(0L)));

        IndicesStatsResponse singleFieldStats = client().admin().indices().prepareStats(INDEX)
            .setIndices(INDEX).setCompletion(true).setCompletionFields(FIELD).get();
        long singleFieldSizeInBytes = singleFieldStats.getIndex(INDEX).getPrimaries().completion.getFields().get(FIELD);
        IndicesStatsResponse otherFieldStats = client().admin().indices().prepareStats(INDEX)
            .setIndices(INDEX).setCompletion(true).setCompletionFields(otherField).get();
        long otherFieldSizeInBytes = otherFieldStats.getIndex(INDEX).getPrimaries().completion.getFields().get(otherField);
        assertThat(singleFieldSizeInBytes + otherFieldSizeInBytes, is(totalSizeInBytes));

        // regexes
        IndicesStatsResponse regexFieldStats = client().admin().indices().prepareStats(INDEX)
            .setIndices(INDEX).setCompletion(true).setCompletionFields("*").get();
        ObjectLongHashMap<String> fields = regexFieldStats.getIndex(INDEX).getPrimaries().completion.getFields();
        long regexSizeInBytes = fields.get(FIELD) + fields.get(otherField);
        assertThat(regexSizeInBytes, is(totalSizeInBytes));
    }

    public void testThatSortingOnCompletionFieldReturnsUsefulException() throws Exception {
        createIndexAndMapping(completionMappingBuilder);

        client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
            .startObject().startObject(FIELD)
            .startArray("input").value("Nirvana").endArray()
            .endObject().endObject()
        ).get();

        refresh();
        try {
            client().prepareSearch(INDEX).setTypes(TYPE).addSort(new FieldSortBuilder(FIELD)).execute().actionGet();
            fail("Expected an exception due to trying to sort on completion field, but did not happen");
        } catch (SearchPhaseExecutionException e) {
            assertThat(e.status().getStatus(), is(400));
            assertThat(e.toString(), containsString("Fielddata is not supported on field [" + FIELD + "] of type [completion]]"));
        }
    }

    public void testThatSuggestStopFilterWorks() throws Exception {
        Settings.Builder settingsBuilder = Settings.builder()
            .put("index.analysis.analyzer.stoptest.tokenizer", "standard")
            .putArray("index.analysis.analyzer.stoptest.filter", "standard", "suggest_stop_filter")
            .put("index.analysis.filter.suggest_stop_filter.type", "stop")
            .put("index.analysis.filter.suggest_stop_filter.remove_trailing", false);

        CompletionMappingBuilder completionMappingBuilder = new CompletionMappingBuilder();
        completionMappingBuilder.preserveSeparators(true).preservePositionIncrements(true);
        completionMappingBuilder.searchAnalyzer("stoptest");
        completionMappingBuilder.indexAnalyzer("simple");
        createIndexAndMappingAndSettings(settingsBuilder.build(), completionMappingBuilder);

        client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
            .startObject().startObject(FIELD)
            .startArray("input").value("Feed trolls").endArray()
            .field("weight", 5).endObject().endObject()
        ).get();

        // Higher weight so it's ranked first:
        client().prepareIndex(INDEX, TYPE, "2").setSource(jsonBuilder()
            .startObject().startObject(FIELD)
            .startArray("input").value("Feed the trolls").endArray()
            .field("weight", 10).endObject().endObject()
        ).get();

        refresh();

        assertSuggestions("f", "Feed the trolls", "Feed trolls");
        assertSuggestions("fe", "Feed the trolls", "Feed trolls");
        assertSuggestions("fee", "Feed the trolls", "Feed trolls");
        assertSuggestions("feed", "Feed the trolls", "Feed trolls");
        assertSuggestions("feed t", "Feed the trolls", "Feed trolls");
        assertSuggestions("feed the", "Feed the trolls");
        // stop word complete, gets ignored on query time, makes it "feed" only
        assertSuggestions("feed the ", "Feed the trolls", "Feed trolls");
        // stopword gets removed, but position increment kicks in, which doesnt work for the prefix suggester
        assertSuggestions("feed the t");
    }

    public void testThatIndexingInvalidFieldsInCompletionFieldResultsInException() throws Exception {
        CompletionMappingBuilder completionMappingBuilder = new CompletionMappingBuilder();
        createIndexAndMapping(completionMappingBuilder);

        try {
            client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
                .startObject().startObject(FIELD)
                .startArray("FRIGGININVALID").value("Nirvana").endArray()
                .endObject().endObject()).get();
            fail("expected MapperParsingException");
        } catch (MapperParsingException expected) {}
    }


    public void assertSuggestions(String suggestion, String... suggestions) {
        String suggestionName = RandomStrings.randomAsciiOfLength(random(), 10);
        SearchResponse suggestResponse = client().prepareSearch(INDEX).suggest(
            new SuggestBuilder().addSuggestion(suggestionName,
                SuggestBuilders.completionSuggestion(FIELD).text(suggestion).size(10))
        ).execute().actionGet();

        assertSuggestions(suggestResponse, suggestionName, suggestions);
    }

    public void assertSuggestionsNotInOrder(String suggestString, String... suggestions) {
        String suggestionName = RandomStrings.randomAsciiOfLength(random(), 10);
        SearchResponse suggestResponse = client().prepareSearch(INDEX).suggest(
            new SuggestBuilder().addSuggestion(suggestionName,
                SuggestBuilders.completionSuggestion(FIELD).text(suggestString).size(10))
        ).execute().actionGet();

        assertSuggestions(suggestResponse, false, suggestionName, suggestions);
    }

    private void assertSuggestions(SearchResponse suggestResponse, String name, String... suggestions) {
        assertSuggestions(suggestResponse, true, name, suggestions);
    }

    private void assertSuggestions(SearchResponse suggestResponse, boolean suggestionOrderStrict, String name,
                                   String... suggestions) {
        assertAllSuccessful(suggestResponse);

        List<String> suggestionNames = new ArrayList<>();
        for (Suggest.Suggestion<? extends Suggest.Suggestion.Entry<? extends Suggest.Suggestion.Entry.Option>> suggestion :
            suggestResponse.getSuggest()) {
            suggestionNames.add(suggestion.getName());
        }
        String expectFieldInResponseMsg =
            String.format(Locale.ROOT, "Expected suggestion named %s in response, got %s", name, suggestionNames);
        assertThat(expectFieldInResponseMsg, suggestResponse.getSuggest().getSuggestion(name), is(notNullValue()));

        Suggest.Suggestion<Suggest.Suggestion.Entry<Suggest.Suggestion.Entry.Option>> suggestion =
            suggestResponse.getSuggest().getSuggestion(name);

        List<String> suggestionList = getNames(suggestion.getEntries().get(0));
        List<Suggest.Suggestion.Entry.Option> options = suggestion.getEntries().get(0).getOptions();

        String assertMsg = String.format(Locale.ROOT, "Expected options %s length to be %s, but was %s",
            suggestionList, suggestions.length, options.size());
        assertThat(assertMsg, options.size(), is(suggestions.length));
        if (suggestionOrderStrict) {
            for (int i = 0; i < suggestions.length; i++) {
                String errMsg = String.format(Locale.ROOT, "Expected elem %s in list %s to be [%s] score: %s",
                    i, suggestionList, suggestions[i], options.get(i).getScore());
                assertThat(errMsg, options.get(i).getText().toString(), is(suggestions[i]));
            }
        } else {
            for (String expectedSuggestion : suggestions) {
                String errMsg = String.format(Locale.ROOT, "Expected elem %s to be in list %s",
                    expectedSuggestion, suggestionList);
                assertThat(errMsg, suggestionList, hasItem(expectedSuggestion));
            }
        }
    }

    private List<String> getNames(Suggest.Suggestion.Entry<Suggest.Suggestion.Entry.Option> suggestEntry) {
        List<String> names = new ArrayList<>();
        for (Suggest.Suggestion.Entry.Option entry : suggestEntry.getOptions()) {
            names.add(entry.getText().string());
        }
        return names;
    }

    private void createIndexAndMappingAndSettings(Settings settings, CompletionMappingBuilder completionMappingBuilder)
        throws IOException {
        assertAcked(prepareCreate(INDEX)
            .setSettings(Settings.builder().put(indexSettings()).put(settings)
                .put(IndexMetaData.SETTING_VERSION_CREATED, PRE2X_VERSION.id).build())
            .addMapping(TYPE, jsonBuilder().startObject()
                .startObject(TYPE).startObject("properties")
                .startObject(FIELD)
                .field("type", "completion")
                .field("analyzer", completionMappingBuilder.indexAnalyzer)
                .field("search_analyzer", completionMappingBuilder.searchAnalyzer)
                .field("payloads", completionMappingBuilder.payloads)
                .field("preserve_separators", completionMappingBuilder.preserveSeparators)
                .field("preserve_position_increments", completionMappingBuilder.preservePositionIncrements)
                .endObject()
                .endObject().endObject()
                .endObject())
            .get());
    }

    private void createIndexAndMapping(CompletionMappingBuilder completionMappingBuilder) throws IOException {
        createIndexAndMappingAndSettings(Settings.EMPTY, completionMappingBuilder);
    }

    private void createData(boolean optimize) throws IOException, InterruptedException, ExecutionException {
        String[][] input = {{"Foo Fighters"}, {"Generator", "Foo Fighters Generator"},
            {"Learn to Fly", "Foo Fighters Learn to Fly"}, {"The Prodigy"}, {"Firestarter", "The Prodigy Firestarter"},
            {"Turbonegro"}, {"Get it on", "Turbonegro Get it on"}};
        String[] surface = {"Foo Fighters", "Generator - Foo Fighters", "Learn to Fly - Foo Fighters", "The Prodigy",
            "Firestarter - The Prodigy", "Turbonegro", "Get it on - Turbonegro"};
        int[] weight = {10, 9, 8, 12, 11, 6, 7};
        IndexRequestBuilder[] builders = new IndexRequestBuilder[input.length];
        for (int i = 0; i < builders.length; i++) {
            builders[i] = client().prepareIndex(INDEX, TYPE, "" + i)
                .setSource(jsonBuilder()
                    .startObject().startObject(FIELD)
                    .field("input", input[i])
                    .field("output", surface[i])
                    .startObject("payload").field("id", i).endObject()
                    .field("weight", 1) // WE FORCEFULLY INDEX A BOGUS WEIGHT
                    .endObject()
                    .endObject()
                );
        }
        indexRandom(false, builders);

        for (int i = 0; i < builders.length; i++) { // add them again to make sure we deduplicate on the surface form
            builders[i] = client().prepareIndex(INDEX, TYPE, "n" + i)
                .setSource(jsonBuilder()
                    .startObject().startObject(FIELD)
                    .field("input", input[i])
                    .field("output", surface[i])
                    .startObject("payload").field("id", i).endObject()
                    .field("weight", weight[i])
                    .endObject()
                    .endObject()
                );
        }
        indexRandom(false, builders);

        client().admin().indices().prepareRefresh(INDEX).execute().actionGet();
        if (optimize) {
            // make sure merging works just fine
            client().admin().indices().prepareFlush(INDEX).execute().actionGet();
            client().admin().indices().prepareForceMerge(INDEX).setMaxNumSegments(randomIntBetween(1, 5)).get();
        }
    }

    // see #3555
    public void testPrunedSegments() throws IOException {
        createIndexAndMappingAndSettings(
            Settings.builder().put(SETTING_NUMBER_OF_SHARDS, 1).put(SETTING_NUMBER_OF_REPLICAS, 0).build(),
            completionMappingBuilder);

        client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
            .startObject().startObject(FIELD)
            .startArray("input").value("The Beatles").endArray()
            .endObject().endObject()
        ).get();
        client().prepareIndex(INDEX, TYPE, "2").setSource(jsonBuilder()
            .startObject()
            .field("somefield", "somevalue")
            .endObject()
        ).get(); // we have 2 docs in a segment...
        ForceMergeResponse actionGet = client().admin().indices().prepareForceMerge().setFlush(true)
            .setMaxNumSegments(1).execute().actionGet();
        assertAllSuccessful(actionGet);
        refresh();
        // update the first one and then merge.. the target segment will have no value in FIELD
        client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
            .startObject()
            .field("somefield", "somevalue")
            .endObject()
        ).get();
        actionGet = client().admin().indices().prepareForceMerge().setFlush(true).setMaxNumSegments(1)
            .execute().actionGet();
        assertAllSuccessful(actionGet);
        refresh();

        assertSuggestions("b");
        assertThat(2L, equalTo(client().prepareSearch(INDEX).get().getHits().totalHits()));
        for (IndexShardSegments seg : client().admin().indices().prepareSegments().get().getIndices().get(INDEX)) {
            ShardSegments[] shards = seg.getShards();
            for (ShardSegments shardSegments : shards) {
                assertThat(shardSegments.getSegments().size(), equalTo(1));
            }
        }
    }

    public void testMaxFieldLength() throws IOException {
        client().admin().indices().prepareCreate(INDEX)
            .setSettings(Settings.builder().put(IndexMetaData.SETTING_VERSION_CREATED, PRE2X_VERSION.id)).get();
        ensureGreen();
        int iters = scaledRandomIntBetween(10, 20);
        for (int i = 0; i < iters; i++) {
            int maxInputLen = between(3, 50);
            String str = replaceReservedChars(
                randomRealisticUnicodeOfCodepointLengthBetween(maxInputLen + 1,
                    maxInputLen + scaledRandomIntBetween(2, 50)), (char) 0x01);
            assertAcked(client().admin().indices().preparePutMapping(INDEX)
                .setType(TYPE).setSource(jsonBuilder().startObject()
                .startObject(TYPE).startObject("properties")
                .startObject(FIELD)
                .field("type", "completion")
                .field("max_input_length", maxInputLen)
                // upgrade mapping each time
                .field("analyzer", "keyword")
                .endObject()
                .endObject().endObject()
                .endObject()));
            client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
                .startObject().startObject(FIELD)
                .startArray("input").value(str).endArray()
                .field("output", "foobar")
                .endObject().endObject()
            ).setRefreshPolicy(RefreshPolicy.IMMEDIATE).get();
            // need to flush and refresh, because we keep changing the same document
            // we have to make sure that segments without any live documents are deleted
            flushAndRefresh();
            int prefixLen = CompletionFieldMapper2x.correctSubStringLen(str, between(1, maxInputLen - 1));
            assertSuggestions(str.substring(0, prefixLen), "foobar");
            if (maxInputLen + 1 < str.length()) {
                int offset = Character.isHighSurrogate(str.charAt(maxInputLen - 1)) ? 2 : 1;
                int correctSubStringLen = CompletionFieldMapper2x.correctSubStringLen(str, maxInputLen + offset);
                String shortenedSuggestion = str.substring(0, correctSubStringLen);
                assertSuggestions(shortenedSuggestion);
            }
        }
    }

    // see #3596
    public void testVeryLongInput() throws IOException {
        assertAcked(client().admin().indices().prepareCreate(INDEX)
            .setSettings(Settings.builder().put(IndexMetaData.SETTING_VERSION_CREATED, PRE2X_VERSION.id))
            .addMapping(TYPE, jsonBuilder().startObject()
                .startObject(TYPE).startObject("properties")
                .startObject(FIELD)
                .field("type", "completion")
                .endObject()
                .endObject().endObject()
                .endObject()).get());
        // can cause stack overflow without the default max_input_length
        String longString = replaceReservedChars(randomRealisticUnicodeOfLength(randomIntBetween(5000, 10000)), (char) 0x01);
        client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
            .startObject().startObject(FIELD)
            .startArray("input").value(longString).endArray()
            .field("output", "foobar")
            .endObject().endObject()
        ).setRefreshPolicy(RefreshPolicy.IMMEDIATE).get();

    }

    // see #3648
    public void testReservedChars() throws IOException {
        assertAcked(client().admin().indices().prepareCreate(INDEX).addMapping(TYPE, jsonBuilder().startObject()
            .startObject(TYPE).startObject("properties")
            .startObject(FIELD)
            .field("type", "completion")
            .endObject()
            .endObject().endObject()
            .endObject()).get());
        // can cause stack overflow without the default max_input_length
        String string = "foo" + (char) 0x00 + "bar";
        try {
            client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
                .startObject().startObject(FIELD)
                .startArray("input").value(string).endArray()
                .field("output", "foobar")
                .endObject().endObject()
            ).setRefreshPolicy(RefreshPolicy.IMMEDIATE).get();
            fail("expected MapperParsingException");
        } catch (MapperParsingException expected) {}
    }

    // see #5930
    public void testIssue5930() throws IOException {
        assertAcked(client().admin().indices().prepareCreate(INDEX).addMapping(TYPE, jsonBuilder().startObject()
            .startObject(TYPE).startObject("properties")
            .startObject(FIELD)
            .field("type", "completion")
            .endObject()
            .endObject().endObject()
            .endObject()).get());
        String string = "foo bar";
        client().prepareIndex(INDEX, TYPE, "1").setSource(jsonBuilder()
            .startObject()
            .field(FIELD, string)
            .endObject()
        ).setRefreshPolicy(RefreshPolicy.IMMEDIATE).get();

        try {
            client().prepareSearch(INDEX).addAggregation(AggregationBuilders.terms("suggest_agg").field(FIELD)
                .collectMode(randomFrom(SubAggCollectionMode.values()))).execute().actionGet();
            // Exception must be thrown
            assertFalse(true);
        } catch (SearchPhaseExecutionException e) {
            assertTrue(e.toString().contains("Fielddata is not supported on field [" + FIELD + "] of type [completion]"));
        }
    }

    // see issue #6399
    public void testIndexingUnrelatedNullValue() throws Exception {
        String mapping = jsonBuilder()
            .startObject()
            .startObject(TYPE)
            .startObject("properties")
            .startObject(FIELD)
            .field("type", "completion")
            .endObject()
            .endObject()
            .endObject()
            .endObject()
            .string();

        assertAcked(client().admin().indices().prepareCreate(INDEX)
            .setSettings(Settings.builder().put(IndexMetaData.SETTING_VERSION_CREATED, PRE2X_VERSION.id))
            .addMapping(TYPE, mapping).get());
        ensureGreen();

        client().prepareIndex(INDEX, TYPE, "1").setSource(FIELD, "strings make me happy", FIELD + "_1", "nulls make me sad")
        .setRefreshPolicy(RefreshPolicy.IMMEDIATE).get();

        try {
            client().prepareIndex(INDEX, TYPE, "2").setSource(FIELD, null, FIELD + "_1", "nulls make me sad")
                .setRefreshPolicy(RefreshPolicy.IMMEDIATE).get();
            fail("Expected MapperParsingException for null value");
        } catch (MapperParsingException e) {
            // make sure that the exception has the name of the field causing the error
            assertTrue(e.getDetailedMessage().contains(FIELD));
        }

    }

    private static String replaceReservedChars(String input, char replacement) {
        char[] charArray = input.toCharArray();
        for (int i = 0; i < charArray.length; i++) {
            if (CompletionFieldMapper2x.isReservedChar(charArray[i])) {
                charArray[i] = replacement;
            }
        }
        return new String(charArray);
    }

    private static class CompletionMappingBuilder {
        private String searchAnalyzer = "simple";
        private String indexAnalyzer = "simple";
        private Boolean payloads = getRandom().nextBoolean();
        private Boolean preserveSeparators = getRandom().nextBoolean();
        private Boolean preservePositionIncrements = getRandom().nextBoolean();

        public CompletionMappingBuilder searchAnalyzer(String searchAnalyzer) {
            this.searchAnalyzer = searchAnalyzer;
            return this;
        }
        public CompletionMappingBuilder indexAnalyzer(String indexAnalyzer) {
            this.indexAnalyzer = indexAnalyzer;
            return this;
        }
        public CompletionMappingBuilder payloads(Boolean payloads) {
            this.payloads = payloads;
            return this;
        }
        public CompletionMappingBuilder preserveSeparators(Boolean preserveSeparators) {
            this.preserveSeparators = preserveSeparators;
            return this;
        }
        public CompletionMappingBuilder preservePositionIncrements(Boolean preservePositionIncrements) {
            this.preservePositionIncrements = preservePositionIncrements;
            return this;
        }
    }
}
