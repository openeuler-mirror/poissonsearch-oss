/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.analysis.phonetic;

import org.apache.commons.codec.Encoder;
import org.apache.commons.codec.language.*;
import org.apache.commons.codec.language.bm.BeiderMorseEncoder;
import org.apache.commons.codec.language.bm.NameType;
import org.apache.commons.codec.language.bm.RuleType;
import org.apache.lucene.analysis.TokenStream;
import org.elasticsearch.ElasticSearchIllegalArgumentException;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.assistedinject.Assisted;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.analysis.AbstractTokenFilterFactory;
import org.elasticsearch.index.analysis.AnalysisSettingsRequired;
import org.elasticsearch.index.settings.IndexSettings;

/**
 *
 */
@AnalysisSettingsRequired
public class PhoneticTokenFilterFactory extends AbstractTokenFilterFactory {

    private final Encoder encoder;

    private final boolean replace;

    @Inject
    public PhoneticTokenFilterFactory(Index index, @IndexSettings Settings indexSettings, @Assisted String name, @Assisted Settings settings) {
        super(index, indexSettings, name, settings);
        this.replace = settings.getAsBoolean("replace", true);
        String encoder = settings.get("encoder");
        if (encoder == null) {
            throw new ElasticSearchIllegalArgumentException("encoder must be set on phonetic token filter");
        }
        if ("metaphone".equalsIgnoreCase(encoder)) {
            this.encoder = new Metaphone();
        } else if ("soundex".equalsIgnoreCase(encoder)) {
            this.encoder = new Soundex();
        } else if ("caverphone1".equalsIgnoreCase(encoder)) {
            this.encoder = new Caverphone1();
        } else if ("caverphone2".equalsIgnoreCase(encoder)) {
            this.encoder = new Caverphone2();
        } else if ("caverphone".equalsIgnoreCase(encoder)) {
            this.encoder = new Caverphone2();
        } else if ("refined_soundex".equalsIgnoreCase(encoder) || "refinedSoundex".equalsIgnoreCase(encoder)) {
            this.encoder = new RefinedSoundex();
        } else if ("cologne".equalsIgnoreCase(encoder)) {
            this.encoder = new ColognePhonetic();
        } else if ("double_metaphone".equalsIgnoreCase(encoder) || "doubleMetaphone".equalsIgnoreCase(encoder)) {
            DoubleMetaphone doubleMetaphone = new DoubleMetaphone();
            doubleMetaphone.setMaxCodeLen(settings.getAsInt("max_code_len", doubleMetaphone.getMaxCodeLen()));
            this.encoder = doubleMetaphone;
        } else if ("bm".equalsIgnoreCase(encoder) || "beider_morse".equalsIgnoreCase(encoder)) {
            BeiderMorseEncoder bm = new BeiderMorseEncoder();
            String ruleType = settings.get("rule_type", "approx");
            if ("approx".equalsIgnoreCase(ruleType)) {
                bm.setRuleType(RuleType.APPROX);
            } else if ("exact".equalsIgnoreCase(ruleType)) {
                bm.setRuleType(RuleType.EXACT);
            } else {
                throw new ElasticSearchIllegalArgumentException("No matching rule type [" + ruleType + "] for beider morse encoder");
            }
            String nameType = settings.get("name_type", "generic");
            if ("GENERIC".equalsIgnoreCase(nameType)) {
                bm.setNameType(NameType.GENERIC);
            } else if ("ASHKENAZI".equalsIgnoreCase(nameType)) {
                bm.setNameType(NameType.ASHKENAZI);
            } else if ("SEPHARDIC".equalsIgnoreCase(nameType)) {
                bm.setNameType(NameType.SEPHARDIC);
            }
            this.encoder = bm;
        } else {
            throw new ElasticSearchIllegalArgumentException("unknown encoder [" + encoder + "] for phonetic token filter");
        }
    }

    @Override
    public TokenStream create(TokenStream tokenStream) {
        if (encoder instanceof DoubleMetaphone) {
            return new DoubleMetaphoneFilter(tokenStream, (DoubleMetaphone) encoder, !replace);
        }
        return new PhoneticFilter(tokenStream, encoder, name(), !replace);
    }
}