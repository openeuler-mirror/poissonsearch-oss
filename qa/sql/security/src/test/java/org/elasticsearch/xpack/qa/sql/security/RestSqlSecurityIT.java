/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.qa.sql.security;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.test.NotEqualMessageBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.singletonMap;
import static org.elasticsearch.xpack.qa.sql.rest.RestSqlTestCase.columnInfo;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

public class RestSqlSecurityIT extends SqlSecurityTestCase {
    private static class RestActions implements Actions {
        @Override
        public void queryWorksAsAdmin() throws Exception {
            Map<String, Object> expected = new HashMap<>();
            expected.put("columns", Arrays.asList(
                    columnInfo("a", "long"),
                    columnInfo("b", "long"),
                    columnInfo("c", "long")));
            expected.put("rows", Arrays.asList(
                    Arrays.asList(1, 2, 3),
                    Arrays.asList(4, 5, 6)));
            expected.put("size", 2);

            assertResponse(expected, runSql(null, "SELECT * FROM test ORDER BY a"));
        }

        @Override
        public void expectMatchesAdmin(String adminSql, String user, String userSql) throws Exception {
            assertResponse(runSql(null, adminSql), runSql(user, userSql));
        }

        @Override
        public void expectScrollMatchesAdmin(String adminSql, String user, String userSql) throws Exception {
            Map<String, Object> adminResponse = runSql(null,
                    new StringEntity("{\"query\": \"" + adminSql + "\", \"fetch_size\": 1}", ContentType.APPLICATION_JSON));
            Map<String, Object> otherResponse = runSql(user,
                    new StringEntity("{\"query\": \"" + adminSql + "\", \"fetch_size\": 1}", ContentType.APPLICATION_JSON));

            String adminCursor = (String) adminResponse.remove("cursor");
            String otherCursor = (String) otherResponse.remove("cursor");
            assertNotNull(adminCursor);
            assertNotNull(otherCursor);
            assertResponse(adminResponse, otherResponse);
            while (true) {
                adminResponse = runSql(null, new StringEntity("{\"cursor\": \"" + adminCursor + "\"}", ContentType.APPLICATION_JSON));
                otherResponse = runSql(user, new StringEntity("{\"cursor\": \"" + otherCursor + "\"}", ContentType.APPLICATION_JSON));
                adminCursor = (String) adminResponse.remove("cursor");
                otherCursor = (String) otherResponse.remove("cursor");
                assertResponse(adminResponse, otherResponse);
                if (adminCursor == null) {
                    assertNull(otherCursor);
                    return;
                }
                assertNotNull(otherCursor);
            }
        }

        @Override
        public void expectDescribe(Map<String, String> columns, String user) throws Exception {
            Map<String, Object> expected = new HashMap<>(3);
            expected.put("columns", Arrays.asList(
                    columnInfo("column", "keyword"),
                    columnInfo("type", "keyword")));
            List<List<String>> rows = new ArrayList<>(columns.size());
            for (Map.Entry<String, String> column : columns.entrySet()) {
                rows.add(Arrays.asList(column.getKey(), column.getValue()));
            }
            expected.put("rows", rows);
            expected.put("size", columns.size());

            assertResponse(expected, runSql(user, "DESCRIBE test"));
        }

        @Override
        public void expectShowTables(List<String> tables, String user) throws Exception {
            Map<String, Object> expected = new HashMap<>();
            expected.put("columns", singletonList(columnInfo("table", "keyword")));
            List<List<String>> rows = new ArrayList<>();
            for (String table : tables) {
                rows.add(singletonList(table));
            }
            expected.put("rows", rows);
            expected.put("size", tables.size());
            assertResponse(expected, runSql(user, "SHOW TABLES"));
        }

        @Override
        public void expectForbidden(String user, String sql) {
            ResponseException e = expectThrows(ResponseException.class, () -> runSql(user, sql));
            assertThat(e.getResponse().getStatusLine().getStatusCode(), equalTo(403));
            assertThat(e.getMessage(), containsString("unauthorized"));
        }

        @Override
        public void expectUnknownIndex(String user, String sql) {
            ResponseException e = expectThrows(ResponseException.class, () -> runSql(user, sql));
            assertThat(e.getResponse().getStatusLine().getStatusCode(), equalTo(400));
            assertThat(e.getMessage(), containsString("Unknown index"));
        }

        @Override
        public void expectUnknownColumn(String user, String sql, String column) throws Exception {
            ResponseException e = expectThrows(ResponseException.class, () -> runSql(user, sql));
            assertThat(e.getMessage(), containsString("Unknown column [" + column + "]"));
        }

        private static Map<String, Object> runSql(@Nullable String asUser, String sql) throws IOException {
            return runSql(asUser, new StringEntity("{\"query\": \"" + sql + "\"}", ContentType.APPLICATION_JSON));
        }

        private static Map<String, Object> runSql(@Nullable String asUser, HttpEntity entity) throws IOException {
            Header[] headers = asUser == null ? new Header[0] : new Header[] {new BasicHeader("es-security-runas-user", asUser)};
            Response response = client().performRequest("POST", "/_sql", singletonMap("format", "json"), entity, headers);
            return toMap(response);
        }

        private static void assertResponse(Map<String, Object> expected, Map<String, Object> actual) {
            if (false == expected.equals(actual)) {
                NotEqualMessageBuilder message = new NotEqualMessageBuilder();
                message.compareMaps(actual, expected);
                fail("Response does not match:\n" + message.toString());
            }
        }

        private static Map<String, Object> toMap(Response response) throws IOException {
            try (InputStream content = response.getEntity().getContent()) {
                return XContentHelper.convertToMap(JsonXContent.jsonXContent, content, false);
            }
        }
    }

    public RestSqlSecurityIT() {
        super(new RestActions());
    }

    /**
     * Test the hijacking a scroll fails. This test is only implemented for
     * REST because it is the only API where it is simple to hijack a scroll.
     * It should excercise the same code as the other APIs but if we were truly
     * paranoid we'd hack together something to test the others as well.
     */
    public void testHijackScrollFails() throws Exception {
        createUser("full_access", "read_all");

        Map<String, Object> adminResponse = RestActions.runSql(null,
                new StringEntity("{\"query\": \"SELECT * FROM test\", \"fetch_size\": 1}", ContentType.APPLICATION_JSON));

        String cursor = (String) adminResponse.remove("cursor");
        assertNotNull(cursor);

        ResponseException e = expectThrows(ResponseException.class, () ->
                RestActions.runSql("full_access", new StringEntity("{\"cursor\":\"" + cursor + "\"}", ContentType.APPLICATION_JSON)));
        // TODO return a better error message for bad scrolls
        assertThat(e.getMessage(), containsString("No search context found for id"));
        assertEquals(404, e.getResponse().getStatusLine().getStatusCode());

        new AuditLogAsserter()
            .expectSqlCompositeAction("test_admin", "test")
            .expect(true, SQL_ACTION_NAME, "full_access", empty())
            // One scroll access denied per shard
            .expect(false, SQL_ACTION_NAME, "full_access", empty(), "InternalScrollSearchRequest")
            .expect(false, SQL_ACTION_NAME, "full_access", empty(), "InternalScrollSearchRequest")
            .expect(false, SQL_ACTION_NAME, "full_access", empty(), "InternalScrollSearchRequest")
            .expect(false, SQL_ACTION_NAME, "full_access", empty(), "InternalScrollSearchRequest")
            .expect(false, SQL_ACTION_NAME, "full_access", empty(), "InternalScrollSearchRequest")
            .assertLogs();
    }
}
