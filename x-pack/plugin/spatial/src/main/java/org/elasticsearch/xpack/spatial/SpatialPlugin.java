/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.spatial;

import org.elasticsearch.common.inject.Module;
import org.elasticsearch.geo.GeoPlugin;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.ingest.Processor;
import org.elasticsearch.plugins.IngestPlugin;
import org.elasticsearch.plugins.MapperPlugin;
import org.elasticsearch.plugins.SearchPlugin;
import org.elasticsearch.xpack.core.XPackPlugin;
import org.elasticsearch.xpack.spatial.index.mapper.GeoShapeWithDocValuesFieldMapper;
import org.elasticsearch.xpack.spatial.index.mapper.PointFieldMapper;
import org.elasticsearch.xpack.spatial.index.mapper.ShapeFieldMapper;
import org.elasticsearch.xpack.spatial.index.query.ShapeQueryBuilder;
import org.elasticsearch.xpack.spatial.ingest.CircleProcessor;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.singletonList;

public class SpatialPlugin extends GeoPlugin implements MapperPlugin, SearchPlugin, IngestPlugin {

    public Collection<Module> createGuiceModules() {
        return Collections.singletonList(b -> {
            XPackPlugin.bindFeatureSet(b, SpatialFeatureSet.class);
        });
    }

    @Override
    public Map<String, Mapper.TypeParser> getMappers() {
        Map<String, Mapper.TypeParser> mappers = new HashMap<>(super.getMappers());
        mappers.put(ShapeFieldMapper.CONTENT_TYPE, new ShapeFieldMapper.TypeParser());
        mappers.put(PointFieldMapper.CONTENT_TYPE, new PointFieldMapper.TypeParser());
        mappers.put(GeoShapeWithDocValuesFieldMapper.CONTENT_TYPE, new GeoShapeWithDocValuesFieldMapper.TypeParser());
        return Collections.unmodifiableMap(mappers);
    }

    @Override
    public List<QuerySpec<?>> getQueries() {
        return singletonList(new QuerySpec<>(ShapeQueryBuilder.NAME, ShapeQueryBuilder::new, ShapeQueryBuilder::fromXContent));
    }

    @Override
    public Map<String, Processor.Factory> getProcessors(Processor.Parameters parameters) {
        return Collections.singletonMap(CircleProcessor.TYPE, new CircleProcessor.Factory());
    }
}
