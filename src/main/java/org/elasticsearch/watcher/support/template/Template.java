/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.support.template;

import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.xcontent.*;
import org.elasticsearch.script.ScriptService.ScriptType;
import org.elasticsearch.watcher.WatcherException;

import java.io.IOException;
import java.util.Map;

/**
 *
 */
public class Template implements ToXContent {

    private final String template;
    private final @Nullable XContentType contentType;
    private final @Nullable ScriptType type;
    private final @Nullable Map<String, Object> params;

    Template(String template) {
        this(template, null, null, null);
    }

    Template(String template, @Nullable XContentType contentType, @Nullable ScriptType type, @Nullable Map<String, Object> params) {
        this.template = template;
        this.contentType = contentType;
        this.type = type;
        this.params = params;
    }

    public String getTemplate() {
        return template;
    }

    public XContentType getContentType() {
        return contentType;
    }

    public ScriptType getType() {
        return type != null ? type : ScriptType.INLINE;
    }

    public Map<String, Object> getParams() {
        return params != null ? params : ImmutableMap.<String, Object>of();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Template template1 = (Template) o;

        if (!template.equals(template1.template)) return false;
        if (contentType != template1.contentType) return false;
        if (type != template1.type) return false;
        return !(params != null ? !params.equals(template1.params) : template1.params != null);

    }

    @Override
    public int hashCode() {
        int result = template.hashCode();
        result = 31 * result + (contentType != null ? contentType.hashCode() : 0);
        result = 31 * result + (type != null ? type.hashCode() : 0);
        result = 31 * result + (params != null ? params.hashCode() : 0);
        return result;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        if (type == null) {
            return builder.value(template);
        }
        builder.startObject();
        switch (type) {
            case INLINE:
                if (contentType != null && builder.contentType() == contentType) {
                    builder.rawField(Field.INLINE.getPreferredName(), new BytesArray(template));
                } else {
                    builder.field(Field.INLINE.getPreferredName(), template);
                }
                break;
            case FILE:
                builder.field(Field.FILE.getPreferredName(), template);
                break;
            case INDEXED:
                builder.field(Field.ID.getPreferredName(), template);
                break;
            default:
                throw new WatcherException("unsupported script type [{}]", type);
        }
        if (this.params != null) {
            builder.field(Field.PARAMS.getPreferredName(), this.params);
        }
        return builder.endObject();
    }

    public static Template parse(XContentParser parser) throws IOException {
        XContentParser.Token token = parser.currentToken();
        if (token.isValue()) {
            return new Template(String.valueOf(parser.objectText()));
        }
        if (token != XContentParser.Token.START_OBJECT) {
            throw new ParseException("expected a string value or an object, but found [{}] instead", token);
        }

        String template = null;
        XContentType contentType = null;
        ScriptType type = null;
        Map<String, Object> params = null;

        String currentFieldName = null;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (Field.INLINE.match(currentFieldName)) {
                type = ScriptType.INLINE;
                if (token.isValue()) {
                    template = String.valueOf(parser.objectText());
                } else {
                    contentType = parser.contentType();
                    XContentBuilder builder = XContentFactory.contentBuilder(contentType);
                    template = builder.copyCurrentStructure(parser).bytes().toUtf8();
                }
            } else if (Field.FILE.match(currentFieldName)) {
                type = ScriptType.FILE;
                if (token == XContentParser.Token.VALUE_STRING) {
                    template = parser.text();
                } else {
                    throw new ParseException("expected a string value for field [{}], but found [{}]", currentFieldName, token);
                }
            } else if (Field.ID.match(currentFieldName)) {
                type = ScriptType.INDEXED;
                if (token == XContentParser.Token.VALUE_STRING) {
                    template = parser.text();
                } else {
                    throw new ParseException("expected a string value for field [{}], but found [{}]", currentFieldName, token);
                }
            } else if (Field.PARAMS.match(currentFieldName)) {
                if (token == XContentParser.Token.START_OBJECT) {
                    params = parser.map();
                } else {
                    throw new ParseException("expected an object for field [{}], but found [{}]", currentFieldName, token);
                }
            } else {
                throw new ParseException("unexpected field [{}]", currentFieldName);
            }
        }
        if (template == null) {
            throw new ParseException("expected one of [{}], [{}] or [{}] fields, but found none", Field.INLINE.getPreferredName(), Field.FILE.getPreferredName(), Field.ID.getPreferredName());
        }
        assert type != null : "if template is not null, type should definitely not be null";
        return new Template(template, contentType, type, params);
    }

    public static Builder inline(XContentBuilder template) {
        return new Builder.Inline(template.bytes().toUtf8()).contentType(template.contentType());
    }

    public static Builder inline(String text) {
        return new Builder.Inline(text);
    }

    public static Builder file(String file) {
        return new Builder.File(file);
    }

    public static Builder indexed(String id) {
        return new Builder.Indexed(id);
    }

    public static Builder.DefaultType defaultType(String text) {
        return new Builder.DefaultType(text);
    }

    public static abstract class Builder<B extends Builder> {

        protected final ScriptType type;
        protected final String template;
        protected Map<String, Object> params;

        protected Builder(String template, ScriptType type) {
            this.template = template;
            this.type = type;
        }

        public B params(Map<String, Object> params) {
            this.params = params;
            return (B) this;
        }

        public abstract Template build();

        public static class Inline extends Builder<Inline> {

            private XContentType contentType;

            public Inline(String script) {
                super(script, ScriptType.INLINE);
            }

            public Inline contentType(XContentType contentType) {
                this.contentType = contentType;
                return this;
            }

            @Override
            public Template build() {
                return new Template(template, contentType, type, params);
            }
        }

        public static class File extends Builder<File> {

            public File(String file) {
                super(file, ScriptType.FILE);
            }

            @Override
            public Template build() {
                return new Template(template, null, type, params);
            }
        }

        public static class Indexed extends Builder<Indexed> {

            public Indexed(String id) {
                super(id, ScriptType.INDEXED);
            }

            @Override
            public Template build() {
                return new Template(template, null, type, params);
            }
        }

        public static class DefaultType extends Builder<DefaultType> {

            public DefaultType(String text) {
                super(text, null);
            }

            @Override
            public Template build() {
                return new Template(template, null, type, params);
            }
        }
    }

    public static class ParseException extends WatcherException {

        public ParseException(String msg, Object... args) {
            super(msg, args);
        }

        public ParseException(String msg, Throwable cause, Object... args) {
            super(msg, cause, args);
        }
    }

    public interface Field {
        ParseField INLINE = new ParseField("inline");
        ParseField FILE = new ParseField("file");
        ParseField ID = new ParseField("id");
        ParseField PARAMS = new ParseField("params");
    }
}

