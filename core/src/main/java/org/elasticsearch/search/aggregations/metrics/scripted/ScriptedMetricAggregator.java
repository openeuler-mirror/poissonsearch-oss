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

import org.apache.lucene.index.LeafReaderContext;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.script.ExecutableScript;
import org.elasticsearch.script.LeafSearchScript;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.script.SearchScript;
import org.elasticsearch.search.SearchParseException;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.AggregatorFactory;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.LeafBucketCollector;
import org.elasticsearch.search.aggregations.LeafBucketCollectorBase;
import org.elasticsearch.search.aggregations.metrics.MetricsAggregator;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;
import org.elasticsearch.search.aggregations.support.AggregationContext;
import org.elasticsearch.search.internal.SearchContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

public class ScriptedMetricAggregator extends MetricsAggregator {

    private final SearchScript mapScript;
    private final ExecutableScript combineScript;
    private final Script reduceScript;
    private Map<String, Object> params;

    protected ScriptedMetricAggregator(String name, Script initScript, Script mapScript, Script combineScript, Script reduceScript,
            Map<String, Object> params, AggregationContext context, Aggregator parent, List<PipelineAggregator> pipelineAggregators, Map<String, Object> metaData)
            throws IOException {
        super(name, context, parent, pipelineAggregators, metaData);
        this.params = params;
        ScriptService scriptService = context.searchContext().scriptService();
        if (initScript != null) {
            scriptService.executable(initScript, ScriptContext.Standard.AGGS, context.searchContext(), Collections.emptyMap()).run();
        }
        this.mapScript = scriptService.search(context.searchContext().lookup(), mapScript, ScriptContext.Standard.AGGS, Collections.emptyMap());
        if (combineScript != null) {
            this.combineScript = scriptService.executable(combineScript, ScriptContext.Standard.AGGS, context.searchContext(), Collections.emptyMap());
        } else {
            this.combineScript = null;
        }
        this.reduceScript = reduceScript;
    }

    @Override
    public boolean needsScores() {
        return true; // TODO: how can we know if the script relies on scores?
    }

    @Override
    public LeafBucketCollector getLeafCollector(LeafReaderContext ctx,
            final LeafBucketCollector sub) throws IOException {
        final LeafSearchScript leafMapScript = mapScript.getLeafSearchScript(ctx);
        return new LeafBucketCollectorBase(sub, mapScript) {
            @Override
            public void collect(int doc, long bucket) throws IOException {
                assert bucket == 0 : bucket;
                leafMapScript.setDocument(doc);
                leafMapScript.run();
            }
        };
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrdinal) {
        Object aggregation;
        if (combineScript != null) {
            aggregation = combineScript.run();
        } else {
            aggregation = params.get("_agg");
        }
        return new InternalScriptedMetric(name, aggregation, reduceScript, pipelineAggregators(),
                metaData());
    }

    @Override
    public InternalAggregation buildEmptyAggregation() {
        return new InternalScriptedMetric(name, null, reduceScript, pipelineAggregators(), metaData());
    }

    public static class Factory extends AggregatorFactory {

        private Script initScript;
        private Script mapScript;
        private Script combineScript;
        private Script reduceScript;
        private Map<String, Object> params;

        public Factory(String name) {
            super(name, InternalScriptedMetric.TYPE);
        }

        /**
         * Set the <tt>init</tt> script.
         */
        public void initScript(Script initScript) {
            this.initScript = initScript;
        }

        /**
         * Set the <tt>map</tt> script.
         */
        public void mapScript(Script mapScript) {
            this.mapScript = mapScript;
        }

        /**
         * Set the <tt>combine</tt> script.
         */
        public void combineScript(Script combineScript) {
            this.combineScript = combineScript;
        }

        /**
         * Set the <tt>reduce</tt> script.
         */
        public void reduceScript(Script reduceScript) {
            this.reduceScript = reduceScript;
        }

        /**
         * Set parameters that will be available in the <tt>init</tt>,
         * <tt>map</tt> and <tt>combine</tt> phases.
         */
        public void params(Map<String, Object> params) {
            this.params = params;
        }

        @Override
        public Aggregator createInternal(AggregationContext context, Aggregator parent, boolean collectsFromSingleBucket,
                List<PipelineAggregator> pipelineAggregators, Map<String, Object> metaData) throws IOException {
            if (collectsFromSingleBucket == false) {
                return asMultiBucketAggregator(this, context, parent);
            }
            Map<String, Object> params = this.params;
            if (params != null) {
                params = deepCopyParams(params, context.searchContext());
            } else {
                params = new HashMap<>();
                params.put("_agg", new HashMap<String, Object>());
            }
            return new ScriptedMetricAggregator(name, insertParams(initScript, params), insertParams(mapScript, params), insertParams(
                    combineScript, params), deepCopyScript(reduceScript, context.searchContext()), params, context, parent, pipelineAggregators,
                    metaData);
            }

