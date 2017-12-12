/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.qa.sql.rest;

import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.test.NotEqualMessageBuilder;
import org.elasticsearch.test.rest.ESRestTestCase;
import org.elasticsearch.xpack.qa.sql.ErrorsTestCase;
import org.hamcrest.Matcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.Collections.unmodifiableMap;
import static org.hamcrest.Matchers.containsString;

/**
 * Integration test for the rest sql action. The one that speaks json directly to a
 * user rather than to the JDBC driver or CLI.
 */
public abstract class RestSqlTestCase extends ESRestTestCase implements ErrorsTestCase {
    /**
     * Builds that map that is returned in the header for each column.
     */
    public static Map<String, Object> columnInfo(String name, String type) {
        Map<String, Object> column = new HashMap<>();
        column.put("name", name);
        column.put("type", type);
        return unmodifiableMap(column);
    }

    public void testBasicQuery() throws IOException {
        StringBuilder bulk = new StringBuilder();
        bulk.append("{\"index\":{\"_id\":\"1\"}}\n");
        bulk.append("{\"test\":\"test\"}\n");
        bulk.append("{\"index\":{\"_id\":\"2\"}}\n");
        bulk.append("{\"test\":\"test\"}\n");
        client().performRequest("POST", "/test/test/_bulk", singletonMap("refresh", "true"),
                new StringEntity(bulk.toString(), ContentType.APPLICATION_JSON));

        Map<String, Object> expected = new HashMap<>();
        expected.put("columns", singletonList(columnInfo("test", "text")));
        expected.put("rows", Arrays.asList(singletonList("test"), singletonList("test")));
        expected.put("size", 2);
        assertResponse(expected, runSql("SELECT * FROM test"));
    }

    public void testNextPage() throws IOException {
        StringBuilder bulk = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            bulk.append("{\"index\":{\"_id\":\"" + i + "\"}}\n");
            bulk.append("{\"text\":\"text" + i + "\", \"number\":" + i + "}\n");
        }
        client().performRequest("POST", "/test/test/_bulk", singletonMap("refresh", "true"),
                new StringEntity(bulk.toString(), ContentType.APPLICATION_JSON));

        String request = "{\"query\":\"SELECT text, number, SIN(number) AS s FROM test ORDER BY number\", \"fetch_size\":2}";

