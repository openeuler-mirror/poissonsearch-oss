/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.spatial.index.query;

import org.elasticsearch.Version;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.geo.ShapeRelation;
import org.elasticsearch.geometry.Geometry;
import org.elasticsearch.geometry.ShapeType;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.xpack.spatial.util.ShapeTestUtils;

import java.io.IOException;

public class ShapeQueryBuilderOverShapeTests extends ShapeQueryBuilderTests {

    @Override
    protected void initializeAdditionalMappings(MapperService mapperService) throws IOException {
        mapperService.merge(docType, new CompressedXContent(Strings.toString(PutMappingRequest.buildFromSimplifiedDef(docType,
            fieldName(), "type=shape"))), MapperService.MergeReason.MAPPING_UPDATE);
    }

    @Override
    protected ShapeRelation getShapeRelation(ShapeType type) {
        QueryShardContext context = createShardContext();
        if (context.indexVersionCreated().onOrAfter(Version.V_7_5_0)) { // CONTAINS is only supported from version 7.5
            if (type == ShapeType.LINESTRING || type == ShapeType.MULTILINESTRING) {
                return randomFrom(ShapeRelation.DISJOINT, ShapeRelation.INTERSECTS, ShapeRelation.CONTAINS);
            } else {
                return randomFrom(ShapeRelation.DISJOINT, ShapeRelation.INTERSECTS,
                    ShapeRelation.WITHIN, ShapeRelation.CONTAINS);
            }
        } else {
            if (type == ShapeType.LINESTRING || type == ShapeType.MULTILINESTRING) {
                return randomFrom(ShapeRelation.DISJOINT, ShapeRelation.INTERSECTS);
            } else {
                return randomFrom(ShapeRelation.DISJOINT, ShapeRelation.INTERSECTS, ShapeRelation.WITHIN);
            }
        }
    }

    @Override
    protected Geometry getGeometry() {
        return ShapeTestUtils.randomGeometry(false);
    }
}
