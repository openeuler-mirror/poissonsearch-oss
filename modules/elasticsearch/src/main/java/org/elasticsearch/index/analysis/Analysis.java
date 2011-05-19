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

import org.apache.lucene.analysis.ar.ArabicAnalyzer;
import org.apache.lucene.analysis.bg.BulgarianAnalyzer;
import org.apache.lucene.analysis.br.BrazilianAnalyzer;
import org.apache.lucene.analysis.ca.CatalanAnalyzer;
import org.apache.lucene.analysis.da.DanishAnalyzer;
import org.apache.lucene.analysis.de.GermanAnalyzer;
import org.apache.lucene.analysis.el.GreekAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.es.SpanishAnalyzer;
import org.apache.lucene.analysis.eu.BasqueAnalyzer;
import org.apache.lucene.analysis.fa.PersianAnalyzer;
import org.apache.lucene.analysis.fi.FinnishAnalyzer;
import org.apache.lucene.analysis.fr.FrenchAnalyzer;
import org.apache.lucene.analysis.gl.GalicianAnalyzer;
import org.apache.lucene.analysis.hi.HindiAnalyzer;
import org.apache.lucene.analysis.hu.HungarianAnalyzer;
import org.apache.lucene.analysis.hy.ArmenianAnalyzer;
import org.apache.lucene.analysis.id.IndonesianAnalyzer;
import org.apache.lucene.analysis.it.ItalianAnalyzer;
import org.apache.lucene.analysis.nl.DutchAnalyzer;
import org.apache.lucene.analysis.no.NorwegianAnalyzer;
import org.apache.lucene.analysis.pt.PortugueseAnalyzer;
import org.apache.lucene.analysis.ro.RomanianAnalyzer;
import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.apache.lucene.analysis.sv.SwedishAnalyzer;
import org.apache.lucene.analysis.tr.TurkishAnalyzer;
import org.elasticsearch.ElasticSearchIllegalArgumentException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.base.Charsets;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.collect.ImmutableSet;
import org.elasticsearch.common.collect.Iterators;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.util.*;

/**
 * @author kimchy (shay.banon)
 */
public class Analysis {

    public static boolean isNoStopwords(Settings settings) {
        String value = settings.get("stopwords");
        return value != null && "_none_".equals(value);
    }

    public static Set<?> parseStemExclusion(Settings settings, Set<?> defaultStemExclusion) {
        String value = settings.get("stem_exclusion");
        if (value != null) {
            if ("_none_".equals(value)) {
                return ImmutableSet.of();
            } else {
                return ImmutableSet.copyOf(Strings.commaDelimitedListToSet(value));
            }
        }
        String[] stopWords = settings.getAsArray("stem_exclusion", null);
        if (stopWords != null) {
            return ImmutableSet.copyOf(Iterators.forArray(stopWords));
        } else {
            return defaultStemExclusion;
        }
    }

    public static final ImmutableMap<String, Set<?>> namedStopWords = MapBuilder.<String, Set<?>>newMapBuilder()
            .put("_arabic_", ArabicAnalyzer.getDefaultStopSet())
            .put("_armenian_", ArmenianAnalyzer.getDefaultStopSet())
            .put("_basque_", BasqueAnalyzer.getDefaultStopSet())
            .put("_brazilian_", BrazilianAnalyzer.getDefaultStopSet())
            .put("_bulgarian_", BulgarianAnalyzer.getDefaultStopSet())
            .put("_catalan_", CatalanAnalyzer.getDefaultStopSet())
            .put("_danish_", DanishAnalyzer.getDefaultStopSet())
            .put("_dutch_", DutchAnalyzer.getDefaultStopSet())
            .put("_english_", EnglishAnalyzer.getDefaultStopSet())
            .put("_finnish_", FinnishAnalyzer.getDefaultStopSet())
            .put("_french_", FrenchAnalyzer.getDefaultStopSet())
            .put("_galician_", GalicianAnalyzer.getDefaultStopSet())
            .put("_german_", GermanAnalyzer.getDefaultStopSet())
            .put("_greek_", GreekAnalyzer.getDefaultStopSet())
            .put("_hindi_", HindiAnalyzer.getDefaultStopSet())
            .put("_hungarian_", HungarianAnalyzer.getDefaultStopSet())
            .put("_indonesian_", IndonesianAnalyzer.getDefaultStopSet())
            .put("_italian_", ItalianAnalyzer.getDefaultStopSet())
            .put("_norwegian_", NorwegianAnalyzer.getDefaultStopSet())
            .put("_persian_", PersianAnalyzer.getDefaultStopSet())
            .put("_portuguese_", PortugueseAnalyzer.getDefaultStopSet())
            .put("_romanian_", RomanianAnalyzer.getDefaultStopSet())
            .put("_russian_", RussianAnalyzer.getDefaultStopSet())
            .put("_spanish_", SpanishAnalyzer.getDefaultStopSet())
            .put("_swedish_", SwedishAnalyzer.getDefaultStopSet())
            .put("_turkish_", TurkishAnalyzer.getDefaultStopSet())
            .immutableMap();

