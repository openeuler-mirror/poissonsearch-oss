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

package org.elasticsearch.search.facet.geodistance;

import com.google.common.collect.Lists;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.geo.GeoDistance;
import org.elasticsearch.common.geo.GeoHashUtils;
import org.elasticsearch.common.geo.GeoPoint;
import org.elasticsearch.common.geo.GeoUtils;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.DistanceUnit;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.fielddata.IndexGeoPointFieldData;
import org.elasticsearch.index.fielddata.IndexNumericFieldData;
import org.elasticsearch.index.mapper.FieldMapper;
import org.elasticsearch.index.mapper.geo.GeoPointFieldMapper;
import org.elasticsearch.search.facet.Facet;
import org.elasticsearch.search.facet.FacetCollector;
import org.elasticsearch.search.facet.FacetPhaseExecutionException;
import org.elasticsearch.search.facet.FacetProcessor;
import org.elasticsearch.search.internal.SearchContext;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 *
 */
public class GeoDistanceFacetProcessor extends AbstractComponent implements FacetProcessor {

    @Inject
    public GeoDistanceFacetProcessor(Settings settings) {
        super(settings);
        InternalGeoDistanceFacet.registerStreams();
    }

    @Override
    public String[] types() {
        return new String[]{GeoDistanceFacet.TYPE, "geoDistance"};
    }

    @Override
    public FacetCollector parse(String facetName, XContentParser parser, SearchContext context) throws IOException {
        String fieldName = null;
        String valueFieldName = null;
        String valueScript = null;
        String scriptLang = null;
        Map<String, Object> params = null;
        GeoPoint point = new GeoPoint();
        DistanceUnit unit = DistanceUnit.KILOMETERS;
        GeoDistance geoDistance = GeoDistance.ARC;
        List<GeoDistanceFacet.Entry> entries = Lists.newArrayList();

        boolean normalizeLon = true;
        boolean normalizeLat = true;

        XContentParser.Token token;
        String currentName = parser.currentName();

        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentName = parser.currentName();
            } else if (token == XContentParser.Token.START_ARRAY) {
                if ("ranges".equals(currentName) || "entries".equals(currentName)) {
                    // "ranges" : [
                    //     { "from" : 0, "to" : 12.5 }
                    //     { "from" : 12.5 }
                    // ]
                    while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                        double from = Double.NEGATIVE_INFINITY;
                        double to = Double.POSITIVE_INFINITY;
                        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                            if (token == XContentParser.Token.FIELD_NAME) {
                                currentName = parser.currentName();
                            } else if (token.isValue()) {
                                if ("from".equals(currentName)) {
                                    from = parser.doubleValue();
                                } else if ("to".equals(currentName)) {
                                    to = parser.doubleValue();
                                }
                            }
                        }
                        entries.add(new GeoDistanceFacet.Entry(from, to, 0, 0, 0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY));
                    }
                } else {
                    token = parser.nextToken();
                    point.resetLon(parser.doubleValue());
                    token = parser.nextToken();
                    point.resetLat(parser.doubleValue());
                    while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {

                    }
                    fieldName = currentName;
                }
            } else if (token == XContentParser.Token.START_OBJECT) {
                if ("params".equals(currentName)) {
                    params = parser.map();
                } else {
                    // the json in the format of -> field : { lat : 30, lon : 12 }
                    fieldName = currentName;
                    while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                        if (token == XContentParser.Token.FIELD_NAME) {
                            currentName = parser.currentName();
                        } else if (token.isValue()) {
                            if (currentName.equals(GeoPointFieldMapper.Names.LAT)) {
                                point.resetLat(parser.doubleValue());
                            } else if (currentName.equals(GeoPointFieldMapper.Names.LON)) {
                                point.resetLon(parser.doubleValue());
                            } else if (currentName.equals(GeoPointFieldMapper.Names.GEOHASH)) {
                                GeoHashUtils.decode(parser.text(), point);
                            }
                        }
                    }
                }
            } else if (token.isValue()) {
                if (currentName.equals("unit")) {
                    unit = DistanceUnit.fromString(parser.text());
                } else if (currentName.equals("distance_type") || currentName.equals("distanceType")) {
                    geoDistance = GeoDistance.fromString(parser.text());
                } else if ("value_field".equals(currentName) || "valueField".equals(currentName)) {
                    valueFieldName = parser.text();
                } else if ("value_script".equals(currentName) || "valueScript".equals(currentName)) {
                    valueScript = parser.text();
                } else if ("lang".equals(currentName)) {
                    scriptLang = parser.text();
                } else if ("normalize".equals(currentName)) {
                    normalizeLat = parser.booleanValue();
                    normalizeLon = parser.booleanValue();
                } else {
                    // assume the value is the actual value
                    point.resetFromString(parser.text());

                    fieldName = currentName;
                }
            }
        }

        if (entries.isEmpty()) {
            throw new FacetPhaseExecutionException(facetName, "no ranges defined for geo_distance facet");
        }

        if (normalizeLat || normalizeLon) {
            GeoUtils.normalizePoint(point, normalizeLat, normalizeLon);
        }

        FieldMapper keyFieldMapper = context.smartNameFieldMapper(fieldName);
        if (keyFieldMapper == null) {
            throw new FacetPhaseExecutionException(facetName, "failed to find mapping for [" + fieldName + "]");
        }
        IndexGeoPointFieldData keyIndexFieldData = context.fieldData().getForField(keyFieldMapper);

        if (valueFieldName != null) {
            FieldMapper valueFieldMapper = context.smartNameFieldMapper(valueFieldName);
            if (valueFieldMapper == null) {
                throw new FacetPhaseExecutionException(facetName, "failed to find mapping for [" + valueFieldName + "]");
            }
            IndexNumericFieldData valueIndexFieldData = context.fieldData().getForField(valueFieldMapper);
            return new ValueGeoDistanceFacetCollector(facetName, keyIndexFieldData, point.lat(), point.lon(), unit, geoDistance, entries.toArray(new GeoDistanceFacet.Entry[entries.size()]),
                    context, valueIndexFieldData);
        }

        if (valueScript != null) {
            return new ScriptGeoDistanceFacetCollector(facetName, keyIndexFieldData, point.lat(), point.lon(), unit, geoDistance, entries.toArray(new GeoDistanceFacet.Entry[entries.size()]),
                    context, scriptLang, valueScript, params);
        }

        return new GeoDistanceFacetCollector(facetName, keyIndexFieldData, point.lat(), point.lon(), unit, geoDistance, entries.toArray(new GeoDistanceFacet.Entry[entries.size()]),
                context);
    }

    @Override
    public Facet reduce(String name, List<Facet> facets) {
        InternalGeoDistanceFacet agg = null;
        for (Facet facet : facets) {
            InternalGeoDistanceFacet geoDistanceFacet = (InternalGeoDistanceFacet) facet;
            if (agg == null) {
                agg = geoDistanceFacet;
            } else {
                for (int i = 0; i < geoDistanceFacet.entries.length; i++) {
                    GeoDistanceFacet.Entry aggEntry = agg.entries[i];
                    GeoDistanceFacet.Entry currentEntry = geoDistanceFacet.entries[i];
                    aggEntry.count += currentEntry.count;
                    aggEntry.totalCount += currentEntry.totalCount;
                    aggEntry.total += currentEntry.total;
                    if (currentEntry.min < aggEntry.min) {
                        aggEntry.min = currentEntry.min;
                    }
                    if (currentEntry.max > aggEntry.max) {
                        aggEntry.max = currentEntry.max;
                    }
                }
            }
        }
        return agg;
    }
}
