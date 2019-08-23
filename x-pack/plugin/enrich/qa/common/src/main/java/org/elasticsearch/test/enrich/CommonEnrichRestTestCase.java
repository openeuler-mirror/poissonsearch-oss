/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.test.enrich;

import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.test.rest.ESRestTestCase;
import org.junit.After;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.hamcrest.Matchers.equalTo;

public abstract class CommonEnrichRestTestCase extends ESRestTestCase {

    @After
    private void deletePolicies() throws Exception {
        Map<String, Object> responseMap = toMap(adminClient().performRequest(new Request("GET", "/_enrich/policy")));
        @SuppressWarnings("unchecked")
        List<Map<?,?>> policies = (List<Map<?,?>>) responseMap.get("policies");

        for (Map<?, ?> entry: policies) {
            client().performRequest(new Request("DELETE", "/_enrich/policy/" + XContentMapValues.extractValue("exact_match.name", entry)));
        }
    }

    private void setupGenericLifecycleTest(boolean deletePipeilne) throws Exception {
        // Create the policy:
        Request putPolicyRequest = new Request("PUT", "/_enrich/policy/my_policy");
        putPolicyRequest.setJsonEntity(generatePolicySource("my-source-index"));
        assertOK(client().performRequest(putPolicyRequest));

        // Add entry to source index and then refresh:
        Request indexRequest = new Request("PUT", "/my-source-index/_doc/elastic.co");
        indexRequest.setJsonEntity("{\"host\": \"elastic.co\",\"globalRank\": 25,\"tldRank\": 7,\"tld\": \"co\"}");
        assertOK(client().performRequest(indexRequest));
        Request refreshRequest = new Request("POST", "/my-source-index/_refresh");
        assertOK(client().performRequest(refreshRequest));

        // Execute the policy:
        Request executePolicyRequest = new Request("POST", "/_enrich/policy/my_policy/_execute");
        assertOK(client().performRequest(executePolicyRequest));

        // Create pipeline
        Request putPipelineRequest = new Request("PUT", "/_ingest/pipeline/my_pipeline");
        putPipelineRequest.setJsonEntity("{\"processors\":[" +
            "{\"enrich\":{\"policy_name\":\"my_policy\",\"field\":\"host\",\"target_field\":\"entry\"}}" +
            "]}");
        assertOK(client().performRequest(putPipelineRequest));

        // Index document using pipeline with enrich processor:
        indexRequest = new Request("PUT", "/my-index/_doc/1");
        indexRequest.addParameter("pipeline", "my_pipeline");
        indexRequest.setJsonEntity("{\"host\": \"elastic.co\"}");
        assertOK(client().performRequest(indexRequest));

        // Check if document has been enriched
        Request getRequest = new Request("GET", "/my-index/_doc/1");
        Map<String, Object> response = toMap(client().performRequest(getRequest));
        Map<?, ?> _source = (Map<?, ?>) ((Map<?, ?>) response.get("_source")).get("entry");
        assertThat(_source.size(), equalTo(4));
        assertThat(_source.get("host"), equalTo("elastic.co"));
        assertThat(_source.get("tld"), equalTo("co"));
        assertThat(_source.get("globalRank"), equalTo(25));
        assertThat(_source.get("tldRank"), equalTo(7));

        if (deletePipeilne) {
            // delete the pipeline so the policies can be deleted
            client().performRequest(new Request("DELETE", "/_ingest/pipeline/my_pipeline"));
        }
    }

    public void testBasicFlow() throws Exception {
        setupGenericLifecycleTest(true);
    }

    public void testImmutablePolicy() throws IOException {
        Request putPolicyRequest = new Request("PUT", "/_enrich/policy/my_policy");
        putPolicyRequest.setJsonEntity(generatePolicySource("my-source-index"));
        assertOK(client().performRequest(putPolicyRequest));

        ResponseException exc = expectThrows(ResponseException.class, () -> client().performRequest(putPolicyRequest));
        assertTrue(exc.getMessage().contains("policy [my_policy] already exists"));
    }

    public void testDeleteIsCaseSensitive() throws Exception {
        Request putPolicyRequest = new Request("PUT", "/_enrich/policy/my_policy");
        putPolicyRequest.setJsonEntity(generatePolicySource("my-source-index"));
        assertOK(client().performRequest(putPolicyRequest));

        ResponseException exc = expectThrows(ResponseException.class,
            () -> client().performRequest(new Request("DELETE", "/_enrich/policy/MY_POLICY")));
        assertTrue(exc.getMessage().contains("policy [MY_POLICY] not found"));
    }

    public void testDeleteExistingPipeline() throws Exception {
        // lets not delete the pipeline at first, to test the failure
        setupGenericLifecycleTest(false);

        Request putPipelineRequest = new Request("PUT", "/_ingest/pipeline/another_pipeline");
        putPipelineRequest.setJsonEntity("{\"processors\":[" +
            "{\"enrich\":{\"policy_name\":\"my_policy\",\"field\":\"host\",\"target_field\":\"entry\"}}" +
            "]}");
        assertOK(client().performRequest(putPipelineRequest));

        ResponseException exc = expectThrows(ResponseException.class,
            () -> client().performRequest(new Request("DELETE", "/_enrich/policy/my_policy")));
        assertTrue(exc.getMessage().contains("Could not delete policy [my_policy] because" +
            " a pipeline is referencing it [my_pipeline, another_pipeline]"));

        // delete the pipelines so the policies can be deleted
        client().performRequest(new Request("DELETE", "/_ingest/pipeline/my_pipeline"));
        client().performRequest(new Request("DELETE", "/_ingest/pipeline/another_pipeline"));

        // verify the delete did not happen
        Request getRequest = new Request("GET", "/_enrich/policy/my_policy");
        assertOK(client().performRequest(getRequest));
    }

    public static String generatePolicySource(String index) throws IOException {
        XContentBuilder source = jsonBuilder().startObject().startObject("exact_match");
        {
            source.field("indices", index);
            if (randomBoolean()) {
                source.field("query", QueryBuilders.matchAllQuery());
            }
            source.field("match_field", "host");
            source.field("enrich_fields", new String[] {"globalRank", "tldRank", "tld"});
        }
        source.endObject().endObject();
        return Strings.toString(source);
    }

    private static Map<String, Object> toMap(Response response) throws IOException {
        return toMap(EntityUtils.toString(response.getEntity()));
    }

    private static Map<String, Object> toMap(String response) {
        return XContentHelper.convertToMap(JsonXContent.jsonXContent, response, false);
    }
}
