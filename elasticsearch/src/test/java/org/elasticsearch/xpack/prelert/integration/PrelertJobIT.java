/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.integration;

import org.apache.http.entity.StringEntity;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.test.rest.ESRestTestCase;
import org.elasticsearch.xpack.prelert.PrelertPlugin;
import org.junit.After;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.elasticsearch.xpack.prelert.integration.ScheduledJobIT.clearPrelertMetadata;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.isEmptyString;
import static org.hamcrest.Matchers.not;

public class PrelertJobIT extends ESRestTestCase {

    private static final String RESULT_MAPPING = "{ \"mappings\": {\"result\": { \"properties\": { " +
            "\"result_type\": { \"type\" : \"keyword\" }," +
            "\"timestamp\": { \"type\" : \"date\" }, " +
            "\"anomaly_score\": { \"type\" : \"double\" }, " +
            "\"normalized_probability\": { \"type\" : \"double\" }, " +
            "\"over_field_value\": { \"type\" : \"keyword\" }, " +
            "\"partition_field_value\": { \"type\" : \"keyword\" }, " +
            "\"by_field_value\": { \"type\" : \"keyword\" }, " +
            "\"field_name\": { \"type\" : \"keyword\" }, " +
            "\"function\": { \"type\" : \"keyword\" } " +
            "} } } }";

    public void testPutJob_GivenFarequoteConfig() throws Exception {
        Response response = createFarequoteJob();

        assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
        String responseAsString = responseEntityToString(response);
        assertThat(responseAsString, containsString("\"job_id\":\"farequote\""));
    }

    public void testGetJob_GivenNoSuchJob() throws Exception {
        ResponseException e = expectThrows(ResponseException.class,
                () -> client().performRequest("get", PrelertPlugin.BASE_PATH + "jobs/non-existing-job/_stats"));

        assertThat(e.getResponse().getStatusLine().getStatusCode(), equalTo(404));
        assertThat(e.getMessage(), containsString("Could not find requested jobs"));
    }

    public void testGetJob_GivenJobExists() throws Exception {
        createFarequoteJob();

        Response response = client().performRequest("get", PrelertPlugin.BASE_PATH + "jobs/farequote/_stats");

        assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
        String responseAsString = responseEntityToString(response);
        assertThat(responseAsString, containsString("\"count\":1"));
        assertThat(responseAsString, containsString("\"job_id\":\"farequote\""));
    }

    public void testGetJobs_GivenNegativeFrom() throws Exception {
        ResponseException e = expectThrows(ResponseException.class,
                () -> client().performRequest("get", PrelertPlugin.BASE_PATH + "jobs/_stats?from=-1"));

        assertThat(e.getResponse().getStatusLine().getStatusCode(), equalTo(400));
        assertThat(e.getMessage(), containsString("\"reason\":\"Parameter [from] cannot be < 0\""));
    }

    public void testGetJobs_GivenNegativeSize() throws Exception {
        ResponseException e = expectThrows(ResponseException.class,
                () -> client().performRequest("get", PrelertPlugin.BASE_PATH + "jobs/_stats?size=-1"));

        assertThat(e.getResponse().getStatusLine().getStatusCode(), equalTo(400));
        assertThat(e.getMessage(), containsString("\"reason\":\"Parameter [size] cannot be < 0\""));
    }

    public void testGetJobs_GivenFromAndSizeSumTo10001() throws Exception {
        ResponseException e = expectThrows(ResponseException.class,
                () -> client().performRequest("get", PrelertPlugin.BASE_PATH + "jobs/_stats?from=1000&size=11001"));

        assertThat(e.getResponse().getStatusLine().getStatusCode(), equalTo(400));
        assertThat(e.getMessage(), containsString("\"reason\":\"The sum of parameters [from] and [size] cannot be higher than 10000."));
    }

    public void testGetJobs_GivenSingleJob() throws Exception {
        createFarequoteJob();

        Response response = client().performRequest("get", PrelertPlugin.BASE_PATH + "jobs/_stats");

        assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
        String responseAsString = responseEntityToString(response);
        assertThat(responseAsString, containsString("\"count\":1"));
        assertThat(responseAsString, containsString("\"job_id\":\"farequote\""));
    }

