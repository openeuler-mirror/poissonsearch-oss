/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.integration;

import org.apache.lucene.util.LuceneTestCase;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.search.ClearScrollResponse;
import org.elasticsearch.action.search.MultiSearchRequestBuilder;
import org.elasticsearch.action.search.MultiSearchResponse;
import org.elasticsearch.action.search.SearchPhaseExecutionException;
import org.elasticsearch.shield.authc.support.Hasher;
import org.elasticsearch.shield.authc.support.SecuredString;
import org.elasticsearch.shield.authz.AuthorizationException;
import org.elasticsearch.test.ShieldIntegrationTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.elasticsearch.shield.authc.support.UsernamePasswordToken.basicAuthHeaderValue;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertThrows;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

@LuceneTestCase.AwaitsFix(bugUrl = "https://github.com/elastic/elasticsearch-shield/issues/947")
public class ShieldClearScrollTests extends ShieldIntegrationTest {

    protected static final String USERS_PASSWD_HASHED = new String(Hasher.BCRYPT.hash(new SecuredString("change_me".toCharArray())));

    private List<String> scrollIds;

    @Override
    protected String configUsers() {
        return super.configUsers() +
            "allowed_user:" + USERS_PASSWD_HASHED + "\n" +
            "denied_user:" + USERS_PASSWD_HASHED + "\n" ;
    }

    @Override
    protected String configUsersRoles() {
        return super.configUsersRoles() +
            "allowed_role:allowed_user\n" +
            "denied_role:denied_user\n";
    }

    @Override
    protected String configRoles() {
        return super.configRoles() +
            // note the new line here.. we need to fix this in another PR
            // as this throws another exception in the constructor and then we fuck up
            "\nallowed_role:\n" +
            "  cluster:\n" +
            "    - cluster:admin/indices/scroll/clear_all \n" +
            "denied_role:\n" +
            "  indices:\n" +
            "    '*': ALL\n";
    }

    @Before
    public void indexRandomDocuments() {
        BulkRequestBuilder bulkRequestBuilder = client().prepareBulk().setRefresh(true);
        for (int i = 0; i < randomIntBetween(10, 50); i++) {
            bulkRequestBuilder.add(client().prepareIndex("index", "type", String.valueOf(i)).setSource("{ \"foo\" : \"bar\" }"));
        }
        BulkResponse bulkItemResponses = bulkRequestBuilder.get();
        assertThat(bulkItemResponses.hasFailures(), is(false));

        MultiSearchRequestBuilder multiSearchRequestBuilder = client().prepareMultiSearch();
        int count = randomIntBetween(5, 15);
        for (int i = 0; i < count; i++) {
            multiSearchRequestBuilder.add(client().prepareSearch("index").setTypes("type").setScroll("10m").setSize(1));
        }
        MultiSearchResponse multiSearchResponse = multiSearchRequestBuilder.get();
        scrollIds = getScrollIds(multiSearchResponse);
    }

    @After
    public void clearScrolls() {
        //clear all scroll ids from the default admin user, just in case any of test fails
        client().prepareClearScroll().addScrollId("_all").get();
    }

    @Test
    public void testThatClearingAllScrollIdsWorks() throws Exception {
        String shieldUser = "allowed_user:change_me";
        String basicAuth = basicAuthHeaderValue("allowed_user", new SecuredString("change_me".toCharArray()));
        ClearScrollResponse clearScrollResponse = internalTestCluster().transportClient().prepareClearScroll()
            .putHeader("shield.user", shieldUser)
            .putHeader("Authorization", basicAuth)
            .addScrollId("_all").get();
        assertThat(clearScrollResponse.isSucceeded(), is(true));

        assertThatScrollIdsDoNotExist(scrollIds);
    }

    @Test
    public void testThatClearingAllScrollIdsRequirePermissions() throws Exception {
        String shieldUser = "denied_user:change_me";
        String basicAuth = basicAuthHeaderValue("denied_user", new SecuredString("change_me".toCharArray()));

        assertThrows(internalTestCluster().transportClient().prepareClearScroll()
                .putHeader("shield.user", shieldUser)
                .putHeader("Authorization", basicAuth)
                .addScrollId("_all"), AuthorizationException.class, "action [cluster:admin/indices/scroll/clear_all] is unauthorized for user [denied_user]");

        // deletion of scroll ids should work
        ClearScrollResponse clearByIdScrollResponse = client().prepareClearScroll().setScrollIds(scrollIds).get();
        assertThat(clearByIdScrollResponse.isSucceeded(), is(true));

        // test with each id, that they do not exist
        assertThatScrollIdsDoNotExist(scrollIds);
    }

    private void assertThatScrollIdsDoNotExist(List<String> scrollIds) {
        for (String scrollId : scrollIds) {
            try {
                client().prepareSearchScroll(scrollId).get();
                fail("Expected SearchPhaseExecutionException but did not happen");
            } catch (SearchPhaseExecutionException expectedException) {
                assertThat(expectedException.toString(), containsString("SearchContextMissingException"));
            }
        }
    }

    private List<String> getScrollIds(MultiSearchResponse multiSearchResponse) {
        List<String> ids = new ArrayList<>();
        for (MultiSearchResponse.Item item : multiSearchResponse) {
            ids.add(item.getResponse().getScrollId());
        }
        return ids;
    }
}
