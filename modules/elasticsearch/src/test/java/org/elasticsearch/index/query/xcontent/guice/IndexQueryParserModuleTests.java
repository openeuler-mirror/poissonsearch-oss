/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

package org.elasticsearch.index.query.xcontent.guice;

import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexNameModule;
import org.elasticsearch.index.analysis.AnalysisModule;
import org.elasticsearch.index.cache.IndexCacheModule;
import org.elasticsearch.index.engine.IndexEngineModule;
import org.elasticsearch.index.query.IndexQueryParserModule;
import org.elasticsearch.index.query.IndexQueryParserService;
import org.elasticsearch.index.query.xcontent.XContentIndexQueryParser;
import org.elasticsearch.index.query.xcontent.XContentQueryParserRegistry;
import org.elasticsearch.index.settings.IndexSettingsModule;
import org.elasticsearch.index.similarity.SimilarityModule;
import org.elasticsearch.script.ScriptModule;
import org.elasticsearch.util.inject.Guice;
import org.elasticsearch.util.inject.Injector;
import org.elasticsearch.util.settings.Settings;
import org.elasticsearch.util.settings.SettingsModule;
import org.testng.annotations.Test;

import static org.elasticsearch.util.settings.ImmutableSettings.*;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;

/**
 * @author kimchy (shay.banon)
 */
public class IndexQueryParserModuleTests {

    @Test public void testCustomInjection() {
        Settings settings = settingsBuilder()
                .put("index.queryparser.query.my.type", MyJsonQueryParser.class)
                .put("index.queryparser.query.my.param1", "value1")
                .put("index.queryparser.filter.my.type", MyJsonFilterParser.class)
                .put("index.queryparser.filter.my.param2", "value2")
                .put("index.cache.filter.type", "none")
                .build();

        Index index = new Index("test");
        Injector injector = Guice.createInjector(
                new SettingsModule(settings),
                new ScriptModule(),
                new IndexSettingsModule(settings),
                new IndexCacheModule(settings),
                new AnalysisModule(settings),
                new IndexEngineModule(settings),
                new SimilarityModule(settings),
                new IndexQueryParserModule(settings),
                new IndexNameModule(index)
        );
        IndexQueryParserService indexQueryParserService = injector.getInstance(IndexQueryParserService.class);


        XContentQueryParserRegistry parserRegistry = ((XContentIndexQueryParser) indexQueryParserService.defaultIndexQueryParser()).queryParserRegistry();

        MyJsonQueryParser myJsonQueryParser = (MyJsonQueryParser) parserRegistry.queryParser("my");

        assertThat(myJsonQueryParser.names()[0], equalTo("my"));
        assertThat(myJsonQueryParser.settings().get("param1"), equalTo("value1"));

        MyJsonFilterParser myJsonFilterParser = (MyJsonFilterParser) parserRegistry.filterParser("my");
        assertThat(myJsonFilterParser.names()[0], equalTo("my"));
        assertThat(myJsonFilterParser.settings().get("param2"), equalTo("value2"));
    }
}
