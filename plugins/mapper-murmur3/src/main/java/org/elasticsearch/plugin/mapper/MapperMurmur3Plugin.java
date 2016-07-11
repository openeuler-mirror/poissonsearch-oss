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

package org.elasticsearch.plugin.mapper;

import java.util.Collections;
import java.util.Map;

import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.index.mapper.murmur3.Murmur3FieldMapper;
import org.elasticsearch.plugins.MapperPlugin;
import org.elasticsearch.plugins.Plugin;

public class MapperMurmur3Plugin extends Plugin implements MapperPlugin {

    @Override
    public Map<String, Mapper.TypeParser> getMappers() {
        return Collections.singletonMap(Murmur3FieldMapper.CONTENT_TYPE, new Murmur3FieldMapper.TypeParser());
    }
}
