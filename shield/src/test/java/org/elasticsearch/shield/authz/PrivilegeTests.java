/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authz;

import com.google.common.base.Predicate;
import org.elasticsearch.action.get.GetAction;
import org.elasticsearch.action.get.MultiGetAction;
import org.elasticsearch.action.search.MultiSearchAction;
import org.elasticsearch.action.search.SearchAction;
import org.elasticsearch.action.suggest.SuggestAction;
import org.elasticsearch.shield.support.AutomatonPredicate;
import org.elasticsearch.shield.support.Automatons;
import org.elasticsearch.test.ESTestCase;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import static org.hamcrest.Matchers.*;

/**
 *
 */
public class PrivilegeTests extends ESTestCase {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void testName() throws Exception {
        Privilege.Name name12 = new Privilege.Name("name1", "name2");
        Privilege.Name name34 = new Privilege.Name("name3", "name4");
        Privilege.Name name1234 = randomBoolean() ? name12.add(name34) : name34.add(name12);
        assertThat(name1234, equalTo(new Privilege.Name("name1", "name2", "name3", "name4")));

        Privilege.Name name1 = name12.remove(new Privilege.Name("name2"));
        assertThat(name1, equalTo(new Privilege.Name("name1")));

        Privilege.Name name = name1.remove(new Privilege.Name("name1"));
        assertThat(name, is(Privilege.Name.NONE));

        Privilege.Name none = new Privilege.Name("name1", "name2", "none").remove(name12);
        assertThat(none, is(Privilege.Name.NONE));
    }

    @Test
    public void testSubActionPattern() throws Exception {
        AutomatonPredicate predicate = new AutomatonPredicate(Automatons.patterns("foo" + Privilege.SUB_ACTION_SUFFIX_PATTERN));
        assertThat(predicate.apply("foo[n][nodes]"), is(true));
        assertThat(predicate.apply("foo[n]"), is(true));
        assertThat(predicate.apply("bar[n][nodes]"), is(false));
        assertThat(predicate.apply("[n][nodes]"), is(false));
    }

    @Test
    public void testCluster() throws Exception {

        Privilege.Name name = new Privilege.Name("monitor");
        Privilege.Cluster cluster = Privilege.Cluster.get(name);
        assertThat(cluster, is(Privilege.Cluster.MONITOR));

        // since "all" implies "monitor", this should collapse to All
        name = new Privilege.Name("monitor", "all");
        cluster = Privilege.Cluster.get(name);
        assertThat(cluster, is(Privilege.Cluster.ALL));

        name = new Privilege.Name("monitor", "none");
        cluster = Privilege.Cluster.get(name);
        assertThat(cluster, is(Privilege.Cluster.MONITOR));

        Privilege.Name name2 = new Privilege.Name("none", "monitor");
        Privilege.Cluster cluster2 = Privilege.Cluster.get(name2);
        assertThat(cluster, is(cluster2));
    }

    @Test
    public void testCluster_TemplateActions() throws Exception {

        Privilege.Name name = new Privilege.Name("indices:admin/template/delete");
        Privilege.Cluster cluster = Privilege.Cluster.get(name);
        assertThat(cluster, notNullValue());
        assertThat(cluster.predicate().apply("indices:admin/template/delete"), is(true));

        name = new Privilege.Name("indices:admin/template/get");
        cluster = Privilege.Cluster.get(name);
        assertThat(cluster, notNullValue());
        assertThat(cluster.predicate().apply("indices:admin/template/get"), is(true));

        name = new Privilege.Name("indices:admin/template/put");
        cluster = Privilege.Cluster.get(name);
        assertThat(cluster, notNullValue());
        assertThat(cluster.predicate().apply("indices:admin/template/put"), is(true));
    }

    @Test
    public void testCluster_InvalidName() throws Exception {
        thrown.expect(IllegalArgumentException.class);
        Privilege.Name actionName = new Privilege.Name("foobar");
        Privilege.Cluster.get(actionName);
    }

    @Test
    public void testClusterAction() throws Exception {
        Privilege.Name actionName = new Privilege.Name("cluster:admin/snapshot/delete");
        Privilege.Cluster cluster = Privilege.Cluster.get(actionName);
        assertThat(cluster, notNullValue());
        assertThat(cluster.predicate().apply("cluster:admin/snapshot/delete"), is(true));
        assertThat(cluster.predicate().apply("cluster:admin/snapshot/dele"), is(false));
    }