        private static Script insertParams(Script script, Map<String, Object> params) {
            if (script == null) {
                return null;
            }
            return new Script(script.getScript(), script.getType(), script.getLang(), params);
        }

        private static Script deepCopyScript(Script script, SearchContext context) {
            if (script != null) {
                Map<String, Object> params = script.getParams();
                if (params != null) {
                    params = deepCopyParams(params, context);
                }
                return new Script(script.getScript(), script.getType(), script.getLang(), params);
            } else {
                return null;
            }
        }

        @SuppressWarnings({ "unchecked" })
        private static <T> T deepCopyParams(T original, SearchContext context) {
            T clone;
            if (original instanceof Map) {
                Map<?, ?> originalMap = (Map<?, ?>) original;
                Map<Object, Object> clonedMap = new HashMap<>();
                for (Entry<?, ?> e : originalMap.entrySet()) {
                    clonedMap.put(deepCopyParams(e.getKey(), context), deepCopyParams(e.getValue(), context));
                }
                clone = (T) clonedMap;
            } else if (original instanceof List) {
                List<?> originalList = (List<?>) original;
                List<Object> clonedList = new ArrayList<Object>();
                for (Object o : originalList) {
                    clonedList.add(deepCopyParams(o, context));
                }
                clone = (T) clonedList;
            } else if (original instanceof String || original instanceof Integer || original instanceof Long || original instanceof Short
                    || original instanceof Byte || original instanceof Float || original instanceof Double || original instanceof Character
                    || original instanceof Boolean) {
                clone = original;
            } else {
                throw new SearchParseException(context, "Can only clone primitives, String, ArrayList, and HashMap. Found: "
                        + original.getClass().getCanonicalName(), null);
            }
            return clone;
        }

        @Override
        protected XContentBuilder internalXContent(XContentBuilder builder, Params builderParams) throws IOException {
            builder.startObject();
            if (initScript != null) {
                builder.field(ScriptedMetricParser.INIT_SCRIPT_FIELD.getPreferredName(), initScript);
            }

            if (mapScript != null) {
                builder.field(ScriptedMetricParser.MAP_SCRIPT_FIELD.getPreferredName(), mapScript);
            }

            if (combineScript != null) {
                builder.field(ScriptedMetricParser.COMBINE_SCRIPT_FIELD.getPreferredName(), combineScript);
            }

            if (reduceScript != null) {
                builder.field(ScriptedMetricParser.REDUCE_SCRIPT_FIELD.getPreferredName(), reduceScript);
            }
            if (params != null) {
                builder.field(ScriptedMetricParser.PARAMS_FIELD.getPreferredName());
                builder.map(params);
            }
            builder.endObject();
            return builder;
        }

        @Override
        protected AggregatorFactory doReadFrom(String name, StreamInput in) throws IOException {
            Factory factory = new Factory(name);
            factory.initScript = in.readOptionalStreamable(Script.SUPPLIER);
            factory.mapScript = in.readOptionalStreamable(Script.SUPPLIER);
            factory.combineScript = in.readOptionalStreamable(Script.SUPPLIER);
            factory.reduceScript = in.readOptionalStreamable(Script.SUPPLIER);
            if (in.readBoolean()) {
                factory.params = in.readMap();
            }
            return factory;
        }

        @Override
        protected void doWriteTo(StreamOutput out) throws IOException {
            out.writeOptionalStreamable(initScript);
            out.writeOptionalStreamable(mapScript);
            out.writeOptionalStreamable(combineScript);
            out.writeOptionalStreamable(reduceScript);
            boolean hasParams = params != null;
            out.writeBoolean(hasParams);
            if (hasParams) {
                out.writeMap(params);
            }
        }

        @Override
        protected int doHashCode() {
            return Objects.hash(initScript, mapScript, combineScript, reduceScript, params);
        }

        @Override
        protected boolean doEquals(Object obj) {
            Factory other = (Factory) obj;
            return Objects.equals(initScript, other.initScript)
                    && Objects.equals(mapScript, other.mapScript)
                    && Objects.equals(combineScript, other.combineScript)
                    && Objects.equals(reduceScript, other.reduceScript)
                    && Objects.equals(params, other.params);
        }

    }

}
