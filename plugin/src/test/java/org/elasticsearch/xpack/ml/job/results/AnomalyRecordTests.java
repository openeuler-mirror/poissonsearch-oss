/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.job.results;

import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.test.AbstractSerializingTestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class AnomalyRecordTests extends AbstractSerializingTestCase<AnomalyRecord> {

    @Override
    protected AnomalyRecord createTestInstance() {
        return createTestInstance("foo");
    }

    public AnomalyRecord createTestInstance(String jobId) {
        AnomalyRecord anomalyRecord = new AnomalyRecord(jobId, new Date(randomNonNegativeLong()), randomNonNegativeLong());
        anomalyRecord.setActual(Collections.singletonList(randomDouble()));
        anomalyRecord.setTypical(Collections.singletonList(randomDouble()));
        anomalyRecord.setProbability(randomDouble());
        anomalyRecord.setRecordScore(randomDouble());
        anomalyRecord.setInitialRecordScore(randomDouble());
        anomalyRecord.setInterim(randomBoolean());
        if (randomBoolean()) {
            anomalyRecord.setFieldName(randomAlphaOfLength(12));
        }
        if (randomBoolean()) {
            anomalyRecord.setByFieldName(randomAlphaOfLength(12));
            anomalyRecord.setByFieldValue(randomAlphaOfLength(12));
        }
        if (randomBoolean()) {
            anomalyRecord.setPartitionFieldName(randomAlphaOfLength(12));
            anomalyRecord.setPartitionFieldValue(randomAlphaOfLength(12));
        }
        if (randomBoolean()) {
            anomalyRecord.setOverFieldName(randomAlphaOfLength(12));
            anomalyRecord.setOverFieldValue(randomAlphaOfLength(12));
        }
        anomalyRecord.setFunction(randomAlphaOfLengthBetween(5, 20));
        anomalyRecord.setFunctionDescription(randomAlphaOfLengthBetween(5, 20));
        if (randomBoolean()) {
            anomalyRecord.setCorrelatedByFieldValue(randomAlphaOfLength(16));
        }
        if (randomBoolean()) {
            int count = randomIntBetween(0, 9);
            List<Influence>  influences = new ArrayList<>();
            for (int i=0; i<count; i++) {
                influences.add(new Influence(randomAlphaOfLength(8), Collections.singletonList(randomAlphaOfLengthBetween(1, 28))));
            }
            anomalyRecord.setInfluencers(influences);
        }
        if (randomBoolean()) {
            int count = randomIntBetween(0, 9);
            List<AnomalyCause>  causes = new ArrayList<>();
            for (int i=0; i<count; i++) {
                causes.add(new AnomalyCauseTests().createTestInstance());
            }
            anomalyRecord.setCauses(causes);
        }

        return anomalyRecord;
    }

    @Override
    protected Writeable.Reader<AnomalyRecord> instanceReader() {
        return AnomalyRecord::new;
    }

    @Override
    protected AnomalyRecord doParseInstance(XContentParser parser) {
        return AnomalyRecord.PARSER.apply(parser, null);
    }

    @SuppressWarnings("unchecked")
    public void testToXContentIncludesInputFields() throws IOException {
        AnomalyRecord record = createTestInstance();
        record.setByFieldName("byfn");
        record.setByFieldValue("byfv");
        record.setOverFieldName("overfn");
        record.setOverFieldValue("overfv");
        record.setPartitionFieldName("partfn");
        record.setPartitionFieldValue("partfv");

        Influence influence1 = new Influence("inffn", Arrays.asList("inffv1", "inffv2"));
        Influence influence2 = new Influence("inffn", Arrays.asList("inffv1", "inffv2"));
        record.setInfluencers(Arrays.asList(influence1, influence2));

        BytesReference bytes = XContentHelper.toXContent(record, XContentType.JSON, false);
        XContentParser parser = createParser(XContentType.JSON.xContent(), bytes);
        Map<String, Object> map = parser.map();
        List<String> serialisedByFieldValues = (List<String>) map.get(record.getByFieldName());
        assertEquals(Collections.singletonList(record.getByFieldValue()), serialisedByFieldValues);
        List<String> serialisedOverFieldValues = (List<String>) map.get(record.getOverFieldName());
        assertEquals(Collections.singletonList(record.getOverFieldValue()), serialisedOverFieldValues);
        List<String> serialisedPartFieldValues = (List<String>) map.get(record.getPartitionFieldName());
        assertEquals(Collections.singletonList(record.getPartitionFieldValue()), serialisedPartFieldValues);

        List<String> serialisedInfFieldValues1 = (List<String>) map.get(influence1.getInfluencerFieldName());
        assertEquals(influence1.getInfluencerFieldValues(), serialisedInfFieldValues1);
        List<String> serialisedInfFieldValues2 = (List<String>) map.get(influence2.getInfluencerFieldName());
        assertEquals(influence2.getInfluencerFieldValues(), serialisedInfFieldValues2);
    }

    public void testToXContentOrdersDuplicateInputFields() throws IOException {
        AnomalyRecord record = createTestInstance();
        record.setByFieldName("car-make");
        record.setByFieldValue("ford");
        record.setOverFieldName("number-of-wheels");
        record.setOverFieldValue("4");
        record.setPartitionFieldName("spoiler");
        record.setPartitionFieldValue("yes");

        Influence influence1 = new Influence("car-make", Collections.singletonList("VW"));
        Influence influence2 = new Influence("number-of-wheels", Collections.singletonList("18"));
        Influence influence3 = new Influence("spoiler", Collections.singletonList("no"));
        record.setInfluencers(Arrays.asList(influence1, influence2, influence3));

        // influencer fields with the same name as a by/over/partitiion field
        // come second in the list
        BytesReference bytes = XContentHelper.toXContent(record, XContentType.JSON, false);
        XContentParser parser = createParser(XContentType.JSON.xContent(), bytes);
        Map<String, Object> map = parser.map();
        List<String> serialisedCarMakeFieldValues = (List<String>) map.get("car-make");
        assertEquals(Arrays.asList("ford", "VW"), serialisedCarMakeFieldValues);
        List<String> serialisedNumberOfWheelsFieldValues = (List<String>) map.get("number-of-wheels");
        assertEquals(Arrays.asList("4", "18"), serialisedNumberOfWheelsFieldValues);
        List<String> serialisedSpoilerFieldValues = (List<String>) map.get("spoiler");
        assertEquals(Arrays.asList("yes", "no"), serialisedSpoilerFieldValues);
    }

    @SuppressWarnings("unchecked")
    public void testToXContentDoesNotIncludesReservedWordInputFields() throws IOException {
        AnomalyRecord record = createTestInstance();
        record.setByFieldName(AnomalyRecord.BUCKET_SPAN.getPreferredName());
        record.setByFieldValue("bar");

        BytesReference bytes = XContentHelper.toXContent(record, XContentType.JSON, false);
        XContentParser parser = createParser(XContentType.JSON.xContent(), bytes);
        Object value = parser.map().get(AnomalyRecord.BUCKET_SPAN.getPreferredName());
        assertNotEquals("bar", value);
        assertEquals((Long)record.getBucketSpan(), (Long)value);
    }

    public void testId() {
        AnomalyRecord record = new AnomalyRecord("test-job", new Date(1000), 60L);
        String byFieldValue = null;
        String overFieldValue = null;
        String partitionFieldValue = null;

        int valuesHash = Objects.hash(byFieldValue, overFieldValue, partitionFieldValue);
        assertEquals("test-job_record_1000_60_0_" + valuesHash + "_0", record.getId());

        int length = 0;
        if (randomBoolean()) {
            byFieldValue = randomAlphaOfLength(10);
            length += byFieldValue.length();
            record.setByFieldValue(byFieldValue);
        }
        if (randomBoolean()) {
            overFieldValue = randomAlphaOfLength(10);
            length += overFieldValue.length();
            record.setOverFieldValue(overFieldValue);
        }
        if (randomBoolean()) {
            partitionFieldValue = randomAlphaOfLength(10);
            length += partitionFieldValue.length();
            record.setPartitionFieldValue(partitionFieldValue);
        }

        valuesHash = Objects.hash(byFieldValue, overFieldValue, partitionFieldValue);
        assertEquals("test-job_record_1000_60_0_" + valuesHash + "_" + length, record.getId());
    }

    public void testParsingv54WithSequenceNumField() throws IOException {
        AnomalyRecord record = createTestInstance();
        XContentBuilder builder = XContentFactory.contentBuilder(XContentType.JSON);
        builder.startObject();
        builder.field(AnomalyRecord.SEQUENCE_NUM.getPreferredName(), 1);
        record.innerToXContent(builder, ToXContent.EMPTY_PARAMS);
        builder.endObject();
        XContentParser parser = createParser(builder);
        AnomalyRecord serialised = parseInstance(parser);
        assertEquals(record, serialised);
    }
}
