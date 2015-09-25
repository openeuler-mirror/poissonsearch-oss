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

import com.spatial4j.core.io.GeohashUtils;
import com.spatial4j.core.shape.Rectangle;
import org.apache.lucene.search.*;
import org.elasticsearch.common.geo.GeoPoint;
import org.elasticsearch.common.geo.GeoUtils;
import org.elasticsearch.index.search.geo.InMemoryGeoBoundingBoxQuery;
import org.elasticsearch.test.geo.RandomShapeGenerator;
import org.junit.Test;

import java.io.IOException;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;

public class GeoBoundingBoxQueryBuilderTests extends AbstractQueryTestCase<GeoBoundingBoxQueryBuilder> {
    /** Randomly generate either NaN or one of the two infinity values. */
    private static Double[] brokenDoubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
    
    @Override
    protected GeoBoundingBoxQueryBuilder doCreateTestQueryBuilder() {
        GeoBoundingBoxQueryBuilder builder = new GeoBoundingBoxQueryBuilder(GEO_POINT_FIELD_NAME);
        Rectangle box = RandomShapeGenerator.xRandomRectangle(getRandom(), RandomShapeGenerator.xRandomPoint(getRandom()));

        if (randomBoolean()) {
            // check the top-left/bottom-right combination of setters
            int path = randomIntBetween(0, 2);
            switch (path) {
            case 0:
                builder.setCorners(
                        new GeoPoint(box.getMaxY(), box.getMinX()), 
                        new GeoPoint(box.getMinY(), box.getMaxX()));
                break;
            case 1:
                builder.setCorners(
                        GeohashUtils.encodeLatLon(box.getMaxY(), box.getMinX()),
                        GeohashUtils.encodeLatLon(box.getMinY(), box.getMaxX()));
                break;
            default:
                builder.setCorners(box.getMaxY(), box.getMinX(), box.getMinY(), box.getMaxX());
            }
        } else {
            // check the bottom-left/ top-right combination of setters
            if (randomBoolean()) {
                builder.setCornersOGC(
                        new GeoPoint(box.getMinY(), box.getMinX()),
                        new GeoPoint(box.getMaxY(), box.getMaxX()));
            } else {
                builder.setCornersOGC(
                        GeohashUtils.encodeLatLon(box.getMinY(), box.getMinX()),
                        GeohashUtils.encodeLatLon(box.getMaxY(), box.getMaxX()));
            }
        }

        if (randomBoolean()) {
            builder.setValidationMethod(randomFrom(GeoValidationMethod.values()));
        }

        builder.type(randomFrom(GeoExecType.values()));
        return builder;
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidationNullFieldname() {
        new GeoBoundingBoxQueryBuilder(null);
    }


    @Test(expected = IllegalArgumentException.class)
    public void testValidationNullType() {
        GeoBoundingBoxQueryBuilder qb = new GeoBoundingBoxQueryBuilder("teststring");
        qb.type((GeoExecType) null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidationNullTypeString() {
        GeoBoundingBoxQueryBuilder qb = new GeoBoundingBoxQueryBuilder("teststring");
        qb.type((String) null);
    }

    @Test
    @Override
    public void testToQuery() throws IOException {
        assumeTrue("test runs only when at least a type is registered", getCurrentTypes().length > 0);
        super.testToQuery();
    }
    
    @Test(expected = QueryShardException.class)
    public void testExceptionOnMissingTypes() throws IOException {
        assumeTrue("test runs only when at least a type is registered", getCurrentTypes().length == 0);
        super.testToQuery();
    }

    @Test
    public void testBrokenCoordinateCannotBeSet() {
        PointTester[] testers = { new TopTester(), new LeftTester(), new BottomTester(), new RightTester() };

        GeoBoundingBoxQueryBuilder builder = createTestQueryBuilder();
        builder.setValidationMethod(GeoValidationMethod.STRICT);

        for (PointTester tester : testers) {
            try {
                tester.invalidateCoordinate(builder, true);
                fail("expected exception for broken " + tester.getClass().getName() + " coordinate");
            } catch (IllegalArgumentException e) {
                // exptected
            }
        }
    }

    @Test
    public void testBrokenCoordinateCanBeSetWithIgnoreMalformed() {
        PointTester[] testers = { new TopTester(), new LeftTester(), new BottomTester(), new RightTester() };

        GeoBoundingBoxQueryBuilder builder = createTestQueryBuilder();
        builder.setValidationMethod(GeoValidationMethod.IGNORE_MALFORMED);

        for (PointTester tester : testers) {
            tester.invalidateCoordinate(builder, true);
        }
    }


    @Test
    public void testValidation() {
        PointTester[] testers = { new TopTester(), new LeftTester(), new BottomTester(), new RightTester() };

        for (PointTester tester : testers) {
            QueryValidationException except = null;

            GeoBoundingBoxQueryBuilder builder = createTestQueryBuilder();
            tester.invalidateCoordinate(builder.setValidationMethod(GeoValidationMethod.COERCE), false);
            except = builder.checkLatLon(true);
            assertNull("Inner post 2.0 validation w/ coerce should ignore invalid "
                    + tester.getClass().getName()
                    + " coordinate: "
                    + tester.invalidCoordinate + " ",
                    except);

            tester.invalidateCoordinate(builder.setValidationMethod(GeoValidationMethod.COERCE), false);
            except = builder.checkLatLon(false);
            assertNull("Inner pre 2.0 validation w/ coerce should ignore invalid coordinate: "
                    + tester.getClass().getName()
                    + " coordinate: "
                    + tester.invalidCoordinate + " ",
                    except);

            tester.invalidateCoordinate(builder.setValidationMethod(GeoValidationMethod.STRICT), false);
            except = builder.checkLatLon(true);
            assertNull("Inner pre 2.0 validation w/o coerce should ignore invalid coordinate for old indexes: "
                    + tester.getClass().getName()
                    + " coordinate: "
                    + tester.invalidCoordinate,
                    except);

            tester.invalidateCoordinate(builder.setValidationMethod(GeoValidationMethod.STRICT), false);
            except = builder.checkLatLon(false);
            assertNotNull("Inner post 2.0 validation w/o coerce should detect invalid coordinate: "
                    + tester.getClass().getName()
                    + " coordinate: "
                    + tester.invalidCoordinate,
                    except);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testTopBottomCannotBeFlipped() {
        GeoBoundingBoxQueryBuilder builder = createTestQueryBuilder();
        double top = builder.topLeft().getLat();
        double left = builder.topLeft().getLon();
        double bottom = builder.bottomRight().getLat();
        double right = builder.bottomRight().getLon();

        assumeTrue("top should not be equal to bottom for flip check", top != bottom);
        System.out.println("top: " + top + " bottom: " + bottom);
        builder.setValidationMethod(GeoValidationMethod.STRICT).setCorners(bottom, left, top, right);
    }

    @Test
    public void testTopBottomCanBeFlippedOnIgnoreMalformed() {
        GeoBoundingBoxQueryBuilder builder = createTestQueryBuilder();
        double top = builder.topLeft().getLat();
        double left = builder.topLeft().getLon();
        double bottom = builder.bottomRight().getLat();
        double right = builder.bottomRight().getLon();

        assumeTrue("top should not be equal to bottom for flip check", top != bottom);
        builder.setValidationMethod(GeoValidationMethod.IGNORE_MALFORMED).setCorners(bottom, left, top, right);
    }

    @Test
    public void testLeftRightCanBeFlipped() {
        GeoBoundingBoxQueryBuilder builder = createTestQueryBuilder();
        double top = builder.topLeft().getLat();
        double left = builder.topLeft().getLon();
        double bottom = builder.bottomRight().getLat();
        double right = builder.bottomRight().getLon();
        
        builder.setValidationMethod(GeoValidationMethod.IGNORE_MALFORMED).setCorners(top, right, bottom, left);
        builder.setValidationMethod(GeoValidationMethod.STRICT).setCorners(top, right, bottom, left);
    }

    @Test
    public void testNormalization() throws IOException {
        assumeTrue("test runs only when at least a type is registered", getCurrentTypes().length > 0);
        GeoBoundingBoxQueryBuilder qb = createTestQueryBuilder();
        if (getCurrentTypes().length != 0 && "mapped_geo".equals(qb.fieldName())) {
            // only execute this test if we are running on a valid geo field
            qb.setCorners(200, 200, qb.bottomRight().getLat(), qb.bottomRight().getLon());
            qb.setValidationMethod(GeoValidationMethod.COERCE);
            Query query = qb.toQuery(createShardContext());
            if (query instanceof ConstantScoreQuery) {
                ConstantScoreQuery result = (ConstantScoreQuery) query;
                BooleanQuery bboxFilter = (BooleanQuery) result.getQuery();
                for (BooleanClause clause : bboxFilter.clauses()) {
                    NumericRangeQuery boundary = (NumericRangeQuery) clause.getQuery();
                    if (boundary.getMax() != null) {
                        assertTrue("If defined, non of the maximum range values should be larger than 180", boundary.getMax().intValue() <= 180);
                    }
                }
            } else {
                assertTrue("memory queries should result in InMemoryGeoBoundingBoxQuery", query instanceof InMemoryGeoBoundingBoxQuery);
            }
        }
    }
    
    @Test
    public void checkStrictnessDefault() {
        assertFalse("Someone changed the default for coordinate validation - were the docs changed as well?", GeoValidationMethod.DEFAULT_LENIENT_PARSING);
    }

    @Override
    protected void doAssertLuceneQuery(GeoBoundingBoxQueryBuilder queryBuilder, Query query, QueryShardContext context) throws IOException {
        if (queryBuilder.type() == GeoExecType.INDEXED) {
            assertTrue("Found no indexed geo query.", query instanceof ConstantScoreQuery);
        } else {
            assertTrue("Found no indexed geo query.", query instanceof InMemoryGeoBoundingBoxQuery);
        }
    }

    public abstract class PointTester {
        private double brokenCoordinate = randomFrom(brokenDoubles);
        private double invalidCoordinate;

        public PointTester(double invalidCoodinate) {
            this.invalidCoordinate = invalidCoodinate;
        }
        public void invalidateCoordinate(GeoBoundingBoxQueryBuilder qb, boolean useBrokenDouble) {
            if (useBrokenDouble) {
                fillIn(brokenCoordinate, qb);
            } else {
                fillIn(invalidCoordinate, qb);
            }
        }
        protected abstract void fillIn(double fillIn, GeoBoundingBoxQueryBuilder qb);
    }

    public class TopTester extends PointTester {
        public TopTester() {
            super(randomDoubleBetween(GeoUtils.MAX_LAT, Double.MAX_VALUE, false));
        }

        @Override
        public void fillIn(double coordinate, GeoBoundingBoxQueryBuilder qb) {
            qb.setCorners(coordinate, qb.topLeft().getLon(), qb.bottomRight().getLat(), qb.bottomRight().getLon());
        }
    }

    public class LeftTester extends PointTester {
        public LeftTester() {
            super(randomDoubleBetween(-Double.MAX_VALUE, GeoUtils.MIN_LON, true));
        }

        @Override
        public void fillIn(double coordinate, GeoBoundingBoxQueryBuilder qb) {
            qb.setCorners(qb.topLeft().getLat(), coordinate, qb.bottomRight().getLat(), qb.bottomRight().getLon());
        }
    }

    public class BottomTester extends PointTester {
        public BottomTester() {
            super(randomDoubleBetween(-Double.MAX_VALUE, GeoUtils.MIN_LAT, false));
        }

        @Override
        public void fillIn(double coordinate, GeoBoundingBoxQueryBuilder qb) {
            qb.setCorners(qb.topLeft().getLat(), qb.topLeft().getLon(), coordinate, qb.bottomRight().getLon());
        }
    }

    public class RightTester extends PointTester {
        public RightTester() {
            super(randomDoubleBetween(GeoUtils.MAX_LON, Double.MAX_VALUE, true));
        } 

        @Override
        public void fillIn(double coordinate, GeoBoundingBoxQueryBuilder qb) {
            qb.setCorners(qb.topLeft().getLat(), qb.topLeft().getLon(), qb.topLeft().getLat(), coordinate);
        }
    }

    @Test
    public void testParsingAndToQuery1() throws IOException {
        assumeTrue("test runs only when at least a type is registered", getCurrentTypes().length > 0);
        String query = "{\n" +
                "    \"geo_bounding_box\":{\n" +
                "        \"" + GEO_POINT_FIELD_NAME+ "\":{\n" +
                "            \"top_left\":[-70, 40],\n" +
                "            \"bottom_right\":[-80, 30]\n" +
                "        }\n" +
                "    }\n" +
                "}\n";
        assertGeoBoundingBoxQuery(query);
    }

    @Test
    public void testParsingAndToQuery2() throws IOException {
        assumeTrue("test runs only when at least a type is registered", getCurrentTypes().length > 0);
        String query = "{\n" +
                "    \"geo_bounding_box\":{\n" +
                "        \"" + GEO_POINT_FIELD_NAME+ "\":{\n" +
                "            \"top_left\":{\n" +
                "                \"lat\":40,\n" +
                "                \"lon\":-70\n" +
                "            },\n" +
                "            \"bottom_right\":{\n" +
                "                \"lat\":30,\n" +
                "                \"lon\":-80\n" +
                "            }\n" +
                "        }\n" +
                "    }\n" +
                "}\n";
        assertGeoBoundingBoxQuery(query);
    }

    @Test
    public void testParsingAndToQuery3() throws IOException {
        assumeTrue("test runs only when at least a type is registered", getCurrentTypes().length > 0);
        String query = "{\n" +
                "    \"geo_bounding_box\":{\n" +
                "        \"" + GEO_POINT_FIELD_NAME+ "\":{\n" +
                "            \"top_left\":\"40, -70\",\n" +
                "            \"bottom_right\":\"30, -80\"\n" +
                "        }\n" +
                "    }\n" +
                "}\n";
        assertGeoBoundingBoxQuery(query);
    }

    @Test
    public void testParsingAndToQuery4() throws IOException {
        assumeTrue("test runs only when at least a type is registered", getCurrentTypes().length > 0);
        String query = "{\n" +
                "    \"geo_bounding_box\":{\n" +
                "        \"" + GEO_POINT_FIELD_NAME+ "\":{\n" +
                "            \"top_left\":\"drn5x1g8cu2y\",\n" +
                "            \"bottom_right\":\"30, -80\"\n" +
                "        }\n" +
                "    }\n" +
                "}\n";
        assertGeoBoundingBoxQuery(query);
    }

    @Test
    public void testParsingAndToQuery5() throws IOException {
        assumeTrue("test runs only when at least a type is registered", getCurrentTypes().length > 0);
        String query = "{\n" +
                "    \"geo_bounding_box\":{\n" +
                "        \"" + GEO_POINT_FIELD_NAME+ "\":{\n" +
                "            \"top_right\":\"40, -80\",\n" +
                "            \"bottom_left\":\"30, -70\"\n" +
                "        }\n" +
                "    }\n" +
                "}\n";
        assertGeoBoundingBoxQuery(query);
    }

    @Test
    public void testParsingAndToQuery6() throws IOException {
        assumeTrue("test runs only when at least a type is registered", getCurrentTypes().length > 0);
        String query = "{\n" +
                "    \"geo_bounding_box\":{\n" +
                "        \"" + GEO_POINT_FIELD_NAME+ "\":{\n" +
                "            \"right\": -80,\n" +
                "            \"top\": 40,\n" +
                "            \"left\": -70,\n" +
                "            \"bottom\": 30\n" +
                "        }\n" +
                "    }\n" +
                "}\n";
        assertGeoBoundingBoxQuery(query);
    }
    
    private void assertGeoBoundingBoxQuery(String query) throws IOException {
        Query parsedQuery = parseQuery(query).toQuery(createShardContext());
        InMemoryGeoBoundingBoxQuery filter = (InMemoryGeoBoundingBoxQuery) parsedQuery;
        assertThat(filter.fieldName(), equalTo(GEO_POINT_FIELD_NAME));
        assertThat(filter.topLeft().lat(), closeTo(40, 0.00001));
        assertThat(filter.topLeft().lon(), closeTo(-70, 0.00001));
        assertThat(filter.bottomRight().lat(), closeTo(30, 0.00001));
        assertThat(filter.bottomRight().lon(), closeTo(-80, 0.00001));
    }
}
