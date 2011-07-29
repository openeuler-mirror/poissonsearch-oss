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

package org.elasticsearch.index.analysis;

import org.apache.lucene.analysis.StopAnalyzer;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.util.Version;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.assistedinject.Assisted;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.settings.IndexSettings;

import java.util.Set;

/**
 * @author kimchy (Shay Banon)
 */
public class StopTokenFilterFactory extends AbstractTokenFilterFactory {

    private final Set<?> stopWords;

    private final boolean ignoreCase;

    private final boolean enablePositionIncrements;

    @Inject public StopTokenFilterFactory(Index index, @IndexSettings Settings indexSettings, Environment env, @Assisted String name, @Assisted Settings settings) {
        super(index, indexSettings, name, settings);
        this.stopWords = Analysis.parseStopWords(env, settings, StopAnalyzer.ENGLISH_STOP_WORDS_SET);
        this.ignoreCase = settings.getAsBoolean("ignore_case", false);
        this.enablePositionIncrements = settings.getAsBoolean("enable_position_increments", version.onOrAfter(Version.LUCENE_29));
    }

    @Override public TokenStream create(TokenStream tokenStream) {
        StopFilter filter = new StopFilter(version, tokenStream, stopWords, ignoreCase);
        filter.setEnablePositionIncrements(enablePositionIncrements);
        return filter;
    }

    public Set<?> stopWords() {
        return stopWords;
    }

    public boolean ignoreCase() {
        return ignoreCase;
    }

    public boolean enablePositionIncrements() {
        return this.enablePositionIncrements;
    }
}
