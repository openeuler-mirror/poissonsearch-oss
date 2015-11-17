/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.integration;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexModule;
import org.elasticsearch.shield.ShieldPlugin;
import org.elasticsearch.shield.authc.support.Hasher;
import org.elasticsearch.shield.authc.support.SecuredString;
import org.elasticsearch.test.ShieldIntegTestCase;

import static org.elasticsearch.shield.authc.support.UsernamePasswordToken.BASIC_AUTH_HEADER;
import static org.elasticsearch.shield.authc.support.UsernamePasswordToken.basicAuthHeaderValue;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.*;
import static org.hamcrest.Matchers.equalTo;

/**
 */
public class DocumentAndFieldLevelSecurityTests extends ShieldIntegTestCase {

    protected static final SecuredString USERS_PASSWD = new SecuredString("change_me".toCharArray());
    protected static final String USERS_PASSWD_HASHED = new String(Hasher.BCRYPT.hash(USERS_PASSWD));

    @Override
    protected String configUsers() {
        return super.configUsers() +
                "user1:" + USERS_PASSWD_HASHED + "\n" +
                "user2:" + USERS_PASSWD_HASHED + "\n" +
                "user3:" + USERS_PASSWD_HASHED + "\n" ;
    }

    @Override
    protected String configUsersRoles() {
        return super.configUsersRoles() +
                "role1:user1\n" +
                "role2:user2\n" +
                "role3:user3\n";
    }

    @Override
    protected String configRoles() {
        return super.configRoles() +
                "\nrole1:\n" +
                "  cluster: all\n" +
                "  indices:\n" +
                "    '*':\n" +
                "      privileges: ALL\n" +
                "      fields: field1\n" +
                "      query: '{\"term\" : {\"field1\" : \"value1\"}}'\n" +
                "role2:\n" +
                "  cluster: all\n" +
                "  indices:\n" +
                "    '*':\n" +
                "      privileges: ALL\n" +
                "      fields: field2\n" +
                "      query: '{\"term\" : {\"field2\" : \"value2\"}}'\n" +
                "role3:\n" +
                "  cluster: all\n" +
                "  indices:\n" +
                "    '*':\n" +
                "      privileges: ALL\n" +
                "      fields: field1\n" +
                "      query: '{\"term\" : {\"field2\" : \"value2\"}}'\n";
    }

    @Override
    public Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put(ShieldPlugin.DLS_FLS_ENABLED_SETTING, true)
                .build();
    }

    public void testSimpleQuery() throws Exception {
        assertAcked(client().admin().indices().prepareCreate("test")
                        .addMapping("type1", "field1", "type=string", "field2", "type=string")
        );
        client().prepareIndex("test", "type1", "1").setSource("field1", "value1")
                .setRefresh(true)
                .get();
        client().prepareIndex("test", "type1", "2").setSource("field2", "value2")
                .setRefresh(true)
                .get();

        SearchResponse response = client().prepareSearch("test")
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user1", USERS_PASSWD))
                .get();
        assertHitCount(response, 1);
        assertSearchHits(response, "1");
        assertThat(response.getHits().getAt(0).getSource().size(), equalTo(1));
        assertThat(response.getHits().getAt(0).getSource().get("field1").toString(), equalTo("value1"));

        response = client().prepareSearch("test")
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user2", USERS_PASSWD))
                .get();
        assertHitCount(response, 1);
        assertSearchHits(response, "2");
        assertThat(response.getHits().getAt(0).getSource().size(), equalTo(1));
        assertThat(response.getHits().getAt(0).getSource().get("field2").toString(), equalTo("value2"));
    }

    public void testQueryCache() throws Exception {
        assertAcked(client().admin().indices().prepareCreate("test")
                        .setSettings(Settings.builder().put(IndexModule.QUERY_CACHE_EVERYTHING, true))
                        .addMapping("type1", "field1", "type=string", "field2", "type=string")
        );
        client().prepareIndex("test", "type1", "1").setSource("field1", "value1")
                .setRefresh(true)
                .get();
        client().prepareIndex("test", "type1", "2").setSource("field2", "value2")
                .setRefresh(true)
                .get();

        // Both users have the same role query, but user3 has access to field2 and not field1, which should result in zero hits:
        int max = scaledRandomIntBetween(4, 32);
        for (int i = 0; i < max; i++) {
            SearchResponse response = client().prepareSearch("test")
                    .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user1", USERS_PASSWD))
                    .get();
            assertHitCount(response, 1);
            assertThat(response.getHits().getAt(0).getId(), equalTo("1"));
            assertThat(response.getHits().getAt(0).sourceAsMap().size(), equalTo(1));
            assertThat(response.getHits().getAt(0).sourceAsMap().get("field1"), equalTo("value1"));
            response = client().prepareSearch("test")
                    .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user2", USERS_PASSWD))
                    .get();
            assertHitCount(response, 1);
            assertThat(response.getHits().getAt(0).getId(), equalTo("2"));
            assertThat(response.getHits().getAt(0).sourceAsMap().size(), equalTo(1));
            assertThat(response.getHits().getAt(0).sourceAsMap().get("field2"), equalTo("value2"));

            // this is a bit weird the document level permission (all docs with field2:value2) don't match with the field level permissions (field1),
            // this results in document 2 being returned but no fields are visible:
            response = client().prepareSearch("test")
                    .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user3", USERS_PASSWD))
                    .get();
            assertHitCount(response, 1);
            assertThat(response.getHits().getAt(0).getId(), equalTo("2"));
            assertThat(response.getHits().getAt(0).sourceAsMap().size(), equalTo(0));
        }
    }

}