    public void testGetJobs_GivenMultipleJobs() throws Exception {
        createFarequoteJob("farequote_1");
        createFarequoteJob("farequote_2");
        createFarequoteJob("farequote_3");

        Response response = client().performRequest("get", PrelertPlugin.BASE_PATH + "jobs/_stats");

        assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
        String responseAsString = responseEntityToString(response);
        assertThat(responseAsString, containsString("\"count\":3"));
        assertThat(responseAsString, containsString("\"job_id\":\"farequote_1\""));
        assertThat(responseAsString, containsString("\"job_id\":\"farequote_2\""));
        assertThat(responseAsString, containsString("\"job_id\":\"farequote_3\""));
    }

    public void testGetJobs_GivenMultipleJobsAndFromIsOne() throws Exception {
        createFarequoteJob("farequote_1");
        createFarequoteJob("farequote_2");
        createFarequoteJob("farequote_3");

        Response response = client().performRequest("get", PrelertPlugin.BASE_PATH + "jobs/_stats?from=1");

        assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
        String responseAsString = responseEntityToString(response);
        assertThat(responseAsString, containsString("\"count\":3"));
        assertThat(responseAsString, not(containsString("\"job_id\":\"farequote_1\"")));
        assertThat(responseAsString, containsString("\"job_id\":\"farequote_2\""));
        assertThat(responseAsString, containsString("\"job_id\":\"farequote_3\""));
    }

    public void testGetJobs_GivenMultipleJobsAndSizeIsOne() throws Exception {
        createFarequoteJob("farequote_1");
        createFarequoteJob("farequote_2");
        createFarequoteJob("farequote_3");

        Response response = client().performRequest("get", PrelertPlugin.BASE_PATH + "jobs/_stats?size=1");

        assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
        String responseAsString = responseEntityToString(response);
        assertThat(responseAsString, containsString("\"count\":3"));
        assertThat(responseAsString, containsString("\"job_id\":\"farequote_1\""));
        assertThat(responseAsString, not(containsString("\"job_id\":\"farequote_2\"")));
        assertThat(responseAsString, not(containsString("\"job_id\":\"farequote_3\"")));
    }

    public void testGetJobs_GivenMultipleJobsAndFromIsOneAndSizeIsOne() throws Exception {
        createFarequoteJob("farequote_1");
        createFarequoteJob("farequote_2");
        createFarequoteJob("farequote_3");

        Response response = client().performRequest("get", PrelertPlugin.BASE_PATH + "jobs/_stats?from=1&size=1");

        assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
        String responseAsString = responseEntityToString(response);
        assertThat(responseAsString, containsString("\"count\":3"));
        assertThat(responseAsString, not(containsString("\"job_id\":\"farequote_1\"")));
        assertThat(responseAsString, containsString("\"job_id\":\"farequote_2\""));
        assertThat(responseAsString, not(containsString("\"job_id\":\"farequote_3\"")));
    }

    private Response createFarequoteJob() throws Exception {
        return createFarequoteJob("farequote");
    }

    private Response createFarequoteJob(String jobId) throws Exception {
        String job = "{\n" + "    \"job_id\":\"" + jobId + "\",\n" + "    \"description\":\"Analysis of response time by airline\",\n"
                + "    \"analysis_config\" : {\n" + "        \"bucket_span\":3600,\n"
                + "        \"detectors\" :[{\"function\":\"metric\",\"field_name\":\"responsetime\",\"by_field_name\":\"airline\"}]\n"
                + "    },\n" + "    \"data_description\" : {\n" + "        \"field_delimiter\":\",\",\n" + "        " +
                "\"time_field\":\"time\",\n"
                + "        \"time_format\":\"yyyy-MM-dd HH:mm:ssX\"\n" + "    }\n" + "}";

        return client().performRequest("put", PrelertPlugin.BASE_PATH + "jobs", Collections.emptyMap(), new StringEntity(job));
    }

    public void testGetBucketResults() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("start", "1200"); // inclusive
        params.put("end", "1400"); // exclusive

        ResponseException e = expectThrows(ResponseException.class,
                () -> client().performRequest("get", PrelertPlugin.BASE_PATH + "results/1/buckets", params));
        assertThat(e.getResponse().getStatusLine().getStatusCode(), equalTo(404));
        assertThat(e.getMessage(), containsString("No known job with id '1'"));

