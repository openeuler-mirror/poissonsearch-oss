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

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.FieldComparator;
import org.apache.lucene.search.SortField;
import org.apache.lucene.util.BitSet;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.Version;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.geo.GeoDistance;
import org.elasticsearch.common.geo.GeoDistance.FixedSourceDistance;
import org.elasticsearch.common.geo.GeoPoint;
import org.elasticsearch.common.geo.GeoUtils;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.unit.DistanceUnit;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.index.fielddata.IndexFieldData.XFieldComparatorSource.Nested;
import org.elasticsearch.index.fielddata.IndexGeoPointFieldData;
import org.elasticsearch.index.fielddata.MultiGeoPointValues;
import org.elasticsearch.index.fielddata.NumericDoubleValues;
import org.elasticsearch.index.fielddata.SortedNumericDoubleValues;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryParseContext;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.search.MultiValueMode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A geo distance based sorting on a geo point like field.
 */
public class GeoDistanceSortBuilder extends SortBuilder<GeoDistanceSortBuilder> implements SortBuilderParser<GeoDistanceSortBuilder> {
    public static final String NAME = "_geo_distance";
    public static final boolean DEFAULT_COERCE = false;
    public static final boolean DEFAULT_IGNORE_MALFORMED = false;
    public static final ParseField UNIT_FIELD = new ParseField("unit");
    public static final ParseField REVERSE_FIELD = new ParseField("reverse");
    public static final ParseField DISTANCE_TYPE_FIELD = new ParseField("distance_type");
    public static final ParseField COERCE_FIELD = new ParseField("coerce", "normalize");
    public static final ParseField IGNORE_MALFORMED_FIELD = new ParseField("ignore_malformed");
    public static final ParseField SORTMODE_FIELD = new ParseField("mode", "sort_mode");
    public static final ParseField NESTED_PATH_FIELD = new ParseField("nested_path");
    public static final ParseField NESTED_FILTER_FIELD = new ParseField("nested_filter");

    static final GeoDistanceSortBuilder PROTOTYPE = new GeoDistanceSortBuilder("", -1, -1);

    private final String fieldName;
    private final List<GeoPoint> points = new ArrayList<>();

    private GeoDistance geoDistance = GeoDistance.DEFAULT;
    private DistanceUnit unit = DistanceUnit.DEFAULT;

    private SortMode sortMode = null;
    @SuppressWarnings("rawtypes")
    private QueryBuilder nestedFilter;
    private String nestedPath;

    // TODO switch to GeoValidationMethod enum
    private boolean coerce = DEFAULT_COERCE;
    private boolean ignoreMalformed = DEFAULT_IGNORE_MALFORMED;

    /**
     * Constructs a new distance based sort on a geo point like field.
     *
     * @param fieldName The geo point like field name.
     * @param points The points to create the range distance facets from.
     */
    public GeoDistanceSortBuilder(String fieldName, GeoPoint... points) {
        this.fieldName = fieldName;
        if (points.length == 0) {
            throw new IllegalArgumentException("Geo distance sorting needs at least one point.");
        }
        this.points.addAll(Arrays.asList(points));
    }

    /**
     * Constructs a new distance based sort on a geo point like field.
     *
     * @param fieldName The geo point like field name.
     * @param lat Latitude of the point to create the range distance facets from.
     * @param lon Longitude of the point to create the range distance facets from.
     */
    public GeoDistanceSortBuilder(String fieldName, double lat, double lon) {
        this(fieldName, new GeoPoint(lat, lon));
    }

    /**
     * Constructs a new distance based sort on a geo point like field.
     *
     * @param fieldName The geo point like field name.
     * @param geohashes The points to create the range distance facets from.
     */
    public GeoDistanceSortBuilder(String fieldName, String ... geohashes) {
        if (geohashes.length == 0) {
            throw new IllegalArgumentException("Geo distance sorting needs at least one point.");
        }
        for (String geohash : geohashes) {
            this.points.add(GeoPoint.fromGeohash(geohash));
        }
        this.fieldName = fieldName;
    }

