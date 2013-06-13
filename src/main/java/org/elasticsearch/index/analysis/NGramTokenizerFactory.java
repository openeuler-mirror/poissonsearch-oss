/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
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

import com.google.common.collect.ImmutableMap;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.ngram.NGramTokenizer;
import org.apache.lucene.analysis.ngram.XNGramTokenizer;
import org.apache.lucene.util.Version;
import org.elasticsearch.ElasticSearchIllegalArgumentException;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.assistedinject.Assisted;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.settings.IndexSettings;

import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Map;

/**
 *
 */
public class NGramTokenizerFactory extends AbstractTokenizerFactory {

    private final int minGram;
    private final int maxGram;
    private final CharMatcher matcher;

    static final Map<String, CharMatcher> MATCHERS;

    static {
        ImmutableMap.Builder<String, CharMatcher> builder = ImmutableMap.builder();
        builder.put("letter",      CharMatcher.Basic.LETTER);
        builder.put("digit",       CharMatcher.Basic.DIGIT);
        builder.put("whitespace",  CharMatcher.Basic.WHITESPACE);
        builder.put("punctuation", CharMatcher.Basic.PUNCTUATION);
        builder.put("symbol",      CharMatcher.Basic.SYMBOL);
        // Populate with unicode categories from java.lang.Character
        for (Field field : Character.class.getFields()) {
            if (!field.getName().startsWith("DIRECTIONALITY")
                    && Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers())
                    && field.getType() == byte.class) {
                try {
                    builder.put(field.getName().toLowerCase(Locale.ROOT), CharMatcher.ByUnicodeCategory.of(field.getByte(null)));
                } catch (Exception e) {
                    // just ignore
                    continue;
                }
            }
        }
        MATCHERS = builder.build();
    }

    static CharMatcher parseTokenChars(String[] characterClasses) {
        if (characterClasses == null || characterClasses.length == 0) {
            return null;
        }
        CharMatcher.Builder builder = new CharMatcher.Builder();
        for (String characterClass : characterClasses) {
            characterClass = characterClass.toLowerCase(Locale.ROOT).trim();
            CharMatcher matcher = MATCHERS.get(characterClass);
            if (matcher == null) {
                throw new ElasticSearchIllegalArgumentException("Unknown token type: '" + characterClass + "', must be one of " + MATCHERS.keySet());
            }
            builder.or(matcher);
        }
        return builder.build();
    }

    @Inject
    public NGramTokenizerFactory(Index index, @IndexSettings Settings indexSettings, @Assisted String name, @Assisted Settings settings) {
        super(index, indexSettings, name, settings);
        this.minGram = settings.getAsInt("min_gram", NGramTokenizer.DEFAULT_MIN_NGRAM_SIZE);
        this.maxGram = settings.getAsInt("max_gram", NGramTokenizer.DEFAULT_MAX_NGRAM_SIZE);
        this.matcher = parseTokenChars(settings.getAsArray("token_chars"));
    }

    @Override
    public Tokenizer create(Reader reader) {
        if (this.version.onOrAfter(Version.LUCENE_43)) {
            // LUCENE MONITOR: this token filter is a copy from lucene trunk and should go away once we upgrade to lucene 4.4
            if (matcher == null) {
                return new XNGramTokenizer(version, reader, minGram, maxGram);
            } else {
                return new XNGramTokenizer(version, reader, minGram, maxGram) {
                    @Override
                    protected boolean isTokenChar(int chr) {
                        return matcher.isTokenChar(chr);
                    }
                };
            }
        }
        return new NGramTokenizer(reader, minGram, maxGram);
    }

}