/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.job.persistence;

import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.prelert.job.CategorizerState;
import org.elasticsearch.xpack.prelert.job.DataCounts;
import org.elasticsearch.xpack.prelert.job.Job;
import org.elasticsearch.xpack.prelert.job.ModelSizeStats;
import org.elasticsearch.xpack.prelert.job.ModelSnapshot;
import org.elasticsearch.xpack.prelert.job.ModelState;
import org.elasticsearch.xpack.prelert.job.SchedulerConfig;
import org.elasticsearch.xpack.prelert.job.audit.AuditActivity;
import org.elasticsearch.xpack.prelert.job.audit.AuditMessage;
import org.elasticsearch.xpack.prelert.job.metadata.Allocation;
import org.elasticsearch.xpack.prelert.job.quantiles.Quantiles;
import org.elasticsearch.xpack.prelert.job.results.CategoryDefinition;
import org.elasticsearch.xpack.prelert.job.results.ModelDebugOutput;
import org.elasticsearch.xpack.prelert.job.results.ReservedFieldNames;
import org.elasticsearch.xpack.prelert.job.results.Result;
import org.elasticsearch.xpack.prelert.job.usage.Usage;
import org.elasticsearch.xpack.prelert.lists.ListDocument;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;


public class ElasticsearchMappingsTests extends ESTestCase {
    private void parseJson(JsonParser parser, Set<String> expected) throws IOException {
        try {
            JsonToken token = parser.nextToken();
            while (token != null && token != JsonToken.END_OBJECT) {
                switch (token) {
                    case START_OBJECT:
                        parseJson(parser, expected);
                        break;
                    case FIELD_NAME:
                        String fieldName = parser.getCurrentName();
                        expected.add(fieldName);
                        break;
                    default:
                        break;
                }
                token = parser.nextToken();
            }
        } catch (JsonParseException e) {
            fail("Cannot parse JSON: " + e);
        }
    }

    public void testReservedFields()
            throws IOException, ClassNotFoundException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        Set<String> overridden = new HashSet<>();

        // These are not reserved because they're Elasticsearch keywords, not
        // field names
        overridden.add(ElasticsearchMappings.ALL);
        overridden.add(ElasticsearchMappings.ANALYZER);
        overridden.add(ElasticsearchMappings.COPY_TO);
        overridden.add(ElasticsearchMappings.DYNAMIC);
        overridden.add(ElasticsearchMappings.ENABLED);
        overridden.add(ElasticsearchMappings.INCLUDE_IN_ALL);
        overridden.add(ElasticsearchMappings.INDEX);
        overridden.add(ElasticsearchMappings.NESTED);
        overridden.add(ElasticsearchMappings.NO);
        overridden.add(ElasticsearchMappings.PROPERTIES);
        overridden.add(ElasticsearchMappings.TYPE);
        overridden.add(ElasticsearchMappings.WHITESPACE);

        // These are not reserved because they're data types, not field names
        overridden.add(Result.TYPE.getPreferredName());
        overridden.add(AuditActivity.TYPE.getPreferredName());
        overridden.add(AuditMessage.TYPE.getPreferredName());
        overridden.add(DataCounts.TYPE.getPreferredName());
        overridden.add(CategorizerState.TYPE);
        overridden.add(CategoryDefinition.TYPE.getPreferredName());
        overridden.add(Job.TYPE);
        overridden.add(ListDocument.TYPE.getPreferredName());
        overridden.add(ModelDebugOutput.TYPE.getPreferredName());
        overridden.add(ModelState.TYPE);
        overridden.add(ModelSnapshot.TYPE.getPreferredName());
        overridden.add(ModelSizeStats.TYPE.getPreferredName());
        overridden.add(Quantiles.TYPE.getPreferredName());
        overridden.add(Usage.TYPE);

        // These are not reserved because they're in the prelert-int index, not
        // prelertresults-*
        overridden.add(ListDocument.ID.getPreferredName());
        overridden.add(ListDocument.ITEMS.getPreferredName());

        // These are not reserved because they're analyzed strings, i.e. the
        // same type as user-specified fields
        overridden.add(Job.DESCRIPTION.getPreferredName());
        overridden.add(Allocation.STATUS.getPreferredName());
        overridden.add(ModelSnapshot.DESCRIPTION.getPreferredName());
        overridden.add(SchedulerConfig.USERNAME.getPreferredName());

        Set<String> expected = new HashSet<>();

        XContentBuilder builder = ElasticsearchMappings.auditActivityMapping();
        BufferedInputStream inputStream = new BufferedInputStream(
                new ByteArrayInputStream(builder.string().getBytes(StandardCharsets.UTF_8)));
        JsonParser parser = new JsonFactory().createParser(inputStream);
        parseJson(parser, expected);

