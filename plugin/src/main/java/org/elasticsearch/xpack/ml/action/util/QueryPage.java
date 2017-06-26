/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.action.util;

import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.xpack.ml.utils.ExceptionsHelper;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Generic wrapper class for a page of query results and the total number of
 * query results.<br>
 * {@linkplain #count()} is the total number of results but that value may
 * not be equal to the actual length of the {@linkplain #results()} list if from
 * &amp; take or some cursor was used in the database query.
 */
public final class QueryPage<T extends ToXContent & Writeable> implements ToXContentObject, Writeable {

    public static final ParseField COUNT = new ParseField("count");
    public static final ParseField DEFAULT_RESULTS_FIELD = new ParseField("results_field");

    private final ParseField resultsField;
    private final List<T> results;
    private final long count;

    public QueryPage(List<T> results, long count, ParseField resultsField) {
        this.results = results;
        this.count = count;
        this.resultsField = ExceptionsHelper.requireNonNull(resultsField, DEFAULT_RESULTS_FIELD.getPreferredName());
    }

    public QueryPage(StreamInput in, Reader<T> hitReader) throws IOException {
        resultsField = new ParseField(in.readString());
        results = in.readList(hitReader);
        count = in.readLong();
    }

    public static ResourceNotFoundException emptyQueryPage(ParseField resultsField) {
        return new ResourceNotFoundException("Could not find requested " + resultsField.getPreferredName());
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(resultsField.getPreferredName());
        out.writeList(results);
        out.writeLong(count);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        doXContentBody(builder, params);
        builder.endObject();
        return builder;
    }

    public XContentBuilder doXContentBody(XContentBuilder builder, Params params) throws IOException {
        builder.field(COUNT.getPreferredName(), count);
        builder.field(resultsField.getPreferredName(), results);
        return builder;
    }

    public List<T> results() {
        return results;
    }

    public long count() {
        return count;
    }

    public ParseField getResultsField() {
        return resultsField;
    }

    @Override
    public int hashCode() {
        return Objects.hash(results, count);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }

        if (getClass() != obj.getClass()) {
            return false;
        }

        @SuppressWarnings("unchecked")
        QueryPage<T> other = (QueryPage<T>) obj;
        return Objects.equals(results, other.results) &&
                Objects.equals(count, other.count);
    }
}
