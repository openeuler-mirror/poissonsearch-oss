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

package org.elasticsearch.common.geo.builders;

import com.vividsolutions.jts.geom.Coordinate;

import org.elasticsearch.common.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.ArrayList;

public class LineStringBuilder extends BaseLineStringBuilder<LineStringBuilder> {

    public LineStringBuilder() {
        this(new ArrayList<Coordinate>());
    }

    public LineStringBuilder(ArrayList<Coordinate> points) {
        super(points);
    }

    public static final GeoShapeType TYPE = GeoShapeType.LINESTRING;

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(FIELD_TYPE, TYPE.shapename);
        builder.field(FIELD_COORDINATES);
        coordinatesToXcontent(builder, false);
        builder.endObject();
        return builder;
    }

    @Override
    public GeoShapeType type() {
        return TYPE;
    }

    /**
     * Closes the current lineString by adding the starting point as the end point
     */
    public LineStringBuilder close() {
        Coordinate start = points.get(0);
        Coordinate end = points.get(points.size()-1);
        if(start.x != end.x || start.y != end.y) {
            points.add(start);
        }
        return this;
    }

}
