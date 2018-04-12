/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.execution.search.extractor;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.search.aggregations.bucket.MultiBucketsAggregation.Bucket;
import org.elasticsearch.xpack.sql.SqlIllegalArgumentException;
import org.elasticsearch.xpack.sql.querydsl.container.GroupByRef.Property;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

public class CompositeKeyExtractor implements BucketExtractor {

    /**
     * Key or Komposite extractor.
     */
    static final String NAME = "k";

    private final String key;
    private final Property property;
    private final DateTimeZone timeZone;

    /**
     * Constructs a new <code>CompositeKeyExtractor</code> instance.
     * The time-zone parameter is used to indicate a date key.
     */
    public CompositeKeyExtractor(String key, Property property, DateTimeZone timeZone) {
        this.key = key;
        this.property = property;
        this.timeZone = timeZone;
    }

    CompositeKeyExtractor(StreamInput in) throws IOException {
        key = in.readString();
        property = in.readEnum(Property.class);
        timeZone = in.readOptionalTimeZone();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(key);
        out.writeEnum(property);
        out.writeOptionalTimeZone(timeZone);
    }

    String key() {
        return key;
    }

    Property property() {
        return property;
    }

    DateTimeZone timeZone() {
        return timeZone;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public Object extract(Bucket bucket) {
        if (property == Property.COUNT) {
            return bucket.getDocCount();
        }
        // get the composite value
        Object m = bucket.getKey();

        if (!(m instanceof Map)) {
            throw new SqlIllegalArgumentException("Unexpected bucket returned: {}", m);
        }

        Object object = ((Map<?, ?>) m).get(key);

        if (timeZone != null) {
            if (object instanceof Long) {
                object = new DateTime(((Long) object).longValue(), timeZone);
            } else {
                throw new SqlIllegalArgumentException("Invalid date key returned: {}", object);
            }
        }

        return object;
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, property, timeZone);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        
        CompositeKeyExtractor other = (CompositeKeyExtractor) obj;
        return Objects.equals(key, other.key)
                && Objects.equals(property, other.property)
                && Objects.equals(timeZone, other.timeZone);
    }

    @Override
    public String toString() {
        return "|" + key + "|";
    }
}