    public static Set<?> parseArticles(Environment env, Settings settings) {
        String value = settings.get("articles");
        if (value != null) {
            if ("_none_".equals(value)) {
                return ImmutableSet.of();
            } else {
                return ImmutableSet.copyOf(Strings.commaDelimitedListToSet(value));
            }
        }
        String[] articles = settings.getAsArray("articles", null);
        if (articles != null) {
            Set setArticles = new HashSet<String>(Arrays.asList(articles));
            return setArticles;
        }
        Set<String> pathLoadedArticles = getWordSet(env, settings, "articles");
        if (pathLoadedArticles != null) {
            Set setArticles = new HashSet<String>(pathLoadedArticles);
            return setArticles;
        }

        return null;
    }

    public static Set<?> parseStopWords(Environment env, Settings settings, Set<?> defaultStopWords) {
        String value = settings.get("stopwords");
        if (value != null) {
            if ("_none_".equals(value)) {
                return ImmutableSet.of();
            } else {
                return ImmutableSet.copyOf(Strings.commaDelimitedListToSet(value));
            }
        }
        String[] stopWords = settings.getAsArray("stopwords", null);
        if (stopWords != null) {
            Set setStopWords = new HashSet<String>();
            for (String stopWord : stopWords) {
                if (namedStopWords.containsKey(stopWord)) {
                    setStopWords.addAll(namedStopWords.get(stopWord));
                } else {
                    setStopWords.add(stopWord);
                }
            }
            return setStopWords;
        }
        Set<String> pathLoadedStopWords = getWordSet(env, settings, "stopwords");
        if (pathLoadedStopWords != null) {
            Set setStopWords = new HashSet<String>();
            for (String stopWord : pathLoadedStopWords) {
                if (namedStopWords.containsKey(stopWord)) {
                    setStopWords.addAll(namedStopWords.get(stopWord));
                } else {
                    setStopWords.add(stopWord);
                }
            }
            return setStopWords;
        }

        return defaultStopWords;
    }

    public static Set<String> getWordSet(Environment env, Settings settings, String settingsPrefix) {
        List<String> wordList = getWordList(env, settings, settingsPrefix);
        if (wordList == null) {
            return null;
        }
        return new HashSet<String>(wordList);
    }

    /**
     * Fetches a list of words from the specified settings file. The list should either be available at the key
     * specified by settingsPrefix or in a file specified by settingsPrefix + _path.
     *
     * @throws ElasticSearchIllegalArgumentException
     *          If the word list cannot be found at either key.
     */
    public static List<String> getWordList(Environment env, Settings settings, String settingPrefix) {
        String wordListPath = settings.get(settingPrefix + "_path", null);

        if (wordListPath == null) {
            String[] explicitWordList = settings.getAsArray(settingPrefix, null);
            if (explicitWordList == null) {
                return null;
            } else {
                return Arrays.asList(explicitWordList);
            }
        }

        URL wordListFile = env.resolveConfig(wordListPath);

        try {
            return loadWordList(new InputStreamReader(wordListFile.openStream(), Charsets.UTF_8), "#");
        } catch (IOException ioe) {
            String message = String.format("IOException while reading %s_path: %s", settingPrefix, ioe.getMessage());
            throw new ElasticSearchIllegalArgumentException(message);
        }
    }

    public static List<String> loadWordList(Reader reader, String comment) throws IOException {
        final List<String> result = new ArrayList<String>();
        BufferedReader br = null;
        try {
            if (reader instanceof BufferedReader) {
                br = (BufferedReader) reader;
            } else {
                br = new BufferedReader(reader);
            }
            String word = null;
            while ((word = br.readLine()) != null) {
                if (!Strings.hasText(word)) {
                    continue;
                }
                if (!word.startsWith(comment)) {
                    result.add(word.trim());
                }
            }
        } finally {
            if (br != null)
                br.close();
        }
        return result;
    }
}