    /**
     * Copy constructor.
     * */
    GeoDistanceSortBuilder(GeoDistanceSortBuilder original) {
        this.fieldName = original.fieldName();
        this.points.addAll(original.points);
        this.geoDistance = original.geoDistance;
        this.unit = original.unit;
        this.order = original.order;
        this.sortMode = original.sortMode;
        this.nestedFilter = original.nestedFilter;
        this.nestedPath = original.nestedPath;
        this.coerce = original.coerce;
        this.ignoreMalformed = original.ignoreMalformed;
    }

    /**
     * Returns the geo point like field the distance based sort operates on.
     * */
    public String fieldName() {
        return this.fieldName;
    }

    /**
     * The point to create the range distance facets from.
     *
     * @param lat latitude.
     * @param lon longitude.
     */
    public GeoDistanceSortBuilder point(double lat, double lon) {
        points.add(new GeoPoint(lat, lon));
        return this;
    }

    /**
     * The point to create the range distance facets from.
     *
     * @param points reference points.
     */
    public GeoDistanceSortBuilder points(GeoPoint... points) {
        this.points.addAll(Arrays.asList(points));
        return this;
    }

    /**
     * Returns the points to create the range distance facets from.
     */
    public GeoPoint[] points() {
        return this.points.toArray(new GeoPoint[this.points.size()]);
    }

    /**
     * The geohash of the geo point to create the range distance facets from.
     *
     * Deprecated - please use points(GeoPoint... points) instead.
     */
    @Deprecated
    public GeoDistanceSortBuilder geohashes(String... geohashes) {
        for (String geohash : geohashes) {
            this.points.add(GeoPoint.fromGeohash(geohash));
        }
        return this;
    }

    /**
     * The geo distance type used to compute the distance.
     */
    public GeoDistanceSortBuilder geoDistance(GeoDistance geoDistance) {
        this.geoDistance = geoDistance;
        return this;
    }

    /**
     * Returns the geo distance type used to compute the distance.
     */
    public GeoDistance geoDistance() {
        return this.geoDistance;
    }

    /**
     * The distance unit to use. Defaults to {@link org.elasticsearch.common.unit.DistanceUnit#KILOMETERS}
     */
    public GeoDistanceSortBuilder unit(DistanceUnit unit) {
        this.unit = unit;
        return this;
    }

    /**
     * Returns the distance unit to use. Defaults to {@link org.elasticsearch.common.unit.DistanceUnit#KILOMETERS}
     */
    public DistanceUnit unit() {
        return this.unit;
    }

    /**
     * Defines which distance to use for sorting in the case a document contains multiple geo points.
     * Possible values: min and max
     */
    public GeoDistanceSortBuilder sortMode(SortMode sortMode) {
        Objects.requireNonNull(sortMode, "sort mode cannot be null");
        if (sortMode == SortMode.SUM) {
            throw new IllegalArgumentException("sort_mode [sum] isn't supported for sorting by geo distance");
        }
        this.sortMode = sortMode;
        return this;
    }

    /** Returns which distance to use for sorting in the case a document contains multiple geo points. */
    public SortMode sortMode() {
        return this.sortMode;
    }

    /**
     * Sets the nested filter that the nested objects should match with in order to be taken into account
     * for sorting.
     */
    public GeoDistanceSortBuilder setNestedFilter(QueryBuilder<?> nestedFilter) {
        this.nestedFilter = nestedFilter;
        return this;
    }

    /**
     * Returns the nested filter that the nested objects should match with in order to be taken into account
     * for sorting.
     **/
    public QueryBuilder<?> getNestedFilter() {
        return this.nestedFilter;
    }

    /**
     * Sets the nested path if sorting occurs on a field that is inside a nested object. By default when sorting on a
     * field inside a nested object, the nearest upper nested object is selected as nested path.
     */
    public GeoDistanceSortBuilder setNestedPath(String nestedPath) {
        this.nestedPath = nestedPath;
        return this;
    }

    /**
     * Returns the nested path if sorting occurs on a field that is inside a nested object. By default when sorting on a
     * field inside a nested object, the nearest upper nested object is selected as nested path.
     */
    public String getNestedPath() {
        return this.nestedPath;
    }

