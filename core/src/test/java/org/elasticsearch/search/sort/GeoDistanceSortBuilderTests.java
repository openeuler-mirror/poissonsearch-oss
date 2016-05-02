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

package org.elasticsearch.search.sort;


import org.apache.lucene.search.SortField;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.geo.GeoDistance;
import org.elasticsearch.common.geo.GeoPoint;
import org.elasticsearch.common.unit.DistanceUnit;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.geo.GeoPointFieldMapper;
import org.elasticsearch.index.query.QueryParseContext;
import org.elasticsearch.indices.query.IndicesQueriesRegistry;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.geo.RandomGeoGenerator;

import java.io.IOException;
import java.util.Arrays;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;

public class GeoDistanceSortBuilderTests extends AbstractSortTestCase<GeoDistanceSortBuilder> {

    @Override
    protected GeoDistanceSortBuilder createTestItem() {
        return randomGeoDistanceSortBuilder();
    }

    public static GeoDistanceSortBuilder randomGeoDistanceSortBuilder() {
        String fieldName = randomAsciiOfLengthBetween(1, 10);
        GeoDistanceSortBuilder result = null;

        int id = randomIntBetween(0, 2);
        switch(id) {
            case 0:
                int count = randomIntBetween(1, 10);
                String[] geohashes = new String[count];
                for (int i = 0; i < count; i++) {
                    geohashes[i] = RandomGeoGenerator.randomPoint(random()).geohash();
                }

                result = new GeoDistanceSortBuilder(fieldName, geohashes);
                break;
            case 1:
                GeoPoint pt = RandomGeoGenerator.randomPoint(random());
                result = new GeoDistanceSortBuilder(fieldName, pt.getLat(), pt.getLon());
                break;
            case 2:
                result = new GeoDistanceSortBuilder(fieldName, points(new GeoPoint[0]));
                break;
            default:
                throw new IllegalStateException("one of three geo initialisation strategies must be used");

        }
        if (randomBoolean()) {
            result.geoDistance(geoDistance(result.geoDistance()));
        }
        if (randomBoolean()) {
            result.unit(unit(result.unit()));
        }
        if (randomBoolean()) {
            result.order(randomFrom(SortOrder.values()));
        }
        if (randomBoolean()) {
            result.sortMode(mode(result.sortMode()));
        }
        if (randomBoolean()) {
            result.setNestedFilter(NestedQueryBuilderGenerator.randomNestedFilter());
        }
        if (randomBoolean()) {
            result.setNestedPath(
                    ESTestCase.randomValueOtherThan(
                            result.getNestedPath(),
                            () -> ESTestCase.randomAsciiOfLengthBetween(1, 10)));
        }
        if (randomBoolean()) {
            result.coerce(! result.coerce());
        }
        if (randomBoolean()) {
            result.ignoreMalformed(! result.ignoreMalformed());
        }

        return result;
    }

    @Override
    protected MappedFieldType provideMappedFieldType(String name) {
        MappedFieldType clone = GeoPointFieldMapper.Defaults.FIELD_TYPE.clone();
        clone.setName(name);
        return clone;
    }

    private static SortMode mode(SortMode original) {
        SortMode result;
        do {
            result = randomFrom(SortMode.values());
        } while (result == SortMode.SUM || result == original);
        return result;
    }

    private static DistanceUnit unit(DistanceUnit original) {
        int id = -1;
        while (id == -1 || (original != null && original.ordinal() == id)) {
            id = randomIntBetween(0, DistanceUnit.values().length - 1);
        }
        return DistanceUnit.values()[id];
    }

    private static GeoPoint[] points(GeoPoint[] original) {
        GeoPoint[] result = null;
        while (result == null || Arrays.deepEquals(original, result)) {
            int count = randomIntBetween(1, 10);
            result = new GeoPoint[count];
            for (int i = 0; i < count; i++) {
                result[i] = RandomGeoGenerator.randomPoint(random());
            }
        }
        return result;
    }

    private static GeoDistance geoDistance(GeoDistance original) {
        int id = -1;
        while (id == -1 || (original != null && original.ordinal() == id)) {
            id = randomIntBetween(0, GeoDistance.values().length - 1);
        }
        return GeoDistance.values()[id];
    }

