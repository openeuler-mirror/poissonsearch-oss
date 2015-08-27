/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.integration;

import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.shield.authc.support.Hasher;
import org.elasticsearch.shield.authc.support.SecuredString;
import org.elasticsearch.test.ShieldIntegTestCase;

import java.util.*;

import static org.elasticsearch.index.query.QueryBuilders.matchQuery;
import static org.elasticsearch.shield.authc.support.UsernamePasswordToken.BASIC_AUTH_HEADER;
import static org.elasticsearch.shield.authc.support.UsernamePasswordToken.basicAuthHeaderValue;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.hamcrest.Matchers.equalTo;

public class FieldLevelSecurityRandomTests extends ShieldIntegTestCase {

    protected static final SecuredString USERS_PASSWD = new SecuredString("change_me".toCharArray());
    protected static final String USERS_PASSWD_HASHED = new String(Hasher.BCRYPT.hash(new SecuredString("change_me".toCharArray())));

    private static Set<String> allowedFields;
    private static Set<String> disAllowedFields;

    @Override
    protected String configUsers() {
        return super.configUsers() +
                "user1:" + USERS_PASSWD_HASHED + "\n" +
                "user2:" + USERS_PASSWD_HASHED + "\n" +
                "user3:" + USERS_PASSWD_HASHED + "\n" +
                "user4:" + USERS_PASSWD_HASHED + "\n" ;
    }

    @Override
    protected String configUsersRoles() {
        return super.configUsersRoles() +
                "role1:user1\n" +
                "role2:user2\n" +
                "role3:user3\n" +
                "role4:user4\n";
    }
    @Override
    protected String configRoles() {
        if (allowedFields == null) {
            allowedFields = new HashSet<>();
            disAllowedFields = new HashSet<>();
            int numFields = scaledRandomIntBetween(5, 50);
            for (int i = 0; i < numFields; i++) {
                String field = "field" + i;
                if (i % 2 == 0) {
                    allowedFields.add(field);
                } else {
                    disAllowedFields.add(field);
                }
            }
        }

        StringBuilder roleFields = new StringBuilder();
        for (String field : allowedFields) {
            roleFields.append("        - ").append(field).append('\n');
        }

        return super.configRoles() +
                "\nrole1:\n" +
                "  cluster: all\n" +
                "  indices:\n" +
                "    '*':\n" +
                "      privileges: ALL\n" +
                "      fields:\n" + roleFields.toString() +
                "role2:\n" +
                "  cluster: all\n" +
                "  indices:\n" +
                "    test:\n" +
                "      privileges: ALL\n" +
                "      fields:\n" +
                "        - field1\n" +
                "role3:\n" +
                "  cluster: all\n" +
                "  indices:\n" +
                "    test:\n" +
                "      privileges: ALL\n" +
                "      fields:\n" +
                "        - field2\n" +
                "role4:\n" +
                "  cluster: all\n" +
                "  indices:\n" +
                "    test:\n" +
                "      privileges: ALL\n" +
                "      fields:\n" +
                "        - field3\n";
    }

    public void testRandom() throws Exception {
        int j = 0;
        Map<String, Object> doc = new HashMap<>();
        String[] fieldMappers = new String[(allowedFields.size() + disAllowedFields.size()) * 2];
        for (String field : allowedFields) {
            fieldMappers[j++] = field;
            fieldMappers[j++] = "type=string";
            doc.put(field, "value");
        }
        for (String field : disAllowedFields) {
            fieldMappers[j++] = field;
            fieldMappers[j++] = "type=string";
            doc.put(field, "value");
        }
        assertAcked(client().admin().indices().prepareCreate("test")
                        .addMapping("type1", fieldMappers)
        );
        client().prepareIndex("test", "type1", "1").setSource(doc).setRefresh(true).get();

        for (String allowedField : allowedFields) {
            logger.info("Checking allowed field [{}]", allowedField);
            SearchResponse response = client().prepareSearch("test")
                    .setQuery(matchQuery(allowedField, "value"))
                    .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user1", USERS_PASSWD))
                    .get();
            assertHitCount(response, 1);
        }
        for (String disallowedField : disAllowedFields) {
            logger.info("Checking disallowed field [{}]", disallowedField);
            SearchResponse response = client().prepareSearch("test")
                    .setQuery(matchQuery(disallowedField, "value"))
                    .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user1", USERS_PASSWD))
                    .get();
            assertHitCount(response, 0);
        }
    }

