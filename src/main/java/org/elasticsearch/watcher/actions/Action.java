/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.actions;

import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.watcher.transform.Transform;
import org.elasticsearch.watcher.watch.Payload;
import org.elasticsearch.watcher.execution.WatchExecutionContext;

import java.io.IOException;

/**
 */
public abstract class Action<R extends Action.Result> implements ToXContent {

    protected final ESLogger logger;

    protected Action(ESLogger logger) {
        this.logger = logger;
    }

    /**
     * @return the type of this action
     */
    public abstract String type();

    protected abstract R execute(String actionId, WatchExecutionContext context, Payload payload) throws IOException;

    /**
     * Parses xcontent to a concrete action of the same type.
     */
    public interface Parser<R extends Result, T extends Action<R>> {

        /**
         * @return  The type of the action
         */
        String type();

        /**
         * Parses the given xcontent and creates a concrete action
         */
        T parse(XContentParser parser) throws IOException;

        R parseResult(XContentParser parser) throws IOException;
    }

    public static abstract class Result implements ToXContent {

        public static final ParseField SUCCESS_FIELD = new ParseField("success");

        protected final String type;
        protected final boolean success;

        protected Result(String type, boolean success) {
            this.type = type;
            this.success = success;
        }

        public String type() {
            return type;
        }

        public boolean success() {
            return success;
        }


        @Override
        public final XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field(SUCCESS_FIELD.getPreferredName(), success);
            xContentBody(builder, params);
            return builder.endObject();
        }

        protected abstract XContentBuilder xContentBody(XContentBuilder builder, Params params) throws IOException;

    }

    public static abstract class SourceBuilder<SB extends SourceBuilder<SB>> implements ToXContent {

        protected @Nullable Transform.SourceBuilder transform;

        public SB transform(Transform.SourceBuilder transform) {
            this.transform = transform;
            return (SB) this;
        }

        public abstract String type();

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            if (transform != null) {
                builder.startObject(Transform.Parser.TRANSFORM_FIELD.getPreferredName())
                        .field(transform.type(), transform)
                        .endObject();
            }
            builder.field(type());
            actionXContent(builder, params);
            return builder.endObject();
        }

        protected abstract XContentBuilder actionXContent(XContentBuilder builder, Params params) throws IOException;
    }
}
