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

import org.locationtech.spatial4j.shape.Point;
import org.locationtech.spatial4j.shape.Shape;
import com.vividsolutions.jts.geom.Coordinate;

import org.elasticsearch.common.geo.XShapeCollection;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MultiPointBuilder extends CoordinateCollection<MultiPointBuilder> {

    public static final GeoShapeType TYPE = GeoShapeType.MULTIPOINT;

    public static final MultiPointBuilder PROTOTYPE = new MultiPointBuilder(new CoordinatesBuilder().coordinate(0.0, 0.0).build());

    /**
     * Create a new {@link MultiPointBuilder}.
     * @param coordinates needs at least two coordinates to be valid, otherwise will throw an exception
     */
    public MultiPointBuilder(List<Coordinate> coordinates) {
        super(coordinates);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(FIELD_TYPE, TYPE.shapeName());
        builder.field(FIELD_COORDINATES);
        super.coordinatesToXcontent(builder, false);
        builder.endObject();
        return builder;
    }

    @Override
    public Shape build() {
        //Could wrap JtsGeometry but probably slower due to conversions to/from JTS in relate()
        //MultiPoint geometry = FACTORY.createMultiPoint(points.toArray(new Coordinate[points.size()]));
        List<Point> shapes = new ArrayList<>(coordinates.size());
        for (Coordinate coord : coordinates) {
            shapes.add(SPATIAL_CONTEXT.makePoint(coord.x, coord.y));
        }
        XShapeCollection<Point> multiPoints = new XShapeCollection<>(shapes, SPATIAL_CONTEXT);
        multiPoints.setPointsOnly(true);
        return multiPoints;
    }

    @Override
    public GeoShapeType type() {
        return TYPE;
    }

    @Override
    public int hashCode() {
        return Objects.hash(coordinates);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        MultiPointBuilder other = (MultiPointBuilder) obj;
        return Objects.equals(coordinates, other.coordinates);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVInt(coordinates.size());
        for (Coordinate point : coordinates) {
            writeCoordinateTo(point, out);
        }
    }

    @Override
    public MultiPointBuilder readFrom(StreamInput in) throws IOException {
        int size = in.readVInt();
        List<Coordinate> points = new ArrayList<Coordinate>(size);
        for (int i=0; i < size; i++) {
            points.add(readCoordinateFrom(in));
        }
        MultiPointBuilder multiPointBuilder = new MultiPointBuilder(points);

        return multiPointBuilder;
    }
}
