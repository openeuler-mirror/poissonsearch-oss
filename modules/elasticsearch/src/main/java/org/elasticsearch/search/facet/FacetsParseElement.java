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

package org.elasticsearch.search.facet;

import org.apache.lucene.search.Filter;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.collect.Lists;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.query.xcontent.XContentIndexQueryParser;
import org.elasticsearch.search.SearchParseElement;
import org.elasticsearch.search.SearchParseException;
import org.elasticsearch.search.facet.collector.FacetCollector;
import org.elasticsearch.search.facet.collector.FacetCollectorParser;
import org.elasticsearch.search.facet.filter.FilterFacetCollectorParser;
import org.elasticsearch.search.facet.geodistance.GeoDistanceFacetCollectorParser;
import org.elasticsearch.search.facet.histogram.HistogramFacetCollectorParser;
import org.elasticsearch.search.facet.query.QueryFacetCollectorParser;
import org.elasticsearch.search.facet.range.RangeFacetCollectorParser;
import org.elasticsearch.search.facet.statistical.StatisticalFacetCollectorParser;
import org.elasticsearch.search.facet.terms.TermsFacetCollectorParser;
import org.elasticsearch.search.internal.ContextIndexSearcher;
import org.elasticsearch.search.internal.SearchContext;

import java.util.List;

import static org.elasticsearch.common.collect.MapBuilder.*;

/**
 * <pre>
 * facets : {
 *  facet1: {
 *      query : { ... },
 *      global : false
 *  },
 *  facet2: {
 *      terms : {
 *          name : "myfield",
 *          size : 12
 *      },
 *      global : false
 *  }
 * }
 * </pre>
 *
 * @author kimchy (shay.banon)
 */
public class FacetsParseElement implements SearchParseElement {

    private final ImmutableMap<String, FacetCollectorParser> facetCollectorParsers;

    public FacetsParseElement() {
        MapBuilder<String, FacetCollectorParser> builder = newMapBuilder();
        addFacetParser(builder, new TermsFacetCollectorParser());
        addFacetParser(builder, new QueryFacetCollectorParser());
        addFacetParser(builder, new StatisticalFacetCollectorParser());
        addFacetParser(builder, new HistogramFacetCollectorParser());
        addFacetParser(builder, new GeoDistanceFacetCollectorParser());
        addFacetParser(builder, new RangeFacetCollectorParser());
        addFacetParser(builder, new FilterFacetCollectorParser());
        this.facetCollectorParsers = builder.immutableMap();
    }

    private void addFacetParser(MapBuilder<String, FacetCollectorParser> builder, FacetCollectorParser facetCollectorParser) {
        for (String s : facetCollectorParser.names()) {
            builder.put(s, facetCollectorParser);
        }
    }

    @Override public void parse(XContentParser parser, SearchContext context) throws Exception {
        XContentParser.Token token;

        List<FacetCollector> facetCollectors = null;

        String topLevelFieldName = null;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                topLevelFieldName = parser.currentName();
            } else if (token == XContentParser.Token.START_OBJECT) {
                FacetCollector facet = null;
                String scope = ContextIndexSearcher.Scopes.MAIN;
                String facetFieldName = null;
                Filter filter = null;
                boolean cacheFilter = true;
                while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                    if (token == XContentParser.Token.FIELD_NAME) {
                        facetFieldName = parser.currentName();
                    } else if (token == XContentParser.Token.START_OBJECT) {
                        if ("facet_filter".equals(facetFieldName) || "facetFilter".equals(facetFieldName)) {
                            XContentIndexQueryParser indexQueryParser = (XContentIndexQueryParser) context.queryParser();
                            filter = indexQueryParser.parseInnerFilter(parser);
                        } else {
                            FacetCollectorParser facetCollectorParser = facetCollectorParsers.get(facetFieldName);
                            if (facetCollectorParser == null) {
                                throw new SearchParseException(context, "No facet type for [" + facetFieldName + "]");
                            }
                            facet = facetCollectorParser.parse(topLevelFieldName, parser, context);
                        }
                    } else if (token.isValue()) {
                        if ("global".equals(facetFieldName)) {
                            if (parser.booleanValue()) {
                                scope = ContextIndexSearcher.Scopes.GLOBAL;
                            }
                        } else if ("scope".equals(facetFieldName)) {
                            scope = parser.text();
                        } else if ("cache_filter".equals(facetFieldName) || "cacheFilter".equals(facetFieldName)) {
                            cacheFilter = parser.booleanValue();
                        }
                    }
                }
                if (filter != null) {
                    if (cacheFilter) {
                        filter = context.filterCache().cache(filter);
                    }
                    facet.setFilter(filter);
                }

                if (facetCollectors == null) {
                    facetCollectors = Lists.newArrayList();
                }
                facetCollectors.add(facet);
                context.searcher().addCollector(scope, facet);
            }
        }

        context.facets(new SearchContextFacets(facetCollectors));
    }
}
