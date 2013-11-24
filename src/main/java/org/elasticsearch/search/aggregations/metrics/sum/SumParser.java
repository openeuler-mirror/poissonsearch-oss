/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.aggregations.metrics.sum;

import org.elasticsearch.search.aggregations.metrics.ValuesSourceMetricsAggregatorParser;
import org.elasticsearch.search.aggregations.support.ValuesSourceConfig;
import org.elasticsearch.search.aggregations.support.numeric.NumericValuesSource;
import org.elasticsearch.search.aggregations.AggregatorFactory;

/**
 *
 */
public class SumParser extends ValuesSourceMetricsAggregatorParser<InternalSum> {

    @Override
    public String type() {
        return InternalSum.TYPE.name();
    }

    @Override
    protected AggregatorFactory createFactory(String aggregationName, ValuesSourceConfig<NumericValuesSource> config) {
        return new SumAggregator.Factory(aggregationName, config);
    }
}
