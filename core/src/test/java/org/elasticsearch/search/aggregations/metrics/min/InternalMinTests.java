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

package org.elasticsearch.search.aggregations.metrics.min;

import org.elasticsearch.common.io.stream.Writeable.Reader;
import org.elasticsearch.search.DocValueFormat;
import org.elasticsearch.search.aggregations.InternalAggregationTestCase;
import org.elasticsearch.search.aggregations.ParsedAggregation;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;

import java.util.List;
import java.util.Map;

public class InternalMinTests extends InternalAggregationTestCase<InternalMin> {
    @Override
    protected InternalMin createTestInstance(String name, List<PipelineAggregator> pipelineAggregators, Map<String, Object> metaData) {
        double value = frequently() ? randomDouble() : randomFrom(new Double[] { Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY });
        DocValueFormat formatter = randomFrom(new DocValueFormat.Decimal("###.##"), DocValueFormat.BOOLEAN, DocValueFormat.RAW);
        return new InternalMin(name, value, formatter, pipelineAggregators, metaData);
    }

    @Override
    protected Reader<InternalMin> instanceReader() {
        return InternalMin::new;
    }

    @Override
    protected void assertReduced(InternalMin reduced, List<InternalMin> inputs) {
        assertEquals(inputs.stream().mapToDouble(InternalMin::value).min().getAsDouble(), reduced.value(), 0);
    }

    @Override
    protected void assertFromXContent(InternalMin min, ParsedAggregation parsedAggregation) {
        ParsedMin parsed = ((ParsedMin) parsedAggregation);
        if (Double.isInfinite(min.getValue()) == false) {
            assertEquals(min.getValue(), parsed.getValue(), Double.MIN_VALUE);
            assertEquals(min.getValueAsString(), parsed.getValueAsString());
        } else {
            // we write Double.NEGATIVE_INFINITY and Double.POSITIVE_INFINITY to xContent as 'null', so we
            // cannot differentiate between them. Also we cannot recreate the exact String representation
            assertEquals(parsed.getValue(), Double.POSITIVE_INFINITY, 0);
        }
    }
}
