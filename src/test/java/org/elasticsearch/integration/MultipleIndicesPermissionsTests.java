/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.integration;

import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.MultiSearchResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.shield.authc.support.SecuredStringTests;
import org.elasticsearch.shield.authc.support.UsernamePasswordToken;
import org.elasticsearch.shield.authz.AuthorizationException;
import org.elasticsearch.shield.test.ShieldIntegrationTest;
import org.junit.Test;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.index.query.QueryBuilders.indicesQuery;
import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.shield.authc.support.UsernamePasswordToken.BASIC_AUTH_HEADER;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertNoFailures;
import static org.hamcrest.Matchers.is;

/**
 *
 */
public class MultipleIndicesPermissionsTests extends ShieldIntegrationTest {

    public static final String ROLES =
            DEFAULT_ROLE + ":\n" +
            "  cluster: all\n" +
            "  indices:\n" +
            "    '*': manage\n" +
            "    '/.*/': write\n" +
            "    'test': read\n" +
            "    'test1': read\n" +
            "\n" +
            "role_a:\n" +
            "  indices:\n" +
            "    'a': all\n" +
            "\n" +
            "role_b:\n" +
            "  indices:\n" +
            "    'b': all\n";

    public static final String USERS =
            CONFIG_STANDARD_USER +
            "user_a:{plain}passwd\n" +
            "user_b:{plain}passwd\n";

    public static final String USERS_ROLES =
            CONFIG_STANDARD_USER_ROLES +
            "role_a:user_a,user_b\n" +
            "role_b:user_b\n";

    @Override
    protected String configRole() {
        return ROLES;
    }

    @Override
    protected String configUsers() {
        return USERS;
    }

    @Override
    protected String configUsersRoles() {
        return USERS_ROLES;
    }

    @Test
    public void testSingleRole() throws Exception {
        IndexResponse indexResponse = index("test", "type", jsonBuilder()
                .startObject()
                .field("name", "value")
                .endObject());
        assertThat(indexResponse.isCreated(), is(true));


        indexResponse = index("test1", "type", jsonBuilder()
                .startObject()
                .field("name", "value1")
                .endObject());
        assertThat(indexResponse.isCreated(), is(true));

        refresh();

        Client client = internalCluster().transportClient();

        // no specifying an index, should replace indices with the permitted ones (test & test1)
        SearchResponse searchResponse = client.prepareSearch().setQuery(matchAllQuery()).get();
        assertNoFailures(searchResponse);
        assertHitCount(searchResponse, 2);

        searchResponse = client.prepareSearch().setQuery(indicesQuery(matchAllQuery(), "test1")).get();
        assertNoFailures(searchResponse);
        assertHitCount(searchResponse, 2);

        // _all should expand to all the permitted indices
        searchResponse = client.prepareSearch("_all").setQuery(matchAllQuery()).get();
        assertNoFailures(searchResponse);
        assertHitCount(searchResponse, 2);

        // wildcards should expand to all the permitted indices
        searchResponse = client.prepareSearch("test*").setQuery(matchAllQuery()).get();
        assertNoFailures(searchResponse);
        assertHitCount(searchResponse, 2);

        // specifying a permitted index, should only return results from that index
        searchResponse = client.prepareSearch("test1").setQuery(indicesQuery(matchAllQuery(), "test1")).get();
        assertNoFailures(searchResponse);
        assertHitCount(searchResponse, 1);

        // specifying a forbidden index, should throw an authorization exception
        try {
            client.prepareSearch("test2").setQuery(indicesQuery(matchAllQuery(), "test1")).get();
            fail("expected an authorization exception when searching a forbidden index");
        } catch (AuthorizationException ae) {
            // expected
        }

        try {
            client.prepareSearch("test", "test2").setQuery(matchAllQuery()).get();
            fail("expected an authorization exception when one of mulitple indices is forbidden");
        } catch (AuthorizationException ae) {
            // expected
        }

        MultiSearchResponse msearchResponse = client.prepareMultiSearch()
                .add(client.prepareSearch("test"))
                .add(client.prepareSearch("test1"))
                .get();
        MultiSearchResponse.Item[] items = msearchResponse.getResponses();
        assertThat(items.length, is(2));
        assertThat(items[0].isFailure(), is(false));
        searchResponse = items[0].getResponse();
        assertNoFailures(searchResponse);
        assertHitCount(searchResponse, 1);
        assertThat(items[1].isFailure(), is(false));
        searchResponse = items[1].getResponse();
        assertNoFailures(searchResponse);
        assertHitCount(searchResponse, 1);
    }

    @Test
    public void testMultipleRoles() throws Exception {

        IndexResponse indexResponse = index("a", "type", jsonBuilder()
                .startObject()
                .field("name", "value_a")
                .endObject());
        assertThat(indexResponse.isCreated(), is(true));


        indexResponse = index("b", "type", jsonBuilder()
                .startObject()
                .field("name", "value_b")
                .endObject());
        assertThat(indexResponse.isCreated(), is(true));

        refresh();

        Client client = internalCluster().transportClient();

        SearchResponse response = client.prepareSearch("a")
                .putHeader(BASIC_AUTH_HEADER, userHeader("user_a", "passwd"))
                .get();
        assertNoFailures(response);
        assertHitCount(response, 1);

        String[] indices = randomDouble() < 0.3 ?
                new String[] { "_all"} : randomBoolean() ?
                new String[] { "*" } :
                new String[] {};
        response = client.prepareSearch(indices)
                .putHeader(BASIC_AUTH_HEADER, userHeader("user_a", "passwd"))
                .get();
        assertNoFailures(response);
        assertHitCount(response, 1);

        try {
            indices = randomBoolean() ? new String[] { "a", "b" } : new String[] { "b", "a" };
            client.prepareSearch(indices)
                    .putHeader(BASIC_AUTH_HEADER, userHeader("user_a", "passwd"))
                    .get();
            fail("expected an authorization excpetion when trying to search on multiple indices where there are no search permissions on one/some of them");
        } catch (AuthorizationException ae) {
            //expected
        }

        response = client.prepareSearch("b")
                .putHeader(BASIC_AUTH_HEADER, userHeader("user_b", "passwd"))
                .get();
        assertNoFailures(response);
        assertHitCount(response, 1);

        indices = randomBoolean() ? new String[] { "a", "b" } : new String[] { "b", "a" };
        response = client.prepareSearch(indices)
                .putHeader(BASIC_AUTH_HEADER, userHeader("user_b", "passwd"))
                .get();
        assertNoFailures(response);
        assertHitCount(response, 2);

        indices = randomDouble() < 0.3 ?
                new String[] { "_all"} : randomBoolean() ?
                new String[] { "*" } :
                new String[] {};
        response = client.prepareSearch(indices)
                .putHeader(BASIC_AUTH_HEADER, userHeader("user_b", "passwd"))
                .get();
        assertNoFailures(response);
        assertHitCount(response, 2);
    }

    private static String userHeader(String username, String password) {
        return UsernamePasswordToken.basicAuthHeaderValue(username, SecuredStringTests.build(password));
    }
}
