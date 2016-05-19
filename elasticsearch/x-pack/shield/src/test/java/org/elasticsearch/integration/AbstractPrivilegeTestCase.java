/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.integration;

import org.apache.http.HttpEntity;
import org.apache.http.StatusLine;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.ElasticsearchResponse;
import org.elasticsearch.client.ElasticsearchResponseException;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.shield.authc.support.Hasher;
import org.elasticsearch.shield.authc.support.SecuredString;
import org.elasticsearch.shield.authc.support.UsernamePasswordToken;
import org.elasticsearch.test.ShieldIntegTestCase;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * a helper class that contains a couple of HTTP helper methods
 */
public abstract class AbstractPrivilegeTestCase extends ShieldIntegTestCase {

    protected static final String USERS_PASSWD_HASHED = new String(Hasher.BCRYPT.hash(new SecuredString("passwd".toCharArray())));

    protected void assertAccessIsAllowed(String user, String method, String uri, String body,
                                         Map<String, String> params) throws IOException {
        try (RestClient restClient = restClient()) {
            ElasticsearchResponse response = restClient.performRequest(method, uri, params, entityOrNull(body),
                    new BasicHeader(UsernamePasswordToken.BASIC_AUTH_HEADER,
                            UsernamePasswordToken.basicAuthHeaderValue(user, new SecuredString("passwd".toCharArray()))));
            StatusLine statusLine = response.getStatusLine();
            String message = String.format(Locale.ROOT, "%s %s: Expected no error got %s %s with body %s", method, uri,
                    statusLine.getStatusCode(), statusLine.getReasonPhrase(), EntityUtils.toString(response.getEntity()));
            assertThat(message, statusLine.getStatusCode(), is(not(greaterThanOrEqualTo(400))));
        }
    }

    protected void assertAccessIsAllowed(String user, String method, String uri, String body) throws IOException {
        assertAccessIsAllowed(user, method, uri, body, new HashMap<>());
    }

    protected void assertAccessIsAllowed(String user, String method, String uri) throws IOException {
        assertAccessIsAllowed(user, method, uri, null, new HashMap<>());
    }

    protected void assertAccessIsDenied(String user, String method, String uri, String body) throws IOException {
        assertAccessIsDenied(user, method, uri, body, new HashMap<>());
    }

    protected void assertAccessIsDenied(String user, String method, String uri) throws IOException {
        assertAccessIsDenied(user, method, uri, null, new HashMap<>());
    }

    protected void assertAccessIsDenied(String user, String method, String uri, String body,
                                        Map<String, String> params) throws IOException {
        try (RestClient restClient = restClient()) {
            restClient.performRequest(method, uri, params, entityOrNull(body),
                    new BasicHeader(UsernamePasswordToken.BASIC_AUTH_HEADER,
                            UsernamePasswordToken.basicAuthHeaderValue(user, new SecuredString("passwd".toCharArray()))));
            fail("request should have failed");
        } catch(ElasticsearchResponseException e) {
            StatusLine statusLine = e.getElasticsearchResponse().getStatusLine();
            String message = String.format(Locale.ROOT, "%s %s body %s: Expected 403, got %s %s with body %s", method, uri, body,
                    statusLine.getStatusCode(), statusLine.getReasonPhrase(), e.getResponseBody());
            assertThat(message, statusLine.getStatusCode(), is(403));
        }
    }

    private static HttpEntity entityOrNull(String body) {
        HttpEntity entity = null;
        if (body != null) {
            entity = new StringEntity(body, RestClient.JSON_CONTENT_TYPE);
        }
        return entity;
    }
}