    @Override
    protected GeoDistanceSortBuilder mutate(GeoDistanceSortBuilder original) throws IOException {
        GeoDistanceSortBuilder result = new GeoDistanceSortBuilder(original);
        int parameter = randomIntBetween(0, 9);
        switch (parameter) {
        case 0:
            while (Arrays.deepEquals(original.points(), result.points())) {
                GeoPoint pt = RandomGeoGenerator.randomPoint(random());
                result.point(pt.getLat(), pt.getLon());
            }
            break;
        case 1:
            result.points(points(original.points()));
            break;
        case 2:
            result.geoDistance(geoDistance(original.geoDistance()));
            break;
        case 3:
            result.unit(unit(original.unit()));
            break;
        case 4:
            result.order(ESTestCase.randomValueOtherThan(original.order(), () -> randomFrom(SortOrder.values())));
            break;
        case 5:
            result.sortMode(mode(original.sortMode()));
            break;
        case 6:
            result.setNestedFilter(ESTestCase.randomValueOtherThan(
                    original.getNestedFilter(),
                    () -> NestedQueryBuilderGenerator.randomNestedFilter()));
            break;
        case 7:
            result.setNestedPath(ESTestCase.randomValueOtherThan(
                    result.getNestedPath(),
                    () -> ESTestCase.randomAsciiOfLengthBetween(1, 10)));
            break;
        case 8:
            result.coerce(! original.coerce());
            break;
        case 9:
            // ignore malformed will only be set if coerce is set to true
            result.coerce(false);
            result.ignoreMalformed(! original.ignoreMalformed());
            break;
        }
        return result;
    }

    @Override
    protected void sortFieldAssertions(GeoDistanceSortBuilder builder, SortField sortField) throws IOException {
        assertEquals(SortField.Type.CUSTOM, sortField.getType());
        assertEquals(builder.order() == SortOrder.ASC ? false : true, sortField.getReverse());
        assertEquals(builder.fieldName(), sortField.getField());
    }

    public void testSortModeSumIsRejectedInSetter() {
        GeoDistanceSortBuilder builder = new GeoDistanceSortBuilder("testname", -1, -1);
        GeoPoint point = RandomGeoGenerator.randomPoint(random());
        builder.point(point.getLat(), point.getLon());
        try {
            builder.sortMode(SortMode.SUM);
            fail("sort mode sum should not be supported");
          } catch (IllegalArgumentException e) {
              // all good
          }
    }

    public void testReverseOptionFailsWhenNonStringField() throws IOException {
        String json = "{\n" +
                "  \"testname\" : [ {\n" +
                "    \"lat\" : -6.046997540714173,\n" +
                "    \"lon\" : -51.94128329747579\n" +
                "  } ],\n" +
                "  \"reverse\" : true\n" +
                "}";
        XContentParser itemParser = XContentHelper.createParser(new BytesArray(json));
        itemParser.nextToken();

        QueryParseContext context = new QueryParseContext(indicesQueriesRegistry, itemParser, ParseFieldMatcher.STRICT);

        try {
          GeoDistanceSortBuilder.fromXContent(context, "");
          fail("adding reverse sorting option should fail with an exception");
        } catch (ParsingException e) {
            assertEquals("Only geohashes of type string supported for field [reverse]", e.getMessage());
        }
    }

    public void testReverseOptionFailsWhenStringFieldButResetting() throws IOException {
        String json = "{\n" +
                "  \"testname\" : [ {\n" +
                "    \"lat\" : -6.046997540714173,\n" +
                "    \"lon\" : -51.94128329747579\n" +
                "  } ],\n" +
                "  \"reverse\" : \"true\"\n" +
                "}";
        XContentParser itemParser = XContentHelper.createParser(new BytesArray(json));
        itemParser.nextToken();

        QueryParseContext context = new QueryParseContext(indicesQueriesRegistry, itemParser, ParseFieldMatcher.STRICT);

        try {
          GeoDistanceSortBuilder.fromXContent(context, "");
          fail("adding reverse sorting option should fail with an exception");
        } catch (ParsingException e) {
            assertEquals("Trying to reset fieldName to [reverse], already set to [testname].", e.getMessage());
        }
    }

