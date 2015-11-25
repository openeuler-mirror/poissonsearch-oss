/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license.plugin.core;

import org.elasticsearch.Version;
import org.elasticsearch.common.Base64;
import org.elasticsearch.common.io.stream.ByteBufferStreamInput;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.license.core.License;
import org.elasticsearch.license.plugin.TestUtils;
import org.elasticsearch.test.ESTestCase;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.UUID;

import static org.elasticsearch.license.core.CryptUtils.encrypt;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

public class LicensesMetaDataSerializationTests extends ESTestCase {
    public void testXContentSerializationOneSignedLicense() throws Exception {
        License license = TestUtils.generateSignedLicense(TimeValue.timeValueHours(2));
        LicensesMetaData licensesMetaData = new LicensesMetaData(license);
        XContentBuilder builder = XContentFactory.jsonBuilder();
        builder.startObject("licenses");
        licensesMetaData.toXContent(builder, ToXContent.EMPTY_PARAMS);
        builder.endObject();
        byte[] serializedBytes = builder.bytes().toBytes();

        LicensesMetaData licensesMetaDataFromXContent = getLicensesMetaDataFromXContent(serializedBytes);
        assertThat(licensesMetaDataFromXContent.getLicense(), equalTo(license));
    }

    public void testXContentSerializationOneTrial() throws Exception {
        long issueDate = System.currentTimeMillis();
        License.Builder specBuilder = License.builder()
                .uid(UUID.randomUUID().toString())
                .issuedTo("customer")
                .maxNodes(5)
                .issueDate(issueDate)
                .expiryDate(issueDate + TimeValue.timeValueHours(2).getMillis());
        final License trialLicense = TrialLicense.create(specBuilder);
        LicensesMetaData licensesMetaData = new LicensesMetaData(trialLicense);
        XContentBuilder builder = XContentFactory.jsonBuilder();
        builder.startObject("licenses");
        licensesMetaData.toXContent(builder, ToXContent.EMPTY_PARAMS);
        builder.endObject();
        byte[] serializedBytes = builder.bytes().toBytes();

        LicensesMetaData licensesMetaDataFromXContent = getLicensesMetaDataFromXContent(serializedBytes);
        assertThat(licensesMetaDataFromXContent.getLicense(), equalTo(trialLicense));
    }

    public void test1xLicensesMetaDataFromXContent() throws Exception {
        License signedLicense = TestUtils.generateSignedLicense(TimeValue.timeValueHours(2));
        long issueDate = signedLicense.issueDate() - TimeValue.timeValueMillis(200).getMillis();
        License.Builder specBuilder = License.builder()
                .uid(UUID.randomUUID().toString())
                .issuedTo("customer")
                .maxNodes(5)
                .issueDate(issueDate)
                .expiryDate(issueDate + TimeValue.timeValueHours(2).getMillis());
        final License trialLicense = TrialLicense.create(specBuilder);
        // trial license
        XContentBuilder builder = XContentFactory.jsonBuilder();
        builder.startObject();
        builder.startObject("licenses");
        builder.startArray("trial_licenses");
        XContentBuilder contentBuilder = XContentFactory.contentBuilder(XContentType.JSON);
        trialLicense.toXContent(contentBuilder, new ToXContent.MapParams(Collections.singletonMap(License.LICENSE_SPEC_VIEW_MODE, "true")));
        builder.value(Base64.encodeBytes(encrypt(contentBuilder.bytes().toBytes())));
        builder.endArray();
        builder.startArray("signed_licenses");
        builder.endArray();
        builder.endObject();
        builder.endObject();
        LicensesMetaData licensesMetaDataFromXContent = getLicensesMetaDataFromXContent(builder.bytes().toBytes());
        assertThat(licensesMetaDataFromXContent.getLicense(), equalTo(trialLicense));

        // signed license
        builder = XContentFactory.jsonBuilder();
        builder.startObject();
        builder.startObject("licenses");
        builder.startArray("trial_licenses");
        builder.endArray();
        builder.startArray("signed_licenses");
        signedLicense.toXContent(builder, ToXContent.EMPTY_PARAMS);
        builder.endArray();
        builder.endObject();
        builder.endObject();
        licensesMetaDataFromXContent = getLicensesMetaDataFromXContent(builder.bytes().toBytes());
        assertThat(licensesMetaDataFromXContent.getLicense(), equalTo(signedLicense));

        // trial and signed license
        builder = XContentFactory.jsonBuilder();
        builder.startObject();
        builder.startObject("licenses");
        builder.startArray("trial_licenses");
        contentBuilder = XContentFactory.contentBuilder(XContentType.JSON);
        trialLicense.toXContent(contentBuilder, new ToXContent.MapParams(Collections.singletonMap(License.LICENSE_SPEC_VIEW_MODE, "true")));
        builder.value(Base64.encodeBytes(encrypt(contentBuilder.bytes().toBytes())));
        builder.endArray();
        builder.startArray("signed_licenses");
        signedLicense.toXContent(builder, ToXContent.EMPTY_PARAMS);
        builder.endArray();
        builder.endObject();
        builder.endObject();
        licensesMetaDataFromXContent = getLicensesMetaDataFromXContent(builder.bytes().toBytes());
        assertThat(licensesMetaDataFromXContent.getLicense(), equalTo(signedLicense));

        // license with later issue date is selected
        License signedLicenseIssuedLater = TestUtils.generateSignedLicense(TimeValue.timeValueHours(2));
        builder = XContentFactory.jsonBuilder();
        builder.startObject();
        builder.startObject("licenses");
        builder.startArray("trial_licenses");
        contentBuilder = XContentFactory.contentBuilder(XContentType.JSON);
        trialLicense.toXContent(contentBuilder, new ToXContent.MapParams(Collections.singletonMap(License.LICENSE_SPEC_VIEW_MODE, "true")));
        builder.value(Base64.encodeBytes(encrypt(contentBuilder.bytes().toBytes())));
        builder.endArray();
        builder.startArray("signed_licenses");
        signedLicense.toXContent(builder, ToXContent.EMPTY_PARAMS);
        signedLicenseIssuedLater.toXContent(builder, ToXContent.EMPTY_PARAMS);
        builder.endArray();
        builder.endObject();
        builder.endObject();
        licensesMetaDataFromXContent = getLicensesMetaDataFromXContent(builder.bytes().toBytes());
        assertThat(licensesMetaDataFromXContent.getLicense(), equalTo(signedLicenseIssuedLater));

    }