        builder = ElasticsearchMappings.auditMessageMapping();
        inputStream = new BufferedInputStream(new ByteArrayInputStream(builder.string().getBytes(StandardCharsets.UTF_8)));
        parser = new JsonFactory().createParser(inputStream);
        parseJson(parser, expected);

        builder = ElasticsearchMappings.resultsMapping(Collections.emptyList());
        inputStream = new BufferedInputStream(new ByteArrayInputStream(builder.string().getBytes(StandardCharsets.UTF_8)));
        parser = new JsonFactory().createParser(inputStream);
        parseJson(parser, expected);

        builder = ElasticsearchMappings.bucketPartitionMaxNormalizedScores();
        inputStream = new BufferedInputStream(new ByteArrayInputStream(builder.string().getBytes(StandardCharsets.UTF_8)));
        parser = new JsonFactory().createParser(inputStream);
        parseJson(parser, expected);

        builder = ElasticsearchMappings.categorizerStateMapping();
        inputStream = new BufferedInputStream(new ByteArrayInputStream(builder.string().getBytes(StandardCharsets.UTF_8)));
        parser = new JsonFactory().createParser(inputStream);
        parseJson(parser, expected);

        builder = ElasticsearchMappings.categoryDefinitionMapping();
        inputStream = new BufferedInputStream(new ByteArrayInputStream(builder.string().getBytes(StandardCharsets.UTF_8)));
        parser = new JsonFactory().createParser(inputStream);
        parseJson(parser, expected);

        builder = ElasticsearchMappings.dataCountsMapping();
        inputStream = new BufferedInputStream(new ByteArrayInputStream(builder.string().getBytes(StandardCharsets.UTF_8)));
        parser = new JsonFactory().createParser(inputStream);
        parseJson(parser, expected);

        builder = ElasticsearchMappings.modelDebugOutputMapping(null);
        inputStream = new BufferedInputStream(new ByteArrayInputStream(builder.string().getBytes(StandardCharsets.UTF_8)));
        parser = new JsonFactory().createParser(inputStream);
        parseJson(parser, expected);

        builder = ElasticsearchMappings.modelSizeStatsMapping();
        inputStream = new BufferedInputStream(new ByteArrayInputStream(builder.string().getBytes(StandardCharsets.UTF_8)));
        parser = new JsonFactory().createParser(inputStream);
        parseJson(parser, expected);

        builder = ElasticsearchMappings.modelSnapshotMapping();
        inputStream = new BufferedInputStream(new ByteArrayInputStream(builder.string().getBytes(StandardCharsets.UTF_8)));
        parser = new JsonFactory().createParser(inputStream);
        parseJson(parser, expected);

        builder = ElasticsearchMappings.modelStateMapping();
        inputStream = new BufferedInputStream(new ByteArrayInputStream(builder.string().getBytes(StandardCharsets.UTF_8)));
        parser = new JsonFactory().createParser(inputStream);
        parseJson(parser, expected);

        builder = ElasticsearchMappings.quantilesMapping();
        inputStream = new BufferedInputStream(new ByteArrayInputStream(builder.string().getBytes(StandardCharsets.UTF_8)));
        parser = new JsonFactory().createParser(inputStream);
        parseJson(parser, expected);

        builder = ElasticsearchMappings.usageMapping();
        inputStream = new BufferedInputStream(new ByteArrayInputStream(builder.string().getBytes(StandardCharsets.UTF_8)));
        parser = new JsonFactory().createParser(inputStream);
        parseJson(parser, expected);

        expected.removeAll(overridden);

        if (ReservedFieldNames.RESERVED_FIELD_NAMES.size() != expected.size()) {
            Set<String> diff = new HashSet<>(ReservedFieldNames.RESERVED_FIELD_NAMES);
            diff.removeAll(expected);
            StringBuilder errorMessage = new StringBuilder("Fields in ReservedFieldNames but not in expected: ").append(diff);

            diff = new HashSet<>(expected);
            diff.removeAll(ReservedFieldNames.RESERVED_FIELD_NAMES);
            errorMessage.append("\nFields in expected but not in ReservedFieldNames: ").append(diff);
            fail(errorMessage.toString());
        }
        assertEquals(ReservedFieldNames.RESERVED_FIELD_NAMES.size(), expected.size());

        for (String s : expected) {
            // By comparing like this the failure messages say which string is
            // missing
            String reserved = ReservedFieldNames.RESERVED_FIELD_NAMES.contains(s) ? s : null;
            assertEquals(s, reserved);
        }
    }

}
