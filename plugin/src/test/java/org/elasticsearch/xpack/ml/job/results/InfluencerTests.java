/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.job.results;

import org.elasticsearch.common.io.stream.Writeable.Reader;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.xpack.ml.support.AbstractSerializingTestCase;

import java.io.IOException;
import java.util.Date;
import java.util.Objects;

public class InfluencerTests extends AbstractSerializingTestCase<Influencer> {

    public Influencer createTestInstance(String jobId) {
        Influencer influencer = new Influencer(jobId, randomAlphaOfLengthBetween(1, 20), randomAlphaOfLengthBetween(1, 20),
                new Date(randomNonNegativeLong()), randomNonNegativeLong());
        influencer.setInterim(randomBoolean());
        influencer.setInfluencerScore(randomDouble());
        influencer.setInitialInfluencerScore(randomDouble());
        influencer.setProbability(randomDouble());
        return influencer;
    }
    @Override
    protected Influencer createTestInstance() {
        return createTestInstance(randomAlphaOfLengthBetween(1, 20));
    }

    @Override
    protected Reader<Influencer> instanceReader() {
        return Influencer::new;
    }

    @Override
    protected Influencer parseInstance(XContentParser parser) {
        return Influencer.PARSER.apply(parser, null);
    }

    public void testToXContentIncludesNameValueField() throws IOException {
        Influencer influencer = createTestInstance("foo");
        XContentBuilder builder = toXContent(influencer, XContentType.JSON);
        XContentParser parser = createParser(builder);
        String serialisedFieldName = (String) parser.map().get(influencer.getInfluencerFieldName());
        assertNotNull(serialisedFieldName);
        assertEquals(influencer.getInfluencerFieldValue(), serialisedFieldName);
    }

    public void testToXContentDoesNotIncludeNameValueFieldWhenReservedWord() throws IOException {
        Influencer influencer = new Influencer("foo", Influencer.INFLUENCER_SCORE.getPreferredName(), "bar", new Date(), 300L);
        XContentBuilder builder = toXContent(influencer, XContentType.JSON);
        XContentParser parser = createParser(builder);
        Object serialisedFieldValue = parser.map().get(Influencer.INFLUENCER_SCORE.getPreferredName());
        assertNotNull(serialisedFieldValue);
        assertNotEquals("bar", serialisedFieldValue);
    }

    public void testId() {
        String influencerFieldValue = "wopr";
        Influencer influencer = new Influencer("job-foo", "host", influencerFieldValue, new Date(1000), 300L);
        int valueHash = Objects.hashCode(influencerFieldValue);
        assertEquals("job-foo_influencer_1000_300_host_" + valueHash + "_" + influencerFieldValue.length(), influencer.getId());
    }

    public void testParsingv54WithSequenceNumField() throws IOException {
        Influencer influencer = createTestInstance();
        XContentBuilder builder = XContentFactory.contentBuilder(XContentType.JSON);
        builder.startObject();
        builder.field(Influencer.SEQUENCE_NUM.getPreferredName(), 1);
        influencer.innerToXContent(builder, ToXContent.EMPTY_PARAMS);
        builder.endObject();
        XContentParser parser = createParser(builder);
        Influencer serialised = parseInstance(parser);
        assertEquals(influencer, serialised);
    }
}
