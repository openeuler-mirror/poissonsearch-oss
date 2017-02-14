/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.integration;

import org.apache.http.entity.StringEntity;
import org.elasticsearch.client.Response;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.test.rest.ESRestTestCase;
import org.elasticsearch.xpack.ml.MachineLearning;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.common.xcontent.XContentType.JSON;

public class MlBasicMultiNodeIT extends ESRestTestCase {

    public void testBasics() throws Exception {
        String jobId = "foo";
        createFarequoteJob(jobId);

        try {
            Response response = client().performRequest("post", MachineLearning.BASE_PATH + "anomaly_detectors/" + jobId + "/_open");
            assertEquals(200, response.getStatusLine().getStatusCode());
            assertEquals(Collections.singletonMap("opened", true), responseEntityToMap(response));
        } catch (Exception e) {
            Response response = client().performRequest("get", "/_cluster/nodes/hotthreads");
            logger.warn("hot_threads:\n" + responseEntityToString(response));
            throw e;
        }

        String postData =
                "{\"airline\":\"AAL\",\"responsetime\":\"132.2046\",\"sourcetype\":\"farequote\",\"time\":\"1403481600\"}\n" +
                "{\"airline\":\"JZA\",\"responsetime\":\"990.4628\",\"sourcetype\":\"farequote\",\"time\":\"1403481700\"}";
        Response response = client().performRequest("post", MachineLearning.BASE_PATH + "anomaly_detectors/" + jobId + "/_data",
                Collections.emptyMap(), new StringEntity(postData));
        assertEquals(202, response.getStatusLine().getStatusCode());
        Map<String, Object> responseBody = responseEntityToMap(response);
        assertEquals(2, responseBody.get("processed_record_count"));
        assertEquals(4, responseBody.get("processed_field_count"));
        assertEquals(177, responseBody.get("input_bytes"));
        assertEquals(6, responseBody.get("input_field_count"));
        assertEquals(0, responseBody.get("invalid_date_count"));
        assertEquals(0, responseBody.get("missing_field_count"));
        assertEquals(0, responseBody.get("out_of_order_timestamp_count"));
        assertEquals(1403481600000L, responseBody.get("earliest_record_timestamp"));
        assertEquals(1403481700000L, responseBody.get("latest_record_timestamp"));

        response = client().performRequest("post", MachineLearning.BASE_PATH + "anomaly_detectors/" + jobId + "/_flush");
        assertEquals(200, response.getStatusLine().getStatusCode());
        assertEquals(Collections.singletonMap("flushed", true), responseEntityToMap(response));

        response = client().performRequest("post", MachineLearning.BASE_PATH + "anomaly_detectors/" + jobId + "/_close");
        assertEquals(200, response.getStatusLine().getStatusCode());
        assertEquals(Collections.singletonMap("closed", true), responseEntityToMap(response));

        response = client().performRequest("get", MachineLearning.BASE_PATH + "anomaly_detectors/" + jobId + "/_stats");
        assertEquals(200, response.getStatusLine().getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> dataCountsDoc = (Map<String, Object>)
                ((Map)((List) responseEntityToMap(response).get("jobs")).get(0)).get("data_counts");
        assertEquals(2, dataCountsDoc.get("processed_record_count"));
        assertEquals(4, dataCountsDoc.get("processed_field_count"));
        assertEquals(177, dataCountsDoc.get("input_bytes"));
        assertEquals(6, dataCountsDoc.get("input_field_count"));
        assertEquals(0, dataCountsDoc.get("invalid_date_count"));
        assertEquals(0, dataCountsDoc.get("missing_field_count"));
        assertEquals(0, dataCountsDoc.get("out_of_order_timestamp_count"));
        assertEquals(1403481600000L, dataCountsDoc.get("earliest_record_timestamp"));
        assertEquals(1403481700000L, dataCountsDoc.get("latest_record_timestamp"));

        response = client().performRequest("delete", MachineLearning.BASE_PATH + "anomaly_detectors/" + jobId);
        assertEquals(200, response.getStatusLine().getStatusCode());
    }

    private Response createFarequoteJob(String jobId) throws Exception {
        XContentBuilder xContentBuilder = jsonBuilder();
        xContentBuilder.startObject();
        xContentBuilder.field("job_id", jobId);
        xContentBuilder.field("description", "Analysis of response time by airline");

        xContentBuilder.startObject("analysis_config");
        xContentBuilder.field("bucket_span", 3600);
        xContentBuilder.startArray("detectors");
        xContentBuilder.startObject();
        xContentBuilder.field("function", "metric");
        xContentBuilder.field("field_name", "responsetime");
        xContentBuilder.field("by_field_name", "airline");
        xContentBuilder.endObject();
        xContentBuilder.endArray();
        xContentBuilder.endObject();

        xContentBuilder.startObject("data_description");
        xContentBuilder.field("format", "JSON");
        xContentBuilder.field("time_field", "time");
        xContentBuilder.field("time_format", "epoch");
        xContentBuilder.endObject();
        xContentBuilder.endObject();

        return client().performRequest("put", MachineLearning.BASE_PATH + "anomaly_detectors/" + jobId,
                Collections.emptyMap(), new StringEntity(xContentBuilder.string()));
    }

    private static Map<String, Object> responseEntityToMap(Response response) throws Exception {
        return XContentHelper.convertToMap(JSON.xContent(), response.getEntity().getContent(), false);
    }

    private static String responseEntityToString(Response response) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}
