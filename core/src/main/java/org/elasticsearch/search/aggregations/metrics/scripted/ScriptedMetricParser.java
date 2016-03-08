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

package org.elasticsearch.search.aggregations.metrics.scripted;

import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.query.QueryParseContext;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptParameterParser;
import org.elasticsearch.script.ScriptParameterParser.ScriptParameterValue;
import org.elasticsearch.search.aggregations.Aggregator;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ScriptedMetricParser implements Aggregator.Parser {

    public static final String INIT_SCRIPT = "init_script";
    public static final String MAP_SCRIPT = "map_script";
    public static final String COMBINE_SCRIPT = "combine_script";
    public static final String REDUCE_SCRIPT = "reduce_script";
    public static final ParseField INIT_SCRIPT_FIELD = new ParseField("init_script");
    public static final ParseField MAP_SCRIPT_FIELD = new ParseField("map_script");
    public static final ParseField COMBINE_SCRIPT_FIELD = new ParseField("combine_script");
    public static final ParseField REDUCE_SCRIPT_FIELD = new ParseField("reduce_script");
    public static final ParseField PARAMS_FIELD = new ParseField("params");
    public static final ParseField REDUCE_PARAMS_FIELD = new ParseField("reduce_params");
    public static final ParseField LANG_FIELD = new ParseField("lang");

    @Override
    public String type() {
        return InternalScriptedMetric.TYPE.name();
    }

    @Override
    public ScriptedMetricAggregatorBuilder parse(String aggregationName, XContentParser parser,
            QueryParseContext context) throws IOException {
        Script initScript = null;
        Script mapScript = null;
        Script combineScript = null;
        Script reduceScript = null;
        Map<String, Object> params = null;
        Map<String, Object> reduceParams = null;
        XContentParser.Token token;
        String currentFieldName = null;
        Set<String> scriptParameters = new HashSet<>();
        scriptParameters.add(INIT_SCRIPT);
        scriptParameters.add(MAP_SCRIPT);
        scriptParameters.add(COMBINE_SCRIPT);
        scriptParameters.add(REDUCE_SCRIPT);
        ScriptParameterParser scriptParameterParser = new ScriptParameterParser(scriptParameters);

        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token == XContentParser.Token.START_OBJECT) {
                if (context.parseFieldMatcher().match(currentFieldName, INIT_SCRIPT_FIELD)) {
                    initScript = Script.parse(parser, context.parseFieldMatcher());
                } else if (context.parseFieldMatcher().match(currentFieldName, MAP_SCRIPT_FIELD)) {
                    mapScript = Script.parse(parser, context.parseFieldMatcher());
                } else if (context.parseFieldMatcher().match(currentFieldName, COMBINE_SCRIPT_FIELD)) {
                    combineScript = Script.parse(parser, context.parseFieldMatcher());
                } else if (context.parseFieldMatcher().match(currentFieldName, REDUCE_SCRIPT_FIELD)) {
                    reduceScript = Script.parse(parser, context.parseFieldMatcher());
                } else if (context.parseFieldMatcher().match(currentFieldName, PARAMS_FIELD)) {
                    params = parser.map();
                } else if (context.parseFieldMatcher().match(currentFieldName, REDUCE_PARAMS_FIELD)) {
                  reduceParams = parser.map();
                } else {
                    throw new ParsingException(parser.getTokenLocation(),
                            "Unknown key for a " + token + " in [" + aggregationName + "]: [" + currentFieldName + "].");
                }
            } else if (token.isValue()) {
                if (!scriptParameterParser.token(currentFieldName, token, parser, context.parseFieldMatcher())) {
                    throw new ParsingException(parser.getTokenLocation(),
                            "Unknown key for a " + token + " in [" + aggregationName + "]: [" + currentFieldName + "].");
                }
            } else {
                throw new ParsingException(parser.getTokenLocation(), "Unexpected token " + token + " in [" + aggregationName + "].");
            }
        }

        if (initScript == null) { // Didn't find anything using the new API so try using the old one instead
            ScriptParameterValue scriptValue = scriptParameterParser.getScriptParameterValue(INIT_SCRIPT);
            if (scriptValue != null) {
                initScript = new Script(scriptValue.script(), scriptValue.scriptType(), scriptParameterParser.lang(), params);
            }
        } else if (initScript.getParams() != null) {
            throw new ParsingException(parser.getTokenLocation(),
                    "init_script params are not supported. Parameters for the init_script must be specified in the params field on the scripted_metric aggregator not inside the init_script object");
        }

        if (mapScript == null) { // Didn't find anything using the new API so try using the old one instead
            ScriptParameterValue scriptValue = scriptParameterParser.getScriptParameterValue(MAP_SCRIPT);
            if (scriptValue != null) {
                mapScript = new Script(scriptValue.script(), scriptValue.scriptType(), scriptParameterParser.lang(), params);
            }
        } else if (mapScript.getParams() != null) {
            throw new ParsingException(parser.getTokenLocation(),
                    "map_script params are not supported. Parameters for the map_script must be specified in the params field on the scripted_metric aggregator not inside the map_script object");
        }

        if (combineScript == null) { // Didn't find anything using the new API so try using the old one instead
            ScriptParameterValue scriptValue = scriptParameterParser.getScriptParameterValue(COMBINE_SCRIPT);
            if (scriptValue != null) {
                combineScript = new Script(scriptValue.script(), scriptValue.scriptType(), scriptParameterParser.lang(), params);
            }
        } else if (combineScript.getParams() != null) {
            throw new ParsingException(parser.getTokenLocation(),
                    "combine_script params are not supported. Parameters for the combine_script must be specified in the params field on the scripted_metric aggregator not inside the combine_script object");
        }

        if (reduceScript == null) { // Didn't find anything using the new API so try using the old one instead
            ScriptParameterValue scriptValue = scriptParameterParser.getScriptParameterValue(REDUCE_SCRIPT);
            if (scriptValue != null) {
                reduceScript = new Script(scriptValue.script(), scriptValue.scriptType(), scriptParameterParser.lang(), reduceParams);
            }
        }

        if (mapScript == null) {
            throw new ParsingException(parser.getTokenLocation(), "map_script field is required in [" + aggregationName + "].");
        }

        ScriptedMetricAggregatorBuilder factory = new ScriptedMetricAggregatorBuilder(aggregationName);
        if (initScript != null) {
            factory.initScript(initScript);
        }
        if (mapScript != null) {
            factory.mapScript(mapScript);
        }
        if (combineScript != null) {
            factory.combineScript(combineScript);
        }
        if (reduceScript != null) {
            factory.reduceScript(reduceScript);
        }
        if (params != null) {
            factory.params(params);
        }
        return factory;
    }

    @Override
    public ScriptedMetricAggregatorBuilder getFactoryPrototypes() {
        return ScriptedMetricAggregatorBuilder.PROTOTYPE;
    }

}
