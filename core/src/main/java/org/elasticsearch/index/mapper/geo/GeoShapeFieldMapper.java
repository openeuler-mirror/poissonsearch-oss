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
package org.elasticsearch.index.mapper.geo;

import com.spatial4j.core.shape.Shape;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.spatial.prefix.PrefixTreeStrategy;
import org.apache.lucene.spatial.prefix.RecursivePrefixTreeStrategy;
import org.apache.lucene.spatial.prefix.TermQueryPrefixTreeStrategy;
import org.apache.lucene.spatial.prefix.tree.GeohashPrefixTree;
import org.apache.lucene.spatial.prefix.tree.PackedQuadPrefixTree;
import org.apache.lucene.spatial.prefix.tree.QuadPrefixTree;
import org.apache.lucene.spatial.prefix.tree.SpatialPrefixTree;
import org.elasticsearch.Version;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.geo.GeoUtils;
import org.elasticsearch.common.geo.SpatialStrategy;
import org.elasticsearch.common.geo.builders.ShapeBuilder;
import org.elasticsearch.common.geo.builders.ShapeBuilder.Orientation;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.DistanceUnit;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.fielddata.FieldDataType;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.MergeMappingException;
import org.elasticsearch.index.mapper.MergeResult;
import org.elasticsearch.index.mapper.ParseContext;
import org.elasticsearch.index.mapper.core.AbstractFieldMapper;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.elasticsearch.index.mapper.MapperBuilders.geoShapeField;


/**
 * FieldMapper for indexing {@link com.spatial4j.core.shape.Shape}s.
 * <p/>
 * Currently Shapes can only be indexed and can only be queried using
 * {@link org.elasticsearch.index.query.GeoShapeQueryParser}, consequently
 * a lot of behavior in this Mapper is disabled.
 * <p/>
 * Format supported:
 * <p/>
 * "field" : {
 * "type" : "polygon",
 * "coordinates" : [
 * [ [100.0, 0.0], [101.0, 0.0], [101.0, 1.0], [100.0, 1.0], [100.0, 0.0] ]
 * ]
 * }
 */
public class GeoShapeFieldMapper extends AbstractFieldMapper {

    public static final String CONTENT_TYPE = "geo_shape";

    public static class Names {
        public static final String TREE = "tree";
        public static final String TREE_GEOHASH = "geohash";
        public static final String TREE_QUADTREE = "quadtree";
        public static final String TREE_LEVELS = "tree_levels";
        public static final String TREE_PRESISION = "precision";
        public static final String DISTANCE_ERROR_PCT = "distance_error_pct";
        public static final String ORIENTATION = "orientation";
        public static final String STRATEGY = "strategy";
    }

    public static class Defaults {
        public static final String TREE = Names.TREE_GEOHASH;
        public static final String STRATEGY = SpatialStrategy.RECURSIVE.getStrategyName();
        public static final int GEOHASH_LEVELS = GeoUtils.geoHashLevelsForPrecision("50m");
        public static final int QUADTREE_LEVELS = GeoUtils.quadTreeLevelsForPrecision("50m");
        public static final double LEGACY_DISTANCE_ERROR_PCT = 0.025d;
        public static final Orientation ORIENTATION = Orientation.RIGHT;

        public static final MappedFieldType FIELD_TYPE = new GeoShapeFieldType();

        static {
            // setting name here is a hack so freeze can be called...instead all these options should be
            // moved to the default ctor for GeoShapeFieldType, and defaultFieldType() should be removed from mappers...
            FIELD_TYPE.setNames(new MappedFieldType.Names("DoesNotExist"));
            FIELD_TYPE.setIndexOptions(IndexOptions.DOCS);
            FIELD_TYPE.setTokenized(false);
            FIELD_TYPE.setStored(false);
            FIELD_TYPE.setStoreTermVectors(false);
            FIELD_TYPE.setOmitNorms(true);
            FIELD_TYPE.freeze();
        }
    }

    public static class Builder extends AbstractFieldMapper.Builder<Builder, GeoShapeFieldMapper> {

        public Builder(String name) {
            super(name, Defaults.FIELD_TYPE);
        }