        String cursor = null;
        for (int i = 0; i < 20; i += 2) {
            Map<String, Object> response;
            if (i == 0) {
                response = runSql(new StringEntity(request, ContentType.APPLICATION_JSON));
            } else {
                response = runSql(new StringEntity("{\"cursor\":\"" + cursor + "\"}", ContentType.APPLICATION_JSON));
            }

            Map<String, Object> expected = new HashMap<>();
            if (i == 0) {
                expected.put("columns", Arrays.asList(
                        columnInfo("text", "text"),
                        columnInfo("number", "long"),
                        columnInfo("s", "double")));
            }
            expected.put("rows", Arrays.asList(
                    Arrays.asList("text" + i, i, Math.sin(i)),
                    Arrays.asList("text" + (i + 1), i + 1, Math.sin(i + 1))));
            expected.put("size", 2);
            cursor = (String) response.remove("cursor");
            assertResponse(expected, response);
            assertNotNull(cursor);
        }
        Map<String, Object> expected = new HashMap<>();
        expected.put("size", 0);
        expected.put("rows", emptyList());
        assertResponse(expected, runSql(new StringEntity("{\"cursor\":\"" + cursor + "\"}", ContentType.APPLICATION_JSON)));
    }

    @AwaitsFix(bugUrl = "https://github.com/elastic/x-pack-elasticsearch/issues/2074")
    public void testTimeZone() throws IOException {
        StringBuilder bulk = new StringBuilder();
        bulk.append("{\"index\":{\"_id\":\"1\"}}\n");
        bulk.append("{\"test\":\"2017-07-27 00:00:00\"}\n");
        bulk.append("{\"index\":{\"_id\":\"2\"}}\n");
        bulk.append("{\"test\":\"2017-07-27 01:00:00\"}\n");
        client().performRequest("POST", "/test/test/_bulk", singletonMap("refresh", "true"),
                new StringEntity(bulk.toString(), ContentType.APPLICATION_JSON));

        Map<String, Object> expected = new HashMap<>();
        expected.put("columns", singletonMap("test", singletonMap("type", "text")));
        expected.put("rows", Arrays.asList(singletonMap("test", "test"), singletonMap("test", "test")));
        expected.put("size", 2);

        // Default TimeZone is UTC
        assertResponse(expected, runSql(
                new StringEntity("{\"query\":\"SELECT DAY_OF_YEAR(test), COUNT(*) FROM test\"}", ContentType.APPLICATION_JSON)));
    }

    @Override
    public void testSelectInvalidSql() {
        expectBadRequest(() -> runSql("SELECT * FRO"), containsString("1:8: Cannot determine columns for *"));
    }

    @Override
    public void testSelectFromMissingIndex() {
        expectBadRequest(() -> runSql("SELECT * FROM missing"), containsString("1:15: Unknown index [missing]"));
    }

    @Override
    public void testSelectMissingField() throws IOException {
        StringBuilder bulk = new StringBuilder();
        bulk.append("{\"index\":{\"_id\":\"1\"}}\n");
        bulk.append("{\"test\":\"test\"}\n");
        client().performRequest("POST", "/test/test/_bulk", singletonMap("refresh", "true"),
                new StringEntity(bulk.toString(), ContentType.APPLICATION_JSON));

        expectBadRequest(() -> runSql("SELECT foo FROM test"), containsString("1:8: Unknown column [foo]"));
    }

    @Override
    public void testSelectMissingFunction() throws Exception {
        StringBuilder bulk = new StringBuilder();
        bulk.append("{\"index\":{\"_id\":\"1\"}}\n");
        bulk.append("{\"foo\":1}\n");
        client().performRequest("POST", "/test/test/_bulk", singletonMap("refresh", "true"),
                new StringEntity(bulk.toString(), ContentType.APPLICATION_JSON));

        expectBadRequest(() -> runSql("SELECT missing(foo) FROM test"), containsString("1:8: Unknown function [missing]"));
    }

    private void expectBadRequest(ThrowingRunnable code, Matcher<String> errorMessageMatcher) {
        ResponseException e = expectThrows(ResponseException.class, code);
        assertEquals(e.getMessage(), 400, e.getResponse().getStatusLine().getStatusCode());
        assertThat(e.getMessage(), errorMessageMatcher);
    }

    private Map<String, Object> runSql(String sql) throws IOException {
        return runSql(sql, "");
    }

    private Map<String, Object> runSql(String sql, String suffix) throws IOException {
        return runSql(suffix, new StringEntity("{\"query\":\"" + sql + "\"}", ContentType.APPLICATION_JSON));
    }

    private Map<String, Object> runSql(HttpEntity sql) throws IOException {
        return runSql("", sql);
    }

    private Map<String, Object> runSql(String suffix, HttpEntity sql) throws IOException {
        Map<String, String> params = new TreeMap<>();
        params.put("error_trace", "true");   // Helps with debugging in case something crazy happens on the server.
        params.put("pretty", "true");        // Improves error reporting readability
        params.put("format", "json");        // JSON is easier to parse then a table
        Response response = client().performRequest("POST", "/_xpack/sql" + suffix, params, sql);
        try (InputStream content = response.getEntity().getContent()) {
            return XContentHelper.convertToMap(JsonXContent.jsonXContent, content, false);
        }
    }

    public void testBasicTranslateQuery() throws IOException {
        StringBuilder bulk = new StringBuilder();
        bulk.append("{\"index\":{\"_id\":\"1\"}}\n");
        bulk.append("{\"test\":\"test\"}\n");
        bulk.append("{\"index\":{\"_id\":\"2\"}}\n");
        bulk.append("{\"test\":\"test\"}\n");
        client().performRequest("POST", "/test_translate/test/_bulk", singletonMap("refresh", "true"),
                new StringEntity(bulk.toString(), ContentType.APPLICATION_JSON));

        Map<String, Object> response = runSql("SELECT * FROM test_translate", "/translate/");
        assertEquals(response.get("size"), 1000);
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) response.get("_source");
        assertNotNull(source);
        assertEquals(emptyList(), source.get("excludes"));
        assertEquals(singletonList("test"), source.get("includes"));
    }

    public void testBasicQueryWithFilter() throws IOException {
        StringBuilder bulk = new StringBuilder();
        bulk.append("{\"index\":{\"_id\":\"1\"}}\n");
        bulk.append("{\"test\":\"foo\"}\n");
        bulk.append("{\"index\":{\"_id\":\"2\"}}\n");
        bulk.append("{\"test\":\"bar\"}\n");
        client().performRequest("POST", "/test/test/_bulk", singletonMap("refresh", "true"),
                new StringEntity(bulk.toString(), ContentType.APPLICATION_JSON));

        Map<String, Object> expected = new HashMap<>();
        expected.put("columns", singletonList(columnInfo("test", "text")));
        expected.put("rows", singletonList(singletonList("foo")));
        expected.put("size", 1);
        assertResponse(expected, runSql(new StringEntity("{\"query\":\"SELECT * FROM test\", \"filter\":{\"match\": {\"test\": \"foo\"}}}",
                ContentType.APPLICATION_JSON)));
    }

    public void testBasicTranslateQueryWithFilter() throws IOException {
        StringBuilder bulk = new StringBuilder();
        bulk.append("{\"index\":{\"_id\":\"1\"}}\n");
        bulk.append("{\"test\":\"foo\"}\n");
        bulk.append("{\"index\":{\"_id\":\"2\"}}\n");
        bulk.append("{\"test\":\"bar\"}\n");
        client().performRequest("POST", "/test/test/_bulk", singletonMap("refresh", "true"),
                new StringEntity(bulk.toString(), ContentType.APPLICATION_JSON));

        Map<String, Object> response = runSql("/translate/",
                new StringEntity("{\"query\":\"SELECT * FROM test\", \"filter\":{\"match\": {\"test\": \"foo\"}}}",
                        ContentType.APPLICATION_JSON));

        assertEquals(response.get("size"), 1000);
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) response.get("_source");
        assertNotNull(source);
        assertEquals(emptyList(), source.get("excludes"));
        assertEquals(singletonList("test"), source.get("includes"));

        @SuppressWarnings("unchecked")
        Map<String, Object> query = (Map<String, Object>) response.get("query");
        assertNotNull(query);

        @SuppressWarnings("unchecked")
        Map<String, Object> constantScore = (Map<String, Object>) query.get("constant_score");
        assertNotNull(constantScore);

        @SuppressWarnings("unchecked")
        Map<String, Object> filter = (Map<String, Object>) constantScore.get("filter");
        assertNotNull(filter);

        @SuppressWarnings("unchecked")
        Map<String, Object> match = (Map<String, Object>) filter.get("match");
        assertNotNull(match);

        @SuppressWarnings("unchecked")
        Map<String, Object> matchQuery = (Map<String, Object>) match.get("test");
        assertNotNull(matchQuery);
        assertEquals("foo", matchQuery.get("query"));
    }

    public void testBasicQueryText() throws IOException {
        StringBuilder bulk = new StringBuilder();
        bulk.append("{\"index\":{\"_id\":\"1\"}}\n");
        bulk.append("{\"test\":\"test\"}\n");
        bulk.append("{\"index\":{\"_id\":\"2\"}}\n");
        bulk.append("{\"test\":\"test\"}\n");
        client().performRequest("POST", "/test/test/_bulk", singletonMap("refresh", "true"),
                new StringEntity(bulk.toString(), ContentType.APPLICATION_JSON));
        String expected =
                "test           \n" +
                        "---------------\n" +
                        "test           \n" +
                        "test           \n";
        Tuple<String, String> response = runSqlAsText("SELECT * FROM test");
        logger.warn(expected);
        logger.warn(response.v1());
    }

    public void testNextPageText() throws IOException {
        StringBuilder bulk = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            bulk.append("{\"index\":{\"_id\":\"" + i + "\"}}\n");
            bulk.append("{\"text\":\"text" + i + "\", \"number\":" + i + "}\n");
        }
        client().performRequest("POST", "/test/test/_bulk", singletonMap("refresh", "true"),
                new StringEntity(bulk.toString(), ContentType.APPLICATION_JSON));

        String request = "{\"query\":\"SELECT text, number, number + 5 AS sum FROM test ORDER BY number\", \"fetch_size\":2}";

        String cursor = null;
        for (int i = 0; i < 20; i += 2) {
            Tuple<String, String> response;
            if (i == 0) {
                response = runSqlAsText("", new StringEntity(request, ContentType.APPLICATION_JSON));
            } else {
                response = runSqlAsText("", new StringEntity("{\"cursor\":\"" + cursor + "\"}", ContentType.APPLICATION_JSON));
            }

            StringBuilder expected = new StringBuilder();
            if (i == 0) {
                expected.append("     text      |    number     |      sum      \n");
                expected.append("---------------+---------------+---------------\n");
            }
            expected.append(String.format(Locale.ROOT, "%-15s|%-15d|%-15d\n", "text" + i, i, i + 5));
            expected.append(String.format(Locale.ROOT, "%-15s|%-15d|%-15d\n", "text" + (i + 1), i + 1, i + 6));
            cursor = response.v2();
            assertEquals(expected.toString(), response.v1());
            assertNotNull(cursor);
        }
        Map<String, Object> expected = new HashMap<>();
        expected.put("size", 0);
        expected.put("rows", emptyList());
        assertResponse(expected, runSql(new StringEntity("{\"cursor\":\"" + cursor + "\"}", ContentType.APPLICATION_JSON)));

        Map<String, Object> response = runSql("/close", new StringEntity("{\"cursor\":\"" + cursor + "\"}", ContentType.APPLICATION_JSON));
        assertEquals(true, response.get("succeeded"));

        assertEquals(0, getNumberOfSearchContexts("test"));
    }

    private Tuple<String, String> runSqlAsText(String sql) throws IOException {
        return runSqlAsText("", new StringEntity("{\"query\":\"" + sql + "\"}", ContentType.APPLICATION_JSON));
    }

    private Tuple<String, String> runSqlAsText(String suffix, HttpEntity sql) throws IOException {
        Response response = client().performRequest("POST", "/_xpack/sql" + suffix, singletonMap("error_trace", "true"), sql);
        return new Tuple<>(
                Streams.copyToString(new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8)),
                response.getHeader("Cursor")
        );
    }

    private void assertResponse(Map<String, Object> expected, Map<String, Object> actual) {
        if (false == expected.equals(actual)) {
            NotEqualMessageBuilder message = new NotEqualMessageBuilder();
            message.compareMaps(actual, expected);
            fail("Response does not match:\n" + message.toString());
        }
    }

    public static int getNumberOfSearchContexts(String index) throws IOException {
        Response response = client().performRequest("GET", "/_stats/search");
        Map<String, Object> stats;
        try (InputStream content = response.getEntity().getContent()) {
            stats = XContentHelper.convertToMap(JsonXContent.jsonXContent, content, false);
        }
        return getOpenContexts(stats, index);
    }

    public static void assertNoSearchContexts() throws IOException {
        Response response = client().performRequest("GET", "/_stats/search");
        Map<String, Object> stats;
        try (InputStream content = response.getEntity().getContent()) {
            stats = XContentHelper.convertToMap(JsonXContent.jsonXContent, content, false);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> indexStats = (Map<String, Object>) stats.get("indices");
        for (String index : indexStats.keySet()) {
            if (index.startsWith(".") == false) { // We are not interested in internal indices
                assertEquals(index + " should have no search contexts", 0, getOpenContexts(stats, index));
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static int getOpenContexts(Map<String, Object> indexStats, String index) {
        return (int) ((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                indexStats.get("indices")).get(index)).get("total")).get("search")).get("open_contexts");
    }

}