    @Test
    public void testCluster_AddCustom() throws Exception {
        Privilege.Cluster.addCustom("foo", "cluster:bar");
        boolean found = false;
        for (Privilege.Cluster cluster : Privilege.Cluster.values()) {
            if ("foo".equals(cluster.name.toString())) {
                found = true;
                assertThat(cluster.predicate().apply("cluster:bar"), is(true));
            }
        }
        assertThat(found, is(true));
        Privilege.Cluster cluster = Privilege.Cluster.get(new Privilege.Name("foo"));
        assertThat(cluster, notNullValue());
        assertThat(cluster.name().toString(), is("foo"));
        assertThat(cluster.predicate().apply("cluster:bar"), is(true));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCluster_AddCustom_InvalidPattern() throws Exception {
        Privilege.Cluster.addCustom("foo", "bar");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCluster_AddCustom_AlreadyExists() throws Exception {
        Privilege.Cluster.addCustom("all", "bar");
    }

    @Test
    public void testIndexAction() throws Exception {
        Privilege.Name actionName = new Privilege.Name("indices:admin/mapping/delete");
        Privilege.Index index = Privilege.Index.get(actionName);
        assertThat(index, notNullValue());
        assertThat(index.predicate().apply("indices:admin/mapping/delete"), is(true));
        assertThat(index.predicate().apply("indices:admin/mapping/dele"), is(false));
    }

    @Test
    public void testIndex_Collapse() throws Exception {
        Privilege.Index[] values = Privilege.Index.values().toArray(new Privilege.Index[Privilege.Index.values().size()]);
        Privilege.Index first = values[randomIntBetween(0, values.length-1)];
        Privilege.Index second = values[randomIntBetween(0, values.length-1)];

        Privilege.Name name = new Privilege.Name(first.name().toString(), second.name().toString());
        Privilege.Index index = Privilege.Index.get(name);

        if (first.implies(second)) {
            assertThat(index, is(first));
        }

        if (second.implies(first)) {
            assertThat(index, is(second));
        }
    }

    @Test
    public void testIndex_Implies() throws Exception {
        Privilege.Index[] values = Privilege.Index.values().toArray(new Privilege.Index[Privilege.Index.values().size()]);
        Privilege.Index first = values[randomIntBetween(0, values.length-1)];
        Privilege.Index second = values[randomIntBetween(0, values.length-1)];

        Privilege.Name name = new Privilege.Name(first.name().toString(), second.name().toString());
        Privilege.Index index = Privilege.Index.get(name);

        assertThat(index.implies(first), is(true));
        assertThat(index.implies(second), is(true));

        if (first.implies(second)) {
            assertThat(index, is(first));
        }

        if (second.implies(first)) {
            if (index != second) {
                Privilege.Index idx = Privilege.Index.get(name);
                idx.name().toString();
            }
            assertThat(index, is(second));
        }

        for (Privilege.Index other : Privilege.Index.values()) {
            if (first.implies(other) || second.implies(other) || index.isAlias(other)) {
                assertThat("index privilege [" + index + "] should imply [" + other + "]", index.implies(other), is(true));
            } else if (other.implies(first) && other.implies(second)) {
                assertThat("index privilege [" + index + "] should not imply [" + other + "]", index.implies(other), is(false));
            }
        }
    }

    @Test
    public void testIndex_AddCustom() throws Exception {
        Privilege.Index.addCustom("foo", "indices:bar");
        boolean found = false;
        for (Privilege.Index index : Privilege.Index.values()) {
            if ("foo".equals(index.name.toString())) {
                found = true;
                assertThat(index.predicate().apply("indices:bar"), is(true));
            }
        }
        assertThat(found, is(true));
        Privilege.Index index = Privilege.Index.get(new Privilege.Name("foo"));
        assertThat(index, notNullValue());
        assertThat(index.name().toString(), is("foo"));
        assertThat(index.predicate().apply("indices:bar"), is(true));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testIndex_AddCustom_InvalidPattern() throws Exception {
        Privilege.Index.addCustom("foo", "bar");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testIndex_AddCustom_AlreadyExists() throws Exception {
        Privilege.Index.addCustom("all", "bar");
    }

    @Test
    public void testSystem() throws Exception {
        Predicate<String> predicate = Privilege.SYSTEM.predicate();
        assertThat(predicate.apply("indices:monitor/whatever"), is(true));
        assertThat(predicate.apply("cluster:monitor/whatever"), is(true));
        assertThat(predicate.apply("cluster:admin/snapshot/status[nodes]"), is(false));
        assertThat(predicate.apply("internal:whatever"), is(true));
        assertThat(predicate.apply("indices:whatever"), is(false));
        assertThat(predicate.apply("cluster:whatever"), is(false));
        assertThat(predicate.apply("cluster:admin/snapshot/status"), is(false));
        assertThat(predicate.apply("whatever"), is(false));
        assertThat(predicate.apply("cluster:admin/reroute"), is(true));
        assertThat(predicate.apply("cluster:admin/whatever"), is(false));
        assertThat(predicate.apply("indices:admin/mapping/put"), is(true));
        assertThat(predicate.apply("indices:admin/mapping/whatever"), is(false));
    }

    @Test
    public void testSearchPrivilege() throws Exception {
        Predicate<String> predicate = Privilege.Index.SEARCH.predicate();
        assertThat(predicate.apply(SearchAction.NAME), is(true));
        assertThat(predicate.apply(SearchAction.NAME + "/whatever"), is(true));
        assertThat(predicate.apply(MultiSearchAction.NAME), is(true));
        assertThat(predicate.apply(MultiSearchAction.NAME + "/whatever"), is(true));
        assertThat(predicate.apply(SuggestAction.NAME), is(true));
        assertThat(predicate.apply(SuggestAction.NAME + "/whatever"), is(true));

        assertThat(predicate.apply(GetAction.NAME), is(false));
        assertThat(predicate.apply(GetAction.NAME + "/whatever"), is(false));
        assertThat(predicate.apply(MultiGetAction.NAME), is(false));
        assertThat(predicate.apply(MultiGetAction.NAME + "/whatever"), is(false));
    }

    @Test
    public void testGetPrivilege() throws Exception {
        Predicate<String> predicate = Privilege.Index.GET.predicate();
        assertThat(predicate.apply(GetAction.NAME), is(true));
        assertThat(predicate.apply(GetAction.NAME + "/whatever"), is(true));
        assertThat(predicate.apply(MultiGetAction.NAME), is(true));
        assertThat(predicate.apply(MultiGetAction.NAME + "/whatever"), is(true));
    }
}
