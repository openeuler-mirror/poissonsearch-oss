/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.job.results;

import org.elasticsearch.action.support.ToXContentToBytes;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.ParseFieldMatcherSupplier;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Objects;

public class PageParams extends ToXContentToBytes implements Writeable {

    public static final ParseField PAGE = new ParseField("page");
    public static final ParseField FROM = new ParseField("from");
    public static final ParseField SIZE = new ParseField("size");

    public static final ConstructingObjectParser<PageParams, ParseFieldMatcherSupplier> PARSER = new ConstructingObjectParser<>(
            PAGE.getPreferredName(), a -> new PageParams((int) a[0], (int) a[1]));

    public static final int MAX_FROM_SIZE_SUM = 10000;

    static {
        PARSER.declareInt(ConstructingObjectParser.constructorArg(), FROM);
        PARSER.declareInt(ConstructingObjectParser.constructorArg(), SIZE);
    }

    private final int from;
    private final int size;

    public PageParams(StreamInput in) throws IOException {
        this(in.readVInt(), in.readVInt());
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVInt(from);
        out.writeVInt(size);
    }

    public PageParams(int from, int SIZE) {
        if (from < 0) {
            throw new IllegalArgumentException("Parameter [" + FROM.getPreferredName() + "] cannot be < 0");
        }
        if (SIZE < 0) {
            throw new IllegalArgumentException("Parameter [" + PageParams.SIZE.getPreferredName() + "] cannot be < 0");
        }
        if (from + SIZE > MAX_FROM_SIZE_SUM) {
            throw new IllegalArgumentException("The sum of parameters [" + FROM.getPreferredName() + "] and ["
                    + PageParams.SIZE.getPreferredName() + "] cannot be higher than " + MAX_FROM_SIZE_SUM + ".");
        }
        this.from = from;
        this.size = SIZE;
    }

    public int getFrom() {
        return from;
    }

    public int getSize() {
        return size;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(FROM.getPreferredName(), from);
        builder.field(SIZE.getPreferredName(), size);
        builder.endObject();
        return builder;
    }

    @Override
    public int hashCode() {
        return Objects.hash(from, size);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        PageParams other = (PageParams) obj;
        return Objects.equals(from, other.from) &&
                Objects.equals(size, other.size);
    }

}
