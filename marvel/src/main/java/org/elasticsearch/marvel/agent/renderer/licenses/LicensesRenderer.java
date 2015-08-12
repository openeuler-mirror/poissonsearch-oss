/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.marvel.agent.renderer.licenses;

import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentBuilderString;
import org.elasticsearch.license.core.License;
import org.elasticsearch.marvel.agent.collector.licenses.LicensesMarvelDoc;
import org.elasticsearch.marvel.agent.renderer.AbstractRenderer;

import java.io.IOException;
import java.util.List;

public class LicensesRenderer extends AbstractRenderer<LicensesMarvelDoc> {

    public LicensesRenderer() {
        super(Strings.EMPTY_ARRAY, false);
    }

    @Override
    protected void doRender(LicensesMarvelDoc marvelDoc, XContentBuilder builder, ToXContent.Params params) throws IOException {
        LicensesMarvelDoc.Payload payload = marvelDoc.payload();

        if (payload != null) {
            builder.field(Fields.VERSION, payload.getVersion());
            builder.startArray(Fields.LICENSES);

            List<License> licenses = payload.getLicenses();
            if (licenses != null) {
                for (License license : licenses) {
                    builder.startObject();
                    builder.field(Fields.STATUS, status(license));
                    builder.field(Fields.UID, license.uid());
                    builder.field(Fields.TYPE, license.type());
                    builder.dateValueField(Fields.ISSUE_DATE_IN_MILLIS, Fields.ISSUE_DATE, license.issueDate());
                    builder.field(Fields.FEATURE, license.feature());
                    builder.dateValueField(Fields.EXPIRY_DATE_IN_MILLIS, Fields.EXPIRY_DATE, license.expiryDate());
                    builder.field(Fields.MAX_NODES, license.maxNodes());
                    builder.field(Fields.ISSUED_TO, license.issuedTo());
                    builder.field(Fields.ISSUER, license.issuer());
                    builder.field(Fields.HKEY, hash(license, marvelDoc.clusterName()));
                    builder.endObject();
                }
            }
            builder.endArray();
        }
    }

    // TODO (tlrx): move status as a calculated getter in License class then remove this method
    public static String status(License license) {
        String status = "active";
        long now = System.currentTimeMillis();
        if (license.issueDate() > now) {
            status = "invalid";
        } else if (license.expiryDate() < now) {
            status = "expired";
        }
        return status;
    }

    public static String hash(License license, String clusterName) {
        String toHash = status(license) + license.uid() + license.type() + String.valueOf(license.expiryDate()) + clusterName;
        return Hashing.sha256().hashString(toHash, Charsets.UTF_8).toString();
    }

    static final class Fields {
        static final XContentBuilderString LICENSES = new XContentBuilderString("licenses");
        static final XContentBuilderString VERSION = new XContentBuilderString("version");

        static final XContentBuilderString HKEY = new XContentBuilderString("hkey");

        static final XContentBuilderString STATUS = new XContentBuilderString("status");
        static final XContentBuilderString UID = new XContentBuilderString("uid");
        static final XContentBuilderString TYPE = new XContentBuilderString("type");
        static final XContentBuilderString FEATURE = new XContentBuilderString("feature");
        static final XContentBuilderString ISSUE_DATE_IN_MILLIS = new XContentBuilderString("issue_date_in_millis");
        static final XContentBuilderString ISSUE_DATE = new XContentBuilderString("issue_date");
        static final XContentBuilderString EXPIRY_DATE_IN_MILLIS = new XContentBuilderString("expiry_date_in_millis");
        static final XContentBuilderString EXPIRY_DATE = new XContentBuilderString("expiry_date");
        static final XContentBuilderString MAX_NODES = new XContentBuilderString("max_nodes");
        static final XContentBuilderString ISSUED_TO = new XContentBuilderString("issued_to");
        static final XContentBuilderString ISSUER = new XContentBuilderString("issuer");
    }
}
