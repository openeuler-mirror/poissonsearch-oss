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

import com.spatial4j.core.shape.Point;
import com.spatial4j.core.shape.Shape;
import com.spatial4j.core.shape.ShapeCollection;
import org.elasticsearch.common.geo.GeoPoint;
import org.elasticsearch.common.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MultiPointBuilder extends PointCollection<MultiPointBuilder> {

    public static final GeoShapeType TYPE = GeoShapeType.MULTIPOINT;

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(FIELD_TYPE, TYPE.shapename);
        builder.field(FIELD_COORDINATES);
        super.coordinatesToXcontent(builder, false);
        builder.endObject();
        return builder;
    }

    @Override
    public Shape build() {
        //Could wrap JtsGeometry but probably slower due to conversions to/from JTS in relate()
        //MultiPoint geometry = FACTORY.createMultiPoint(points.toArray(new Coordinate[points.size()]));
        List<Point> shapes = new ArrayList<>(points.size());
        for (GeoPoint coord : points) {
            shapes.add(SPATIAL_CONTEXT.makePoint(coord.x, coord.y));
        }
        return new ShapeCollection<>(shapes, SPATIAL_CONTEXT);
    }

    @Override
    public GeoShapeType type() {
        return TYPE;
    }
}
