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

package org.elasticsearch.index.mapper.externalvalues;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.StringField;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.fielddata.FieldDataType;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.MetadataFieldMapper;
import org.elasticsearch.index.mapper.ParseContext;
import org.elasticsearch.index.mapper.core.BooleanFieldMapper;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class ExternalMetadataMapper extends MetadataFieldMapper {

    static final String CONTENT_TYPE = "_external_root";
    static final String FIELD_NAME = "_is_external";
    static final String FIELD_VALUE = "true";

    private static MappedFieldType FIELD_TYPE = new BooleanFieldMapper.BooleanFieldType();
    static {
        FIELD_TYPE.setNames(new MappedFieldType.Names(FIELD_NAME));
        FIELD_TYPE.freeze();
    }

    protected ExternalMetadataMapper(Settings indexSettings) {
        super(FIELD_NAME, FIELD_TYPE, FIELD_TYPE, indexSettings);
    }

    @Override
    public String name() {
        return CONTENT_TYPE;
    }

    @Override
    protected void parseCreateField(ParseContext context, List<Field> fields) throws IOException {
        // handled in post parse
    }

    @Override
    public void doMerge(Mapper mergeWith, boolean updateAllTypes) {
        if (!(mergeWith instanceof ExternalMetadataMapper)) {
            throw new IllegalArgumentException("Trying to merge " + mergeWith + " with " + this);
        }
    }

    @Override
    public Iterator<Mapper> iterator() {
        return Collections.emptyIterator();
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        return builder.startObject(CONTENT_TYPE).endObject();
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    public void preParse(ParseContext context) throws IOException {
    }

    @Override
    public void postParse(ParseContext context) throws IOException {
        context.doc().add(new StringField(FIELD_NAME, FIELD_VALUE, Store.YES));
    }

    public static class Builder extends MetadataFieldMapper.Builder<Builder, ExternalMetadataMapper> {

        protected Builder() {
            super(CONTENT_TYPE, FIELD_TYPE);
        }

        @Override
        public ExternalMetadataMapper build(BuilderContext context) {
            return new ExternalMetadataMapper(context.indexSettings());
        }
        
    }

    public static class TypeParser implements MetadataFieldMapper.TypeParser {

        @Override
        public MetadataFieldMapper.Builder<?, ?> parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            return new Builder();
        }

        @Override
        public MetadataFieldMapper getDefault(Settings indexSettings, MappedFieldType fieldType, String typeName) {
            return new ExternalMetadataMapper(indexSettings);
        }
        
    }

}
