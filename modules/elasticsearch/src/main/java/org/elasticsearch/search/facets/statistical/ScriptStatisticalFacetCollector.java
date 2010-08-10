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

package org.elasticsearch.search.facets.statistical;

import org.apache.lucene.index.IndexReader;
import org.elasticsearch.index.field.function.FieldsFunction;
import org.elasticsearch.index.field.function.script.ScriptFieldsFunction;
import org.elasticsearch.search.facets.Facet;
import org.elasticsearch.search.facets.support.AbstractFacetCollector;
import org.elasticsearch.search.internal.SearchContext;

import java.io.IOException;
import java.util.Map;

/**
 * @author kimchy (shay.banon)
 */
public class ScriptStatisticalFacetCollector extends AbstractFacetCollector {

    private final FieldsFunction function;

    private final Map<String, Object> params;

    private double min = Double.NaN;

    private double max = Double.NaN;

    private double total = 0;

    private double sumOfSquares = 0.0;

    private long count;

    public ScriptStatisticalFacetCollector(String facetName, String script, Map<String, Object> params, SearchContext context) {
        super(facetName);
        this.params = params;
        this.function = new ScriptFieldsFunction(script, context.scriptService(), context.mapperService(), context.fieldDataCache());
    }

    @Override protected void doCollect(int doc) throws IOException {
        double value = ((Number) function.execute(doc, params)).doubleValue();
        if (value < min || Double.isNaN(min)) {
            min = value;
        }
        if (value > max || Double.isNaN(max)) {
            max = value;
        }
        sumOfSquares += value * value;
        total += value;
        count++;
    }

    @Override protected void doSetNextReader(IndexReader reader, int docBase) throws IOException {
        function.setNextReader(reader);
    }

    @Override public Facet facet() {
        return new InternalStatisticalFacet(facetName, "_na", min, max, total, sumOfSquares, count);
    }
}