/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license.core;

import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;

public class ESLicenses {

    public static void toXContent(Collection<ESLicense> licenses, XContentBuilder builder) throws IOException {
        builder.startObject();
        builder.startArray("licenses");
        for (ESLicense license : licenses) {
            ESLicense.toXContent(license, builder);
        }
        builder.endArray();
        builder.endObject();
    }

    public static Set<ESLicense> fromSource(String content) throws IOException {
        return fromSource(content.getBytes(Charset.forName("UTF-8")));
    }

    public static Set<ESLicense> fromSource(byte[] bytes) throws IOException {
        return fromXContent(XContentFactory.xContent(bytes).createParser(bytes));
    }

    private static Set<ESLicense> fromXContent(XContentParser parser) throws IOException {
        Set<ESLicense> esLicenses = new HashSet<>();
        final Map<String, Object> licensesMap = parser.mapAndClose();
        final List<Map<String, Object>> licenseMaps = (ArrayList<Map<String, Object>>)licensesMap.get("licenses");
        for (Map<String, Object> licenseMap : licenseMaps) {
            final ESLicense esLicense = ESLicense.fromXContent(licenseMap);
            esLicenses.add(esLicense);
        }
        return esLicenses;
    }

    public static Set<ESLicense> readFrom(StreamInput in) throws IOException {
        int size = in.readVInt();
        Set<ESLicense> esLicenses = new HashSet<>(size);
        for (int i = 0; i < size; i++) {
            esLicenses.add(ESLicense.readFrom(in));
        }
        return esLicenses;
    }

    public static void writeTo(Set<ESLicense> esLicenses, StreamOutput out) throws IOException {
        out.writeVInt(esLicenses.size());
        for (ESLicense license : esLicenses) {
            ESLicense.writeTo(license, out);
        }

    }
}
