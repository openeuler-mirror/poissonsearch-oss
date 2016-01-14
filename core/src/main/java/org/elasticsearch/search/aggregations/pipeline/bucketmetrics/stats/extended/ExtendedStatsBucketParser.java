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

package org.elasticsearch.search.aggregations.pipeline.bucketmetrics.stats.extended;

import org.elasticsearch.common.ParseField;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregatorFactory;
import org.elasticsearch.search.aggregations.pipeline.bucketmetrics.BucketMetricsFactory;
import org.elasticsearch.search.aggregations.pipeline.bucketmetrics.BucketMetricsParser;

import java.text.ParseException;
import java.util.Map;

public class ExtendedStatsBucketParser extends BucketMetricsParser {
    static final ParseField SIGMA = new ParseField("sigma");

    @Override
    public String type() {
        return ExtendedStatsBucketPipelineAggregator.TYPE.name();
    }

    @Override
    protected BucketMetricsFactory buildFactory(String pipelineAggregatorName, String bucketsPath, Map<String, Object> unparsedParams)
            throws ParseException {

        Double sigma = null;
        Object param = unparsedParams.get(SIGMA.getPreferredName());

        if (param != null) {
            if (param instanceof Double) {
                sigma = (Double) param;
                unparsedParams.remove(SIGMA.getPreferredName());
            } else {
                throw new ParseException("Parameter [" + SIGMA.getPreferredName() + "] must be a Double, type `"
                        + param.getClass().getSimpleName() + "` provided instead", 0);
            }
        }
        ExtendedStatsBucketPipelineAggregator.Factory factory = new ExtendedStatsBucketPipelineAggregator.Factory(pipelineAggregatorName,
                bucketsPath);
        if (sigma != null) {
            factory.sigma(sigma);
        }
        return factory;
    }

    @Override
    public PipelineAggregatorFactory getFactoryPrototype() {
        return new ExtendedStatsBucketPipelineAggregator.Factory(null, null);
    }
}