        addBucketResult("1", "1234", 1);
        addBucketResult("1", "1235", 1);
        addBucketResult("1", "1236", 1);
        Response response = client().performRequest("get", PrelertPlugin.BASE_PATH + "results/1/buckets", params);
        assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
        String responseAsString = responseEntityToString(response);
        assertThat(responseAsString, containsString("\"count\":3"));

        params.put("end", "1235");
        response = client().performRequest("get", PrelertPlugin.BASE_PATH + "results/1/buckets", params);
        assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
        responseAsString = responseEntityToString(response);
        assertThat(responseAsString, containsString("\"count\":1"));

        e = expectThrows(ResponseException.class, () -> client().performRequest("get", PrelertPlugin.BASE_PATH + "results/2/buckets/1234"));
        assertThat(e.getResponse().getStatusLine().getStatusCode(), equalTo(404));
        assertThat(e.getMessage(), containsString("No known job with id '2'"));

        e = expectThrows(ResponseException.class, () -> client().performRequest("get", PrelertPlugin.BASE_PATH + "results/1/buckets/1"));
        assertThat(e.getResponse().getStatusLine().getStatusCode(), equalTo(404));

        response = client().performRequest("get", PrelertPlugin.BASE_PATH + "results/1/buckets/1234");
        assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
        responseAsString = responseEntityToString(response);
        assertThat(responseAsString, not(isEmptyString()));
    }

    public void testGetRecordResults() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("start", "1200"); // inclusive
        params.put("end", "1400"); // exclusive

        ResponseException e = expectThrows(ResponseException.class,
                () -> client().performRequest("get", PrelertPlugin.BASE_PATH + "results/1/records", params));
        assertThat(e.getResponse().getStatusLine().getStatusCode(), equalTo(404));
        assertThat(e.getMessage(), containsString("No known job with id '1'"));

        addRecordResult("1", "1234");
        addRecordResult("1", "1235");
        addRecordResult("1", "1236");
        Response response = client().performRequest("get", PrelertPlugin.BASE_PATH + "results/1/records", params);
        assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
        String responseAsString = responseEntityToString(response);
        assertThat(responseAsString, containsString("\"count\":3"));

        params.put("end", "1235");
        response = client().performRequest("get", PrelertPlugin.BASE_PATH + "results/1/records", params);
        assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
        responseAsString = responseEntityToString(response);
        assertThat(responseAsString, containsString("\"count\":1"));
    }

    private Response addBucketResult(String jobId, String timestamp, long bucketSpan) throws Exception {
        try {
            client().performRequest("put", "prelertresults-" + jobId, Collections.emptyMap(), new StringEntity(RESULT_MAPPING));
        } catch (ResponseException e) {
            // it is ok: the index already exists
            assertThat(e.getMessage(), containsString("index_already_exists_exception"));
            assertThat(e.getResponse().getStatusLine().getStatusCode(), equalTo(400));
        }

        String bucketResult = String.format(Locale.ROOT,
                "{\"job_id\":\"%s\", \"timestamp\": \"%s\", \"result_type\":\"bucket\", \"bucket_span\": \"%s\"}",
                jobId, timestamp, bucketSpan);
        String id = String.format(Locale.ROOT,
                "%s_%s_%s", jobId, timestamp, bucketSpan);
        return client().performRequest("put", "prelertresults-" + jobId + "/result/" + id,
                Collections.singletonMap("refresh", "true"), new StringEntity(bucketResult));
    }

    private Response addRecordResult(String jobId, String timestamp) throws Exception {
        try {
            client().performRequest("put", "prelertresults-" + jobId, Collections.emptyMap(), new StringEntity(RESULT_MAPPING));
        } catch (ResponseException e) {
            // it is ok: the index already exists
            assertThat(e.getMessage(), containsString("index_already_exists_exception"));
            assertThat(e.getResponse().getStatusLine().getStatusCode(), equalTo(400));
        }

        String bucketResult =
                String.format(Locale.ROOT, "{\"job_id\":\"%s\", \"timestamp\": \"%s\", \"result_type\":\"record\"}", jobId, timestamp);
        return client().performRequest("put", "prelertresults-" + jobId + "/result/" + timestamp,
                Collections.singletonMap("refresh", "true"), new StringEntity(bucketResult));
    }

    private static String responseEntityToString(Response response) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    @After
    public void clearPrelertState() throws IOException {
        clearPrelertMetadata(adminClient());
    }
}