    public void testReverseOptionFailsBuildWhenInvalidGeoHashString() throws IOException {
        String json = "{\n" +
                "  \"reverse\" : \"false\"\n" +
                "}";
        XContentParser itemParser = XContentHelper.createParser(new BytesArray(json));
        itemParser.nextToken();

        QueryParseContext context = new QueryParseContext(indicesQueriesRegistry, itemParser, ParseFieldMatcher.STRICT);

        try {
          GeoDistanceSortBuilder item = GeoDistanceSortBuilder.fromXContent(context, "");
          item.ignoreMalformed(false);
          item.build(createMockShardContext());

          fail("adding reverse sorting option should fail with an exception");
        } catch (ElasticsearchParseException e) {
            assertEquals("illegal latitude value [269.384765625] for [GeoDistanceSort] for field [reverse].", e.getMessage());
        }
    }

    public void testSortModeSumIsRejectedInJSON() throws IOException {
        String json = "{\n" +
                "  \"testname\" : [ {\n" +
                "    \"lat\" : -6.046997540714173,\n" +
                "    \"lon\" : -51.94128329747579\n" +
                "  } ],\n" +
                "  \"unit\" : \"m\",\n" +
                "  \"distance_type\" : \"sloppy_arc\",\n" +
                "  \"mode\" : \"SUM\",\n" +
                "  \"coerce\" : false,\n" +
                "  \"ignore_malformed\" : false\n" +
                "}";
        XContentParser itemParser = XContentHelper.createParser(new BytesArray(json));
        itemParser.nextToken();

        QueryParseContext context = new QueryParseContext(indicesQueriesRegistry, itemParser, ParseFieldMatcher.STRICT);

        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> GeoDistanceSortBuilder.fromXContent(context, ""));
        assertEquals("sort_mode [sum] isn't supported for sorting by geo distance", e.getMessage());
    }

    public void testGeoDistanceSortCanBeParsedFromGeoHash() throws IOException {
        String json = "{\n" +
                "    \"VDcvDuFjE\" : [ \"7umzzv8eychg\", \"dmdgmt5z13uw\", " +
                "    \"ezu09wxw6v4c\", \"kc7s3515p6k6\", \"jgeuvjwrmfzn\", \"kcpcfj7ruyf8\" ],\n" +
                "    \"unit\" : \"m\",\n" +
                "    \"distance_type\" : \"sloppy_arc\",\n" +
                "    \"mode\" : \"MAX\",\n" +
                "    \"nested_filter\" : {\n" +
                "      \"ids\" : {\n" +
                "        \"type\" : [ ],\n" +
                "        \"values\" : [ ],\n" +
                "        \"boost\" : 5.711116\n" +
                "      }\n" +
                "    },\n" +
                "    \"coerce\" : false,\n" +
                "    \"ignore_malformed\" : true\n" +
                "  }";
        XContentParser itemParser = XContentHelper.createParser(new BytesArray(json));
        itemParser.nextToken();

        QueryParseContext context = new QueryParseContext(indicesQueriesRegistry, itemParser, ParseFieldMatcher.STRICT);

        GeoDistanceSortBuilder result = GeoDistanceSortBuilder.fromXContent(context, json);
        assertEquals("[-19.700583312660456, -2.8225036337971687, "
                + "31.537466906011105, -74.63590376079082, "
                + "43.71844606474042, -5.548660643398762, "
                + "-37.20467280596495, 38.71751043945551, "
                + "-69.44606635719538, 84.25200328230858, "
                + "-39.03717711567879, 44.74099852144718]", Arrays.toString(result.points()));
    }

    public void testGeoDistanceSortParserManyPointsNoException() throws Exception {
        XContentBuilder sortBuilder = jsonBuilder();
        sortBuilder.startObject();
        sortBuilder.startArray("location");
        sortBuilder.startArray().value(1.2).value(3).endArray().startArray().value(5).value(6).endArray();
        sortBuilder.endArray();
        sortBuilder.field("order", "desc");
        sortBuilder.field("unit", "km");
        sortBuilder.field("mode", "max");
        sortBuilder.endObject();
        parse(sortBuilder);

        sortBuilder = jsonBuilder();
        sortBuilder.startObject();
        sortBuilder.startArray("location");
        sortBuilder.value(new GeoPoint(1.2, 3)).value(new GeoPoint(1.2, 3));
        sortBuilder.endArray();
        sortBuilder.field("order", "desc");
        sortBuilder.field("unit", "km");
        sortBuilder.field("mode", "max");
        sortBuilder.endObject();
        parse(sortBuilder);

        sortBuilder = jsonBuilder();
        sortBuilder.startObject();
        sortBuilder.startArray("location");
        sortBuilder.value("1,2").value("3,4");
        sortBuilder.endArray();
        sortBuilder.field("order", "desc");
        sortBuilder.field("unit", "km");
        sortBuilder.field("mode", "max");
        sortBuilder.endObject();
        parse(sortBuilder);

        sortBuilder = jsonBuilder();
        sortBuilder.startObject();
        sortBuilder.startArray("location");
        sortBuilder.value("s3y0zh7w1z0g").value("s6wjr4et3f8v");
        sortBuilder.endArray();
        sortBuilder.field("order", "desc");
        sortBuilder.field("unit", "km");
        sortBuilder.field("mode", "max");
        sortBuilder.endObject();
        parse(sortBuilder);

        sortBuilder = jsonBuilder();
        sortBuilder.startObject();
        sortBuilder.startArray("location");
        sortBuilder.value(1.2).value(3);
        sortBuilder.endArray();
        sortBuilder.field("order", "desc");
        sortBuilder.field("unit", "km");
        sortBuilder.field("mode", "max");
        sortBuilder.endObject();
        parse(sortBuilder);

        sortBuilder = jsonBuilder();
        sortBuilder.startObject();
        sortBuilder.field("location", new GeoPoint(1, 2));
        sortBuilder.field("order", "desc");
        sortBuilder.field("unit", "km");
        sortBuilder.field("mode", "max");
        sortBuilder.endObject();
        parse(sortBuilder);

        sortBuilder = jsonBuilder();
        sortBuilder.startObject();
        sortBuilder.field("location", "1,2");
        sortBuilder.field("order", "desc");
        sortBuilder.field("unit", "km");
        sortBuilder.field("mode", "max");
        sortBuilder.endObject();
        parse(sortBuilder);

        sortBuilder = jsonBuilder();
        sortBuilder.startObject();
        sortBuilder.field("location", "s3y0zh7w1z0g");
        sortBuilder.field("order", "desc");
        sortBuilder.field("unit", "km");
        sortBuilder.field("mode", "max");
        sortBuilder.endObject();
        parse(sortBuilder);

        sortBuilder = jsonBuilder();
        sortBuilder.startObject();
        sortBuilder.startArray("location");
        sortBuilder.value(new GeoPoint(1, 2)).value("s3y0zh7w1z0g").startArray().value(1).value(2).endArray().value("1,2");
        sortBuilder.endArray();
        sortBuilder.field("order", "desc");
        sortBuilder.field("unit", "km");
        sortBuilder.field("mode", "max");
        sortBuilder.endObject();
        parse(sortBuilder);
    }

    public void testGeoDistanceSortDeprecatedSortModeException() throws Exception {
        XContentBuilder sortBuilder = jsonBuilder();
        sortBuilder.startObject();
        sortBuilder.startArray("location");
        sortBuilder.startArray().value(1.2).value(3).endArray().startArray().value(5).value(6).endArray();
        sortBuilder.endArray();
        sortBuilder.field("order", "desc");
        sortBuilder.field("unit", "km");
        sortBuilder.field("sort_mode", "max");
        sortBuilder.endObject();
        IllegalArgumentException ex = expectThrows(IllegalArgumentException.class, () -> parse(sortBuilder));
        assertEquals("Deprecated field [sort_mode] used, expected [mode] instead", ex.getMessage());
    }

    private static GeoDistanceSortBuilder parse(XContentBuilder sortBuilder) throws Exception {
        XContentParser parser = XContentHelper.createParser(sortBuilder.bytes());
        QueryParseContext parseContext = new QueryParseContext(new IndicesQueriesRegistry(), parser, ParseFieldMatcher.STRICT);
        parser.nextToken();
        return GeoDistanceSortBuilder.fromXContent(parseContext, null);
    }

    @Override
    protected GeoDistanceSortBuilder fromXContent(QueryParseContext context, String fieldName) throws IOException {
        return GeoDistanceSortBuilder.fromXContent(context, fieldName);
    }
}