    public void test1xLicensesMetaDataFromStream() throws Exception {
        long issueDate = System.currentTimeMillis();
        License.Builder specBuilder = License.builder()
                .uid(UUID.randomUUID().toString())
                .issuedTo("customer")
                .maxNodes(5)
                .issueDate(issueDate)
                .expiryDate(issueDate + TimeValue.timeValueHours(1).getMillis());
        final License trialLicense = TrialLicense.create(specBuilder);
        // trial license
        BytesStreamOutput output = new BytesStreamOutput();
        output.writeVInt(0);
        output.writeVInt(1);
        XContentBuilder contentBuilder = XContentFactory.contentBuilder(XContentType.JSON);
        trialLicense.toXContent(contentBuilder, new ToXContent.MapParams(Collections.singletonMap(License.LICENSE_SPEC_VIEW_MODE, "true")));
        output.writeString(Base64.encodeBytes(encrypt(contentBuilder.bytes().toBytes())));
        byte[] bytes = output.bytes().toBytes();
        ByteBufferStreamInput input = new ByteBufferStreamInput(ByteBuffer.wrap(bytes));

        input.setVersion(Version.V_2_0_0_beta1);
        LicensesMetaData licensesMetaData = LicensesMetaData.PROTO.readFrom(input);
        assertThat(licensesMetaData.getLicense(), equalTo(trialLicense));

        // signed license
        License signedLicense = TestUtils.generateSignedLicense(TimeValue.timeValueHours(2));
        output = new BytesStreamOutput();
        output.writeVInt(1);
        signedLicense.writeTo(output);
        output.writeVInt(0);
        bytes = output.bytes().toBytes();
        input = new ByteBufferStreamInput(ByteBuffer.wrap(bytes));
        input.setVersion(Version.V_2_0_0_beta1);
        licensesMetaData = LicensesMetaData.PROTO.readFrom(input);
        assertThat(licensesMetaData.getLicense(), equalTo(signedLicense));
    }

    public void testLicenseTombstoneFromXContext() throws Exception {
        final XContentBuilder builder = XContentFactory.jsonBuilder();
        builder.startObject("licenses");
        builder.nullField("license");
        builder.endObject();
        LicensesMetaData metaDataFromXContent = getLicensesMetaDataFromXContent(builder.bytes().toBytes());
        assertThat(metaDataFromXContent.getLicense(), equalTo(LicensesMetaData.LICENSE_TOMBSTONE));
    }

    private static LicensesMetaData getLicensesMetaDataFromXContent(byte[] bytes) throws Exception {
        final XContentParser parser = XContentFactory.xContent(XContentType.JSON).createParser(bytes);
        parser.nextToken(); // consume null
        parser.nextToken(); // consume "licenses"
        LicensesMetaData licensesMetaDataFromXContent = LicensesMetaData.PROTO.fromXContent(parser);
        parser.nextToken(); // consume endObject
        assertThat(parser.nextToken(), nullValue());
        return licensesMetaDataFromXContent;
    }
}
