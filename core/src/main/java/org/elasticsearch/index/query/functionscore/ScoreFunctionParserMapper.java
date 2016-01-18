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

package org.elasticsearch.index.query.functionscore;

import java.util.Map;

import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.xcontent.XContentLocation;

import static java.util.Collections.unmodifiableMap;

public class ScoreFunctionParserMapper {

    protected Map<String, ScoreFunctionParser<?>> functionParsers;

    public ScoreFunctionParserMapper(Map<String, ScoreFunctionParser<?>> functionParsers) {
        this.functionParsers = unmodifiableMap(functionParsers);
    }

    public ScoreFunctionParser<?> get(XContentLocation contentLocation, String parserName) {
        ScoreFunctionParser<?> functionParser = get(parserName);
        if (functionParser == null) {
            throw new ParsingException(contentLocation, "No function with the name [" + parserName + "] is registered.");
        }
        return functionParser;
    }

    private ScoreFunctionParser<?> get(String parserName) {
        return functionParsers.get(parserName);
    }
}