    public GeoDistanceSortBuilder coerce(boolean coerce) {
        this.coerce = coerce;
        return this;
    }

    public boolean coerce() {
        return this.coerce;
    }

    public GeoDistanceSortBuilder ignoreMalformed(boolean ignoreMalformed) {
        if (coerce == false) {
            this.ignoreMalformed = ignoreMalformed;
        }
        return this;
    }

    public boolean ignoreMalformed() {
        return this.ignoreMalformed;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(NAME);

        builder.startArray(fieldName);
        for (GeoPoint point : points) {
            builder.value(point);
        }
        builder.endArray();

        builder.field(UNIT_FIELD.getPreferredName(), unit);
        builder.field(DISTANCE_TYPE_FIELD.getPreferredName(), geoDistance.name().toLowerCase(Locale.ROOT));
        builder.field(ORDER_FIELD.getPreferredName(), order);

        if (sortMode != null) {
            builder.field(SORTMODE_FIELD.getPreferredName(), sortMode);
        }

        if (nestedPath != null) {
            builder.field(NESTED_PATH_FIELD.getPreferredName(), nestedPath);
        }
        if (nestedFilter != null) {
            builder.field(NESTED_FILTER_FIELD.getPreferredName(), nestedFilter, params);
        }
        builder.field(COERCE_FIELD.getPreferredName(), coerce);
        builder.field(IGNORE_MALFORMED_FIELD.getPreferredName(), ignoreMalformed);

        builder.endObject();
        return builder;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }

        if (object == null || getClass() != object.getClass()) {
            return false;
        }

