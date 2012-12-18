/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.codec.postingsformat.PostingsFormatProvider;
import org.elasticsearch.index.mapper.*;
import org.elasticsearch.index.mapper.core.IntegerFieldMapper;
import org.elasticsearch.index.mapper.core.NumberFieldMapper;

import java.io.IOException;
import java.util.Map;

import static org.elasticsearch.common.xcontent.support.XContentMapValues.nodeBooleanValue;
import static org.elasticsearch.index.mapper.core.TypeParsers.parseStore;

public class SizeFieldMapper extends IntegerFieldMapper implements RootMapper {

    public static final String NAME = "_size";
    public static final String CONTENT_TYPE = "_size";

    public static class Defaults extends IntegerFieldMapper.Defaults {
        public static final String NAME = CONTENT_TYPE;
        public static final boolean ENABLED = false;

        public static final FieldType SIZE_FIELD_TYPE = new FieldType(IntegerFieldMapper.Defaults.FIELD_TYPE);

        static {
            SIZE_FIELD_TYPE.freeze();
        }
    }

    public static class Builder extends NumberFieldMapper.Builder<Builder, IntegerFieldMapper> {

        protected boolean enabled = Defaults.ENABLED;

        public Builder() {
            super(Defaults.NAME, new FieldType(Defaults.SIZE_FIELD_TYPE));
            builder = this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return builder;
        }

        public Builder store(boolean store) {
            this.fieldType.setStored(store);
            return builder;
        }

        @Override
        public SizeFieldMapper build(BuilderContext context) {
            return new SizeFieldMapper(enabled, fieldType, provider);
        }
    }

    public static class TypeParser implements Mapper.TypeParser {
        @Override
        public Mapper.Builder parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            SizeFieldMapper.Builder builder = new SizeFieldMapper.Builder();
            for (Map.Entry<String, Object> entry : node.entrySet()) {
                String fieldName = Strings.toUnderscoreCase(entry.getKey());
                Object fieldNode = entry.getValue();
                if (fieldName.equals("enabled")) {
                    builder.enabled(nodeBooleanValue(fieldNode));
                } else if (fieldName.equals("store")) {
                    builder.store(parseStore(fieldName, fieldNode.toString()));
                }
            }
            return builder;
        }
    }

    private final boolean enabled;

    public SizeFieldMapper() {
        this(Defaults.ENABLED, new FieldType(Defaults.SIZE_FIELD_TYPE), null);
    }

    public SizeFieldMapper(boolean enabled, FieldType fieldType, PostingsFormatProvider provider) {
        super(new Names(Defaults.NAME), Defaults.PRECISION_STEP, Defaults.FUZZY_FACTOR,
                Defaults.BOOST, fieldType, Defaults.NULL_VALUE, Defaults.IGNORE_MALFORMED, provider, null);
        this.enabled = enabled;
    }

    @Override
    protected String contentType() {
        return Defaults.NAME;
    }

    public boolean enabled() {
        return this.enabled;
    }

    @Override
    public void validate(ParseContext context) throws MapperParsingException {
    }

    @Override
    public void preParse(ParseContext context) throws IOException {
    }

    @Override
    public void postParse(ParseContext context) throws IOException {
        // we post parse it so we get the size stored, possibly compressed (source will be preParse)
        super.parse(context);
    }

    @Override
    public void parse(ParseContext context) throws IOException {
        // nothing to do here, we call the parent in postParse
    }

    @Override
    public boolean includeInObject() {
        return false;
    }

    @Override
    protected Field innerParseCreateField(ParseContext context) throws IOException {
        if (!enabled) {
            return null;
        }
        if (context.flyweight()) {
            return null;
        }
        return new CustomIntegerNumericField(this, context.source().length(), fieldType);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        // all are defaults, no need to write it at all
        if (enabled == Defaults.ENABLED && stored() == Defaults.SIZE_FIELD_TYPE.stored()) {
            return builder;
        }
        builder.startObject(contentType());
        if (enabled != Defaults.ENABLED) {
            builder.field("enabled", enabled);
        }
        if (stored() != Defaults.SIZE_FIELD_TYPE.stored()) {
            builder.field("store", stored());
        }
        builder.endObject();
        return builder;
    }

    @Override
    public void merge(Mapper mergeWith, MergeContext mergeContext) throws MergeMappingException {
        // maybe allow to change enabled? But then we need to figure out null for default value
    }
}