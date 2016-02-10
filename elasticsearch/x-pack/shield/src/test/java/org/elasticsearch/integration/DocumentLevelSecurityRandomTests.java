/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.integration;

import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequestBuilder;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.shield.Shield;
import org.elasticsearch.shield.authc.support.Hasher;
import org.elasticsearch.shield.authc.support.SecuredString;
import org.elasticsearch.test.ShieldIntegTestCase;
import org.elasticsearch.xpack.XPackPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.elasticsearch.shield.authc.support.UsernamePasswordToken.BASIC_AUTH_HEADER;
import static org.elasticsearch.shield.authc.support.UsernamePasswordToken.basicAuthHeaderValue;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.equalTo;

/**
 */
public class DocumentLevelSecurityRandomTests extends ShieldIntegTestCase {

    protected static final SecuredString USERS_PASSWD = new SecuredString("change_me".toCharArray());
    protected static final String USERS_PASSWD_HASHED = new String(Hasher.BCRYPT.hash(new SecuredString("change_me".toCharArray())));

    // can't add a second test method, because each test run creates a new instance of this class and that will will result
    // in a new random value:
    private final int numberOfRoles = scaledRandomIntBetween(3, 99);

    @Override
    protected String configUsers() {
        StringBuilder builder = new StringBuilder(super.configUsers());
        for (int i = 1; i <= numberOfRoles; i++) {
            builder.append("user").append(i).append(':').append(USERS_PASSWD_HASHED).append('\n');
        }
        return builder.toString();
    }

    @Override
    protected String configUsersRoles() {
        StringBuilder builder = new StringBuilder(super.configUsersRoles());
        for (int i = 1; i <= numberOfRoles; i++) {
            builder.append("role").append(i).append(":user").append(i).append('\n');
        }
        return builder.toString();
    }

    @Override
    protected String configRoles() {
        StringBuilder builder = new StringBuilder(super.configRoles());
        builder.append('\n');
        for (int i = 1; i <= numberOfRoles; i++) {
            builder.append("role").append(i).append(":\n");
            builder.append("  cluster: all\n");
            builder.append("  indices:\n");
            builder.append("    '*':\n");
            builder.append("      privileges: ALL\n");
            builder.append("      query: \n");
            builder.append("        term: \n");
            builder.append("          field1: value").append(i).append('\n');
        }
        return builder.toString();
    }

    @Override
    public Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put(XPackPlugin.featureEnabledSetting(Shield.DLS_FLS_FEATURE), true)
                .build();
    }

    public void testDuelWithAliasFilters() throws Exception {
        assertAcked(client().admin().indices().prepareCreate("test")
                        .addMapping("type1", "field1", "type=string", "field2", "type=string")
        );

        List<IndexRequestBuilder> requests = new ArrayList<>(numberOfRoles);
        IndicesAliasesRequestBuilder builder = client().admin().indices().prepareAliases();
        for (int i = 1; i <= numberOfRoles; i++) {
            String value = "value" + i;
            requests.add(client().prepareIndex("test", "type1", value).setSource("field1", value));
            builder.addAlias("test", "alias" + i, QueryBuilders.termQuery("field1", value));
        }
        indexRandom(true, requests);
        builder.get();

        for (int roleI = 1; roleI <= numberOfRoles; roleI++) {
            SearchResponse searchResponse1 = client()
                    .filterWithHeader(Collections.singletonMap(BASIC_AUTH_HEADER, basicAuthHeaderValue("user" + roleI, USERS_PASSWD)))
                    .prepareSearch("test")
                    .get();
            SearchResponse searchResponse2 = client().prepareSearch("alias" + roleI).get();
            assertThat(searchResponse1.getHits().getTotalHits(), equalTo(searchResponse2.getHits().getTotalHits()));
            for (int hitI = 0; hitI < searchResponse1.getHits().getHits().length; hitI++) {
                assertThat(searchResponse1.getHits().getAt(hitI).getId(), equalTo(searchResponse2.getHits().getAt(hitI).getId()));
            }
        }
    }

}