        GeoDistanceSortBuilder other = (GeoDistanceSortBuilder) object;
        return Objects.equals(fieldName, other.fieldName) &&
                Objects.deepEquals(points, other.points) &&
                Objects.equals(geoDistance, other.geoDistance) &&
                Objects.equals(unit, other.unit) &&
                Objects.equals(sortMode, other.sortMode) &&
                Objects.equals(order, other.order) &&
                Objects.equals(nestedFilter, other.nestedFilter) &&
                Objects.equals(nestedPath, other.nestedPath) &&
                Objects.equals(coerce, other.coerce) &&
                Objects.equals(ignoreMalformed, other.ignoreMalformed);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.fieldName, this.points, this.geoDistance,
                this.unit, this.sortMode, this.order, this.nestedFilter, this.nestedPath, this.coerce, this.ignoreMalformed);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(fieldName);
        out.writeGenericValue(points);

        geoDistance.writeTo(out);
        unit.writeTo(out);
        order.writeTo(out);
        out.writeBoolean(this.sortMode != null);
        if (this.sortMode != null) {
            sortMode.writeTo(out);
        }
        if (nestedFilter != null) {
            out.writeBoolean(true);
            out.writeQuery(nestedFilter);
        } else {
            out.writeBoolean(false);
        }
        out.writeOptionalString(nestedPath);
        out.writeBoolean(coerce);
        out.writeBoolean(ignoreMalformed);
    }

    @Override
    public GeoDistanceSortBuilder readFrom(StreamInput in) throws IOException {
        String fieldName = in.readString();

        ArrayList<GeoPoint> points = (ArrayList<GeoPoint>) in.readGenericValue();
        GeoDistanceSortBuilder result = new GeoDistanceSortBuilder(fieldName, points.toArray(new GeoPoint[points.size()]));

        result.geoDistance(GeoDistance.readGeoDistanceFrom(in));
        result.unit(DistanceUnit.readDistanceUnit(in));
        result.order(SortOrder.readOrderFrom(in));
        if (in.readBoolean()) {
            result.sortMode = SortMode.PROTOTYPE.readFrom(in);
        }
        if (in.readBoolean()) {
            result.setNestedFilter(in.readQuery());
        }
        result.setNestedPath(in.readOptionalString());
        result.coerce(in.readBoolean());
        result.ignoreMalformed(in.readBoolean());
        return result;
    }

    @Override
    public GeoDistanceSortBuilder fromXContent(QueryParseContext context, String elementName) throws IOException {
        XContentParser parser = context.parser();
        ParseFieldMatcher parseFieldMatcher = context.parseFieldMatcher();
        String fieldName = null;
        List<GeoPoint> geoPoints = new ArrayList<>();
        DistanceUnit unit = DistanceUnit.DEFAULT;
        GeoDistance geoDistance = GeoDistance.DEFAULT;
        SortOrder order = SortOrder.ASC;
        SortMode sortMode = null;
        QueryBuilder<?> nestedFilter = null;
        String nestedPath = null;

        boolean coerce = GeoDistanceSortBuilder.DEFAULT_COERCE;
        boolean ignoreMalformed = GeoDistanceSortBuilder.DEFAULT_IGNORE_MALFORMED;

        XContentParser.Token token;
        String currentName = parser.currentName();
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentName = parser.currentName();
            } else if (token == XContentParser.Token.START_ARRAY) {
                parseGeoPoints(parser, geoPoints);

                fieldName = currentName;
            } else if (token == XContentParser.Token.START_OBJECT) {
                if (parseFieldMatcher.match(currentName, NESTED_FILTER_FIELD)) {
                    nestedFilter = context.parseInnerQueryBuilder();
                } else {
                    // the json in the format of -> field : { lat : 30, lon : 12 }
                    fieldName = currentName;
                    GeoPoint point = new GeoPoint();
                    GeoUtils.parseGeoPoint(parser, point);
                    geoPoints.add(point);
                }
            } else if (token.isValue()) {
                if (parseFieldMatcher.match(currentName, REVERSE_FIELD)) {
                    order = parser.booleanValue() ? SortOrder.DESC : SortOrder.ASC;
                } else if (parseFieldMatcher.match(currentName, ORDER_FIELD)) {
                    order = SortOrder.fromString(parser.text());
                } else if (parseFieldMatcher.match(currentName, UNIT_FIELD)) {
                    unit = DistanceUnit.fromString(parser.text());
                } else if (parseFieldMatcher.match(currentName, DISTANCE_TYPE_FIELD)) {
                    geoDistance = GeoDistance.fromString(parser.text());
                } else if (parseFieldMatcher.match(currentName, COERCE_FIELD)) {
                    coerce = parser.booleanValue();
                    if (coerce == true) {
                        ignoreMalformed = true;
                    }
                } else if (parseFieldMatcher.match(currentName, IGNORE_MALFORMED_FIELD)) {
                    boolean ignore_malformed_value = parser.booleanValue();
                    if (coerce == false) {
                        ignoreMalformed = ignore_malformed_value;
                    }
                } else if (parseFieldMatcher.match(currentName, SORTMODE_FIELD)) {
                    sortMode = SortMode.fromString(parser.text());
                } else if (parseFieldMatcher.match(currentName, NESTED_PATH_FIELD)) {
                    nestedPath = parser.text();
                } else {
                    GeoPoint point = new GeoPoint();
                    point.resetFromString(parser.text());
                    geoPoints.add(point);
                    fieldName = currentName;
                }
            }
        }

        GeoDistanceSortBuilder result = new GeoDistanceSortBuilder(fieldName, geoPoints.toArray(new GeoPoint[geoPoints.size()]));
        result.geoDistance(geoDistance);
        result.unit(unit);
        result.order(order);
        if (sortMode != null) {
            result.sortMode(sortMode);
        }
        result.setNestedFilter(nestedFilter);
        result.setNestedPath(nestedPath);
        result.coerce(coerce);
        result.ignoreMalformed(ignoreMalformed);
        return result;
    }

    @Override
    public SortField build(QueryShardContext context) throws IOException {
        final boolean indexCreatedBeforeV2_0 = context.indexVersionCreated().before(Version.V_2_0_0);
        // validation was not available prior to 2.x, so to support bwc percolation queries we only ignore_malformed on 2.x created indexes
        List<GeoPoint> localPoints = new ArrayList<GeoPoint>();
        for (GeoPoint geoPoint : this.points) {
            localPoints.add(new GeoPoint(geoPoint));
        }

        if (!indexCreatedBeforeV2_0 && !ignoreMalformed) {
            for (GeoPoint point : localPoints) {
                if (GeoUtils.isValidLatitude(point.lat()) == false) {
                    throw new ElasticsearchParseException("illegal latitude value [{}] for [GeoDistanceSort]", point.lat());
                }
                if (GeoUtils.isValidLongitude(point.lon()) == false) {
                    throw new ElasticsearchParseException("illegal longitude value [{}] for [GeoDistanceSort]", point.lon());
                }
            }
        }

        if (coerce) {
            for (GeoPoint point : localPoints) {
                GeoUtils.normalizePoint(point, coerce, coerce);
            }
        }

        boolean reverse = (order == SortOrder.DESC);
        final MultiValueMode finalSortMode;
        if (sortMode == null) {
            finalSortMode = reverse ? MultiValueMode.MAX : MultiValueMode.MIN;
        } else {
            finalSortMode = MultiValueMode.fromString(sortMode.toString());
        }

        MappedFieldType fieldType = context.fieldMapper(fieldName);
        if (fieldType == null) {
            throw new IllegalArgumentException("failed to find mapper for [" + fieldName + "] for geo distance based sort");
        }
        final IndexGeoPointFieldData geoIndexFieldData = context.getForField(fieldType);
        final FixedSourceDistance[] distances = new FixedSourceDistance[localPoints.size()];
        for (int i = 0; i< localPoints.size(); i++) {
            distances[i] = geoDistance.fixedSourceDistance(localPoints.get(i).lat(), localPoints.get(i).lon(), unit);
        }

        final Nested nested = resolveNested(context, nestedPath, nestedFilter);

        IndexFieldData.XFieldComparatorSource geoDistanceComparatorSource = new IndexFieldData.XFieldComparatorSource() {

            @Override
            public SortField.Type reducedType() {
                return SortField.Type.DOUBLE;
            }

            @Override
            public FieldComparator<?> newComparator(String fieldname, int numHits, int sortPos, boolean reversed) throws IOException {
                return new FieldComparator.DoubleComparator(numHits, null, null) {
                    @Override
                    protected NumericDocValues getNumericDocValues(LeafReaderContext context, String field) throws IOException {
                        final MultiGeoPointValues geoPointValues = geoIndexFieldData.load(context).getGeoPointValues();
                        final SortedNumericDoubleValues distanceValues = GeoDistance.distanceValues(geoPointValues, distances);
                        final NumericDoubleValues selectedValues;
                        if (nested == null) {
                            selectedValues = finalSortMode.select(distanceValues, Double.MAX_VALUE);
                        } else {
                            final BitSet rootDocs = nested.rootDocs(context);
                            final DocIdSetIterator innerDocs = nested.innerDocs(context);
                            selectedValues = finalSortMode.select(distanceValues, Double.MAX_VALUE, rootDocs, innerDocs,
                                    context.reader().maxDoc());
                        }
                        return selectedValues.getRawDoubleValues();
                    }
                };
            }

        };

        return new SortField(fieldName, geoDistanceComparatorSource, reverse);
    }

    static void parseGeoPoints(XContentParser parser, List<GeoPoint> geoPoints) throws IOException {
        while (!parser.nextToken().equals(XContentParser.Token.END_ARRAY)) {
            if (parser.currentToken() == XContentParser.Token.VALUE_NUMBER) {
                // we might get here if the geo point is " number, number] " and the parser already moved over the opening bracket
                // in this case we cannot use GeoUtils.parseGeoPoint(..) because this expects an opening bracket
                double lon = parser.doubleValue();
                parser.nextToken();
                if (!parser.currentToken().equals(XContentParser.Token.VALUE_NUMBER)) {
                    throw new ElasticsearchParseException(
                            "geo point parsing: expected second number but got [{}] instead",
                            parser.currentToken());
                }
                double lat = parser.doubleValue();
                GeoPoint point = new GeoPoint();
                point.reset(lat, lon);
                geoPoints.add(point);
            } else {
                GeoPoint point = new GeoPoint();
                GeoUtils.parseGeoPoint(parser, point);
                geoPoints.add(point);
            }

        }
    }
}
