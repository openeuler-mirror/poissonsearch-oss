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

package org.elasticsearch.index.query;

import com.spatial4j.core.shape.Point;

import org.apache.lucene.search.Query;
import org.elasticsearch.common.geo.GeoDistance;
import org.elasticsearch.common.geo.GeoPoint;
import org.elasticsearch.common.unit.DistanceUnit;
import org.elasticsearch.index.search.geo.GeoDistanceRangeQuery;
import org.elasticsearch.test.geo.RandomShapeGenerator;
import org.junit.Test;

import java.io.IOException;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

public class GeoDistanceQueryBuilderTests extends AbstractQueryTestCase<GeoDistanceQueryBuilder> {

    @Override
    protected GeoDistanceQueryBuilder doCreateTestQueryBuilder() {
        GeoDistanceQueryBuilder qb = new GeoDistanceQueryBuilder(GEO_POINT_FIELD_NAME);
        String distance = "" + randomDouble();
        if (randomBoolean()) {
            DistanceUnit unit = randomFrom(DistanceUnit.values());
            distance = distance + unit.toString();
        }
        int selector = randomIntBetween(0, 2);
        switch (selector) {
            case 0:
                qb.distance(randomDouble(), randomFrom(DistanceUnit.values()));
                break;
            case 1:
                qb.distance(distance, randomFrom(DistanceUnit.values()));
                break;
            case 2:
                qb.distance(distance);
                break;
        }

        Point p = RandomShapeGenerator.xRandomPoint(random());
        qb.point(new GeoPoint(p.getY(), p.getX()));

        if (randomBoolean()) {
            qb.coerce(randomBoolean());
        }

        if (randomBoolean()) {
            qb.ignoreMalformed(randomBoolean());
        }

        if (randomBoolean()) {
            qb.optimizeBbox(randomFrom("none", "memory", "indexed"));
        }

        if (randomBoolean()) {
            qb.geoDistance(randomFrom(GeoDistance.values()));
        }
        return qb;
    }

    public void testIllegalValues() {
        try {
            if (randomBoolean()) {
                new GeoDistanceQueryBuilder("");
            } else {
                new GeoDistanceQueryBuilder(null);
            }
            fail("must not be null or empty");
        } catch (IllegalArgumentException ex) {
            // expected
        }

        GeoDistanceQueryBuilder query = new GeoDistanceQueryBuilder("fieldName");
        try {
            if (randomBoolean()) {
                query.distance("");
            } else {
                query.distance(null);
            }
            fail("must not be null or empty");
        } catch (IllegalArgumentException ex) {
            // expected
        }

        try {
            if (randomBoolean()) {
                query.distance("", DistanceUnit.DEFAULT);
            } else {
                query.distance(null, DistanceUnit.DEFAULT);
            }
            fail("must not be null or empty");
        } catch (IllegalArgumentException ex) {
            // expected
        }

        try {
            query.distance("1", null);
            fail("must not be null");
        } catch (IllegalArgumentException ex) {
            // expected
        }

        try {
            query.distance(1, null);
            fail("must not be null");
        } catch (IllegalArgumentException ex) {
            // expected
        }

        try {
            query.geohash(null);
            fail("geohash must not be null");
        } catch (IllegalArgumentException ex) {
            // expected
        }

        try {
            query.geoDistance(null);
            fail("geodistance must not be null");
        } catch (IllegalArgumentException ex) {
            // expected
        }

        try {
            query.optimizeBbox(null);
            fail("optimizeBbox must not be null");
        } catch (IllegalArgumentException ex) {
            // expected
        }
    }

    /**
     * Overridden here to ensure the test is only run if at least one type is
     * present in the mappings. Geo queries do not execute if the field is not
     * explicitly mapped
     */
    @Override
    @Test
    public void testToQuery() throws IOException {
        assumeTrue("test runs only when at least a type is registered", getCurrentTypes().length > 0);
        super.testToQuery();
    }

    @Override
    protected void doAssertLuceneQuery(GeoDistanceQueryBuilder queryBuilder, Query query, QueryShardContext context) throws IOException {
        assertThat(query, instanceOf(GeoDistanceRangeQuery.class));
        GeoDistanceRangeQuery geoQuery = (GeoDistanceRangeQuery) query;
        assertThat(geoQuery.fieldName(), equalTo(queryBuilder.fieldName()));
        if (queryBuilder.point() != null) {
            assertThat(geoQuery.lat(), equalTo(queryBuilder.point().lat()));
            assertThat(geoQuery.lon(), equalTo(queryBuilder.point().lon()));
        }
        assertThat(geoQuery.geoDistance(), equalTo(queryBuilder.geoDistance()));
        assertThat(geoQuery.minInclusiveDistance(), equalTo(Double.NEGATIVE_INFINITY));
        double distance = queryBuilder.distance();
        if (queryBuilder.geoDistance() != null) {
                distance = queryBuilder.geoDistance().normalize(distance, DistanceUnit.DEFAULT);
        }
        assertThat(geoQuery.maxInclusiveDistance(), closeTo(distance, Math.abs(distance) / 1000));
    }

}
