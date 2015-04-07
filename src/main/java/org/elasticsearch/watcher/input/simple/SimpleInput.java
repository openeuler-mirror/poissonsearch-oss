/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.input.simple;

import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.watcher.input.Input;
import org.elasticsearch.watcher.input.InputException;
import org.elasticsearch.watcher.watch.Payload;
import org.elasticsearch.watcher.watch.WatchExecutionContext;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * This class just defines a simple xcontent map as an input
 */
public class SimpleInput extends Input<SimpleInput.Result> {

    public static final String TYPE = "simple";

    private final Payload payload;

    public SimpleInput(ESLogger logger, Payload payload) {
        super(logger);
        this.payload = payload;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Result execute(WatchExecutionContext ctx) throws IOException {
        return new Result(TYPE, payload);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        return payload.toXContent(builder, params);
    }

    @Override
    public int hashCode() {
        return Objects.hash(payload);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final SimpleInput other = (SimpleInput) obj;
        return Objects.equals(this.payload.data(), other.payload.data());
    }

    @Override
    public String toString() {
        return payload.toString();
    }

    public static class Result extends Input.Result {

        public Result(String type, Payload payload) {
            super(type, payload);
        }

        @Override
        protected XContentBuilder toXContentBody(XContentBuilder builder, Params params) throws IOException {
            return builder;
        }
    }

    public static class Parser extends AbstractComponent implements Input.Parser<Result,SimpleInput> {

        @Inject
        public Parser(Settings settings) {
            super(settings);
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public SimpleInput parse(XContentParser parser) throws IOException {
            if (parser.currentToken() != XContentParser.Token.START_OBJECT) {
                throw new InputException("could not parse simple input. expected an object but found [" + parser.currentToken() + "]");
            }
            Payload payload = new Payload.Simple(parser.map());
            return new SimpleInput(logger, payload);
        }

        @Override
        public Result parseResult(XContentParser parser) throws IOException {
            Payload payload = null;

            String currentFieldName = null;
            XContentParser.Token token;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else if (token == XContentParser.Token.START_OBJECT && currentFieldName != null) {
                    if (Input.Result.PAYLOAD_FIELD.match(currentFieldName)) {
                        payload = new Payload.XContent(parser);
                    } else {
                        throw new InputException("unable to parse [" + TYPE + "] input result. unexpected field [" + currentFieldName + "]");
                    }
                }
            }

            if (payload == null) {
                throw new InputException("unable to parse [" + TYPE + "] input result [payload] is a required field");
            }

            return new Result(TYPE, payload);
        }
    }

    public static class SourceBuilder implements Input.SourceBuilder {

        private Map<String, Object> data;

        public SourceBuilder(Map<String, Object> data) {
            this.data = data;
        }

        public Input.SourceBuilder put(String key, Object value) {
            data.put(key, value);
            return this;
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            return builder.map(data);

        }
    }
}