    public void testDuel() throws Exception {
        assertAcked(client().admin().indices().prepareCreate("test")
                        .addMapping("type1", "field1", "type=string", "field2", "type=string", "field3", "type=string")
        );

        int numDocs = scaledRandomIntBetween(32, 128);
        List<IndexRequestBuilder> requests = new ArrayList<>(numDocs);
        for (int i = 1; i <= numDocs; i++) {
            String field = randomFrom("field1", "field2", "field3");
            String value = "value";
            requests.add(client().prepareIndex("test", "type1", value).setSource(field, value));
        }
        indexRandom(true, requests);

        SearchResponse actual = client().prepareSearch("test")
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user2", USERS_PASSWD))
                .addSort("_uid", SortOrder.ASC)
                .setQuery(QueryBuilders.boolQuery()
                                .should(QueryBuilders.termQuery("field1", "value"))
                                .should(QueryBuilders.termQuery("field2", "value"))
                                .should(QueryBuilders.termQuery("field3", "value"))
                )
                .get();
        SearchResponse expected = client().prepareSearch("test")
                .addSort("_uid", SortOrder.ASC)
                .setQuery(QueryBuilders.boolQuery()
                                .should(QueryBuilders.termQuery("field1", "value"))
                )
                .get();
        assertThat(actual.getHits().getTotalHits(), equalTo(expected.getHits().getTotalHits()));
        assertThat(actual.getHits().getHits().length, equalTo(expected.getHits().getHits().length));
        for (int i = 0; i < actual.getHits().getHits().length; i++) {
            assertThat(actual.getHits().getAt(i).getId(), equalTo(expected.getHits().getAt(i).getId()));
        }

        actual = client().prepareSearch("test")
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user3", USERS_PASSWD))
                .addSort("_uid", SortOrder.ASC)
                .setQuery(QueryBuilders.boolQuery()
                                .should(QueryBuilders.termQuery("field1", "value"))
                                .should(QueryBuilders.termQuery("field2", "value"))
                                .should(QueryBuilders.termQuery("field3", "value"))
                )
                .get();
        expected = client().prepareSearch("test")
                .addSort("_uid", SortOrder.ASC)
                .setQuery(QueryBuilders.boolQuery()
                                .should(QueryBuilders.termQuery("field2", "value"))
                )
                .get();
        assertThat(actual.getHits().getTotalHits(), equalTo(expected.getHits().getTotalHits()));
        assertThat(actual.getHits().getHits().length, equalTo(expected.getHits().getHits().length));
        for (int i = 0; i < actual.getHits().getHits().length; i++) {
            assertThat(actual.getHits().getAt(i).getId(), equalTo(expected.getHits().getAt(i).getId()));
        }

        actual = client().prepareSearch("test")
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user4", USERS_PASSWD))
                .addSort("_uid", SortOrder.ASC)
                .setQuery(QueryBuilders.boolQuery()
                                .should(QueryBuilders.termQuery("field1", "value"))
                                .should(QueryBuilders.termQuery("field2", "value"))
                                .should(QueryBuilders.termQuery("field3", "value"))
                )
                .get();
        expected = client().prepareSearch("test")
                .addSort("_uid", SortOrder.ASC)
                .setQuery(QueryBuilders.boolQuery()
                                .should(QueryBuilders.termQuery("field3", "value"))
                )
                .get();
        assertThat(actual.getHits().getTotalHits(), equalTo(expected.getHits().getTotalHits()));
        assertThat(actual.getHits().getHits().length, equalTo(expected.getHits().getHits().length));
        for (int i = 0; i < actual.getHits().getHits().length; i++) {
            assertThat(actual.getHits().getAt(i).getId(), equalTo(expected.getHits().getAt(i).getId()));
        }
    }

}