        public GeoShapeFieldType fieldType() {
            return (GeoShapeFieldType)fieldType;
        }

        @Override
        public GeoShapeFieldMapper build(BuilderContext context) {
            GeoShapeFieldType geoShapeFieldType = (GeoShapeFieldType)fieldType;

            if (geoShapeFieldType.tree.equals("quadtree") && context.indexCreatedVersion().before(Version.V_2_0_0)) {
                geoShapeFieldType.setTree("legacyquadtree");
            }

            if (context.indexCreatedVersion().before(Version.V_2_0_0) ||
                (geoShapeFieldType.treeLevels() == 0 && geoShapeFieldType.precisionInMeters() < 0)) {
                geoShapeFieldType.setDefaultDistanceErrorPct(Defaults.LEGACY_DISTANCE_ERROR_PCT);
            }
            setupFieldType(context);

            return new GeoShapeFieldMapper(fieldType, context.indexSettings(), multiFieldsBuilder.build(this, context), copyTo);
        }
    }

    public static class TypeParser implements Mapper.TypeParser {

        @Override
        public Mapper.Builder parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            Builder builder = geoShapeField(name);
            for (Iterator<Map.Entry<String, Object>> iterator = node.entrySet().iterator(); iterator.hasNext();) {
                Map.Entry<String, Object> entry = iterator.next();
                String fieldName = Strings.toUnderscoreCase(entry.getKey());
                Object fieldNode = entry.getValue();
                if (Names.TREE.equals(fieldName)) {
                    builder.fieldType().setTree(fieldNode.toString());
                    iterator.remove();
                } else if (Names.TREE_LEVELS.equals(fieldName)) {
                    builder.fieldType().setTreeLevels(Integer.parseInt(fieldNode.toString()));
                    iterator.remove();
                } else if (Names.TREE_PRESISION.equals(fieldName)) {
                    builder.fieldType().setPrecisionInMeters(DistanceUnit.parse(fieldNode.toString(), DistanceUnit.DEFAULT, DistanceUnit.DEFAULT));
                    iterator.remove();
                } else if (Names.DISTANCE_ERROR_PCT.equals(fieldName)) {
                    builder.fieldType().setDistanceErrorPct(Double.parseDouble(fieldNode.toString()));
                    iterator.remove();
                } else if (Names.ORIENTATION.equals(fieldName)) {
                    builder.fieldType().setOrientation(ShapeBuilder.orientationFromString(fieldNode.toString()));
                    iterator.remove();
                } else if (Names.STRATEGY.equals(fieldName)) {
                    builder.fieldType().setStrategyName(fieldNode.toString());
                    iterator.remove();
                }
            }
            return builder;
        }
    }

    public static final class GeoShapeFieldType extends MappedFieldType {

        private String tree = Defaults.TREE;
        private String strategyName = Defaults.STRATEGY;
        private int treeLevels = 0;
        private double precisionInMeters = -1;
        private Double distanceErrorPct;
        private double defaultDistanceErrorPct = 0.0;
        private Orientation orientation = Defaults.ORIENTATION;

        // these are built when the field type is frozen
        private PrefixTreeStrategy defaultStrategy;
        private RecursivePrefixTreeStrategy recursiveStrategy;
        private TermQueryPrefixTreeStrategy termStrategy;

        public GeoShapeFieldType() {
            super(AbstractFieldMapper.Defaults.FIELD_TYPE);
        }

        protected GeoShapeFieldType(GeoShapeFieldType ref) {
            super(ref);
            this.tree = ref.tree;
            this.strategyName = ref.strategyName;
            this.treeLevels = ref.treeLevels;
            this.precisionInMeters = ref.precisionInMeters;
            this.distanceErrorPct = ref.distanceErrorPct;
            this.defaultDistanceErrorPct = ref.defaultDistanceErrorPct;
            this.orientation = ref.orientation;
        }

        @Override
        public MappedFieldType clone() {
            return new GeoShapeFieldType(this);
        }

        @Override
        public boolean equals(Object o) {
            if (!super.equals(o)) return false;
            GeoShapeFieldType that = (GeoShapeFieldType) o;
            return treeLevels == that.treeLevels &&
                precisionInMeters == that.precisionInMeters &&
                defaultDistanceErrorPct == that.defaultDistanceErrorPct &&
                Objects.equals(tree, that.tree) &&
                Objects.equals(strategyName, that.strategyName) &&
                Objects.equals(distanceErrorPct, that.distanceErrorPct) &&
                orientation == that.orientation;
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), tree, strategyName, treeLevels, precisionInMeters, distanceErrorPct, defaultDistanceErrorPct, orientation);
        }

        @Override
        public void freeze() {
            super.freeze();
            // This is a bit hackish: we need to setup the spatial tree and strategies once the field name is set, which
            // must be by the time freeze is called.
            SpatialPrefixTree prefixTree;
            if ("geohash".equals(tree)) {
                prefixTree = new GeohashPrefixTree(ShapeBuilder.SPATIAL_CONTEXT, getLevels(treeLevels, precisionInMeters, Defaults.GEOHASH_LEVELS, true));
            } else if ("legacyquadtree".equals(tree)) {
                prefixTree = new QuadPrefixTree(ShapeBuilder.SPATIAL_CONTEXT, getLevels(treeLevels, precisionInMeters, Defaults.QUADTREE_LEVELS, false));
            } else if ("quadtree".equals(tree)) {
                prefixTree = new PackedQuadPrefixTree(ShapeBuilder.SPATIAL_CONTEXT, getLevels(treeLevels, precisionInMeters, Defaults.QUADTREE_LEVELS, false));
            } else {
                throw new IllegalArgumentException("Unknown prefix tree type [" + tree + "]");
            }

            recursiveStrategy = new RecursivePrefixTreeStrategy(prefixTree, names().indexName());
            recursiveStrategy.setDistErrPct(distanceErrorPct());
            recursiveStrategy.setPruneLeafyBranches(false);
            termStrategy = new TermQueryPrefixTreeStrategy(prefixTree, names().indexName());
            termStrategy.setDistErrPct(distanceErrorPct());
            defaultStrategy = resolveStrategy(strategyName);
        }
        
        @Override
        public void validateCompatible(MappedFieldType fieldType, List<String> conflicts) {
            super.validateCompatible(fieldType, conflicts);
            GeoShapeFieldType other = (GeoShapeFieldType)fieldType;
            // prevent user from changing strategies
            if (strategyName().equals(other.strategyName()) == false) {
                conflicts.add("mapper [" + names().fullName() + "] has different strategy");
            }

            // prevent user from changing trees (changes encoding)
            if (tree().equals(other.tree()) == false) {
                conflicts.add("mapper [" + names().fullName() + "] has different tree");
            }

            // TODO we should allow this, but at the moment levels is used to build bookkeeping variables
            // in lucene's SpatialPrefixTree implementations, need a patch to correct that first
            if (treeLevels() != other.treeLevels()) {
                conflicts.add("mapper [" + names().fullName() + "] has different tree_levels");
            }
            if (precisionInMeters() != other.precisionInMeters()) {
                conflicts.add("mapper [" + names().fullName() + "] has different precision");
            }
        }

        private static int getLevels(int treeLevels, double precisionInMeters, int defaultLevels, boolean geoHash) {
            if (treeLevels > 0 || precisionInMeters >= 0) {
                return Math.max(treeLevels, precisionInMeters >= 0 ? (geoHash ? GeoUtils.geoHashLevelsForPrecision(precisionInMeters)
                    : GeoUtils.quadTreeLevelsForPrecision(precisionInMeters)) : 0);
            }
            return defaultLevels;
        }

        public String tree() {
            return tree;
        }

        public void setTree(String tree) {
            checkIfFrozen();
            this.tree = tree;
        }

        public String strategyName() {
            return strategyName;
        }

        public void setStrategyName(String strategyName) {
            checkIfFrozen();
            this.strategyName = strategyName;
        }

        public int treeLevels() {
            return treeLevels;
        }

        public void setTreeLevels(int treeLevels) {
            checkIfFrozen();
            this.treeLevels = treeLevels;
        }

        public double precisionInMeters() {
            return precisionInMeters;
        }

        public void setPrecisionInMeters(double precisionInMeters) {
            checkIfFrozen();
            this.precisionInMeters = precisionInMeters;
        }

        public double distanceErrorPct() {
            return distanceErrorPct == null ? defaultDistanceErrorPct : distanceErrorPct;
        }

        public void setDistanceErrorPct(double distanceErrorPct) {
            checkIfFrozen();
            this.distanceErrorPct = distanceErrorPct;
        }

        public void setDefaultDistanceErrorPct(double defaultDistanceErrorPct) {
            checkIfFrozen();
            this.defaultDistanceErrorPct = defaultDistanceErrorPct;
        }

        public Orientation orientation() { return this.orientation; }

        public void setOrientation(Orientation orientation) {
            checkIfFrozen();
            this.orientation = orientation;
        }

        public PrefixTreeStrategy defaultStrategy() {
            return this.defaultStrategy;
        }

        public PrefixTreeStrategy resolveStrategy(String strategyName) {
            if (SpatialStrategy.RECURSIVE.getStrategyName().equals(strategyName)) {
                return recursiveStrategy;
            }
            if (SpatialStrategy.TERM.getStrategyName().equals(strategyName)) {
                return termStrategy;
            }
            throw new IllegalArgumentException("Unknown prefix tree strategy [" + strategyName + "]");
        }

        @Override
        public String value(Object value) {
            throw new UnsupportedOperationException("GeoShape fields cannot be converted to String values");
        }

    }

    public GeoShapeFieldMapper(MappedFieldType fieldType, Settings indexSettings, MultiFields multiFields, CopyTo copyTo) {
        super(fieldType, false, null, indexSettings, multiFields, copyTo);
    }

    @Override
    public GeoShapeFieldType fieldType() {
        return (GeoShapeFieldType) super.fieldType();
    }

    @Override
    public MappedFieldType defaultFieldType() {
        return Defaults.FIELD_TYPE;
    }

    @Override
    public FieldDataType defaultFieldDataType() {
        return null;
    }

    @Override
    public Mapper parse(ParseContext context) throws IOException {
        try {
            Shape shape = context.parseExternalValue(Shape.class);
            if (shape == null) {
                ShapeBuilder shapeBuilder = ShapeBuilder.parse(context.parser(), this);
                if (shapeBuilder == null) {
                    return null;
                }
                shape = shapeBuilder.build();
            }
            Field[] fields = fieldType().defaultStrategy().createIndexableFields(shape);
            if (fields == null || fields.length == 0) {
                return null;
            }
            for (Field field : fields) {
                if (!customBoost()) {
                    field.setBoost(fieldType().boost());
                }
                context.doc().add(field);
            }
        } catch (Exception e) {
            throw new MapperParsingException("failed to parse [" + fieldType().names().fullName() + "]", e);
        }
        return null;
    }

    @Override
    protected void parseCreateField(ParseContext context, List<Field> fields) throws IOException {
    }

    @Override
    protected void doXContentBody(XContentBuilder builder, boolean includeDefaults, Params params) throws IOException {
        builder.field("type", contentType());

        if (includeDefaults || fieldType().tree().equals(Defaults.TREE) == false) {
            builder.field(Names.TREE, fieldType().tree());
        }
        if (includeDefaults || fieldType().treeLevels() != 0) {
            builder.field(Names.TREE_LEVELS, fieldType().treeLevels());
        }
        if (includeDefaults || fieldType().precisionInMeters() != -1) {
            builder.field(Names.TREE_PRESISION, DistanceUnit.METERS.toString(fieldType().precisionInMeters()));
        }
        if (includeDefaults || fieldType().strategyName() != Defaults.STRATEGY) {
            builder.field(Names.STRATEGY, fieldType().strategyName());
        }
        if (includeDefaults || fieldType().distanceErrorPct() != fieldType().defaultDistanceErrorPct) {
            builder.field(Names.DISTANCE_ERROR_PCT, fieldType().distanceErrorPct());
        }
        if (includeDefaults || fieldType().orientation() != Defaults.ORIENTATION) {
            builder.field(Names.ORIENTATION, fieldType().orientation());
        }
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }
}
