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

package org.elasticsearch.index.mapper.internal;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexOptions;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.MetadataFieldMapper;
import org.elasticsearch.index.mapper.ParseContext;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.common.xcontent.support.XContentMapValues.lenientNodeBooleanValue;

/**
 *
 */
public class RoutingFieldMapper extends MetadataFieldMapper {

    public static final String NAME = "_routing";
    public static final String CONTENT_TYPE = "_routing";

    public static class Defaults {
        public static final String NAME = "_routing";

        public static final MappedFieldType FIELD_TYPE = new RoutingFieldType();

        static {
            FIELD_TYPE.setIndexOptions(IndexOptions.DOCS);
            FIELD_TYPE.setTokenized(false);
            FIELD_TYPE.setStored(true);
            FIELD_TYPE.setOmitNorms(true);
            FIELD_TYPE.setIndexAnalyzer(Lucene.KEYWORD_ANALYZER);
            FIELD_TYPE.setSearchAnalyzer(Lucene.KEYWORD_ANALYZER);
            FIELD_TYPE.setName(NAME);
            FIELD_TYPE.freeze();
        }

        public static final boolean REQUIRED = false;
    }

    public static class Builder extends MetadataFieldMapper.Builder<Builder, RoutingFieldMapper> {

        private boolean required = Defaults.REQUIRED;

        public Builder(MappedFieldType existing) {
            super(Defaults.NAME, existing == null ? Defaults.FIELD_TYPE : existing, Defaults.FIELD_TYPE);
        }

        public Builder required(boolean required) {
            this.required = required;
            return builder;
        }

        @Override
        public RoutingFieldMapper build(BuilderContext context) {
            return new RoutingFieldMapper(fieldType, required, context.indexSettings());
        }
    }

    public static class TypeParser implements MetadataFieldMapper.TypeParser {
        @Override
        public MetadataFieldMapper.Builder parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            Builder builder = new Builder(parserContext.mapperService().fullName(NAME));
            for (Iterator<Map.Entry<String, Object>> iterator = node.entrySet().iterator(); iterator.hasNext();) {
                Map.Entry<String, Object> entry = iterator.next();
                String fieldName = Strings.toUnderscoreCase(entry.getKey());
                Object fieldNode = entry.getValue();
                if (fieldName.equals("required")) {
                    builder.required(lenientNodeBooleanValue(fieldNode));
                    iterator.remove();
                }
            }
            return builder;
        }

        @Override
        public MetadataFieldMapper getDefault(Settings indexSettings, MappedFieldType fieldType, String typeName) {
            return new RoutingFieldMapper(indexSettings, fieldType);
        }
    }

    static final class RoutingFieldType extends MappedFieldType {

        public RoutingFieldType() {
        }

        protected RoutingFieldType(RoutingFieldType ref) {
            super(ref);
        }

        @Override
        public MappedFieldType clone() {
            return new RoutingFieldType(this);
        }

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }
    }

    private boolean required;

    private RoutingFieldMapper(Settings indexSettings, MappedFieldType existing) {
        this(existing == null ? Defaults.FIELD_TYPE.clone() : existing.clone(), Defaults.REQUIRED, indexSettings);
    }

    private RoutingFieldMapper(MappedFieldType fieldType, boolean required, Settings indexSettings) {
        super(NAME, fieldType, Defaults.FIELD_TYPE, indexSettings);
        this.required = required;
    }

    public void markAsRequired() {
        this.required = true;
    }

    public boolean required() {
        return this.required;
    }

    @Override
    public void preParse(ParseContext context) throws IOException {
        super.parse(context);
    }

    @Override
    public void postParse(ParseContext context) throws IOException {
    }

    @Override
    public Mapper parse(ParseContext context) throws IOException {
        // no need ot parse here, we either get the routing in the sourceToParse
        // or we don't have routing, if we get it in sourceToParse, we process it in preParse
        // which will always be called
        return null;
    }

    @Override
    protected void parseCreateField(ParseContext context, List<Field> fields) throws IOException {
        if (context.sourceToParse().routing() != null) {
            String routing = context.sourceToParse().routing();
            if (routing != null) {
                if (fieldType().indexOptions() != IndexOptions.NONE || fieldType().stored()) {
                    fields.add(new Field(fieldType().name(), routing, fieldType()));
                }
            }
        }
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        boolean includeDefaults = params.paramAsBoolean("include_defaults", false);

        // if all are defaults, no sense to write it at all
        if (!includeDefaults && required == Defaults.REQUIRED) {
            return builder;
        }
        builder.startObject(CONTENT_TYPE);
        if (includeDefaults || required != Defaults.REQUIRED) {
            builder.field("required", required);
        }
        builder.endObject();
        return builder;
    }

    @Override
    protected void doMerge(Mapper mergeWith, boolean updateAllTypes) {
        // do nothing here, no merging, but also no exception
    }
}
