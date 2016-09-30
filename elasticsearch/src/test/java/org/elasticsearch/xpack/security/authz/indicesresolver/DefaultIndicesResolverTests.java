/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authz.indicesresolver;

import org.elasticsearch.Version;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesAction;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest.AliasActions;
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesAction;
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexAction;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.get.MultiGetAction;
import org.elasticsearch.action.get.MultiGetRequest;
import org.elasticsearch.action.search.MultiSearchAction;
import org.elasticsearch.action.search.MultiSearchRequest;
import org.elasticsearch.action.search.SearchAction;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.client.Requests;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.AliasMetaData;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.security.SecurityTemplateService;
import org.elasticsearch.xpack.security.authz.store.CompositeRolesStore;
import org.elasticsearch.xpack.security.user.AnonymousUser;
import org.elasticsearch.xpack.security.user.User;
import org.elasticsearch.xpack.security.user.XPackUser;
import org.elasticsearch.xpack.security.audit.AuditTrailService;
import org.elasticsearch.xpack.security.authc.DefaultAuthenticationFailureHandler;
import org.elasticsearch.xpack.security.authz.AuthorizationService;
import org.elasticsearch.xpack.security.authz.permission.Role;
import org.elasticsearch.xpack.security.authz.permission.SuperuserRole;
import org.elasticsearch.xpack.security.authz.privilege.ClusterPrivilege;
import org.elasticsearch.xpack.security.authz.privilege.IndexPrivilege;
import org.junit.Before;

import java.util.Set;

import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DefaultIndicesResolverTests extends ESTestCase {

    private User user;
    private User userNoIndices;
    private CompositeRolesStore rolesStore;
    private MetaData metaData;
    private DefaultIndicesAndAliasesResolver defaultIndicesResolver;
    private IndexNameExpressionResolver indexNameExpressionResolver;

    @Before
    public void setup() {
        Settings settings = Settings.builder()
                .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
                .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, randomIntBetween(1, 2))
                .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, randomIntBetween(0, 1))
                .build();

        indexNameExpressionResolver = new IndexNameExpressionResolver(Settings.EMPTY);
        MetaData.Builder mdBuilder = MetaData.builder()
                .put(indexBuilder("foo").putAlias(AliasMetaData.builder("foofoobar")).settings(settings))
                .put(indexBuilder("foobar").putAlias(AliasMetaData.builder("foofoobar")).settings(settings))
                .put(indexBuilder("closed").state(IndexMetaData.State.CLOSE)
                        .putAlias(AliasMetaData.builder("foofoobar")).settings(settings))
                .put(indexBuilder("foofoo-closed").state(IndexMetaData.State.CLOSE).settings(settings))
                .put(indexBuilder("foobar-closed").state(IndexMetaData.State.CLOSE).settings(settings))
                .put(indexBuilder("foofoo").putAlias(AliasMetaData.builder("barbaz")).settings(settings))
                .put(indexBuilder("bar").settings(settings))
                .put(indexBuilder("bar-closed").state(IndexMetaData.State.CLOSE).settings(settings))
                .put(indexBuilder("bar2").settings(settings))
                .put(indexBuilder(indexNameExpressionResolver.resolveDateMathExpression("<datetime-{now/M}>")).settings(settings))
                .put(indexBuilder(SecurityTemplateService.SECURITY_INDEX_NAME).settings(settings));
        metaData = mdBuilder.build();

        user = new User("user", "role");
        userNoIndices = new User("test", "test");
        rolesStore = mock(CompositeRolesStore.class);
        String[] authorizedIndices = new String[] { "bar", "bar-closed", "foofoobar", "foofoo", "missing", "foofoo-closed" };
        when(rolesStore.role("role")).thenReturn(Role.builder("role").add(IndexPrivilege.ALL, authorizedIndices).build());
        when(rolesStore.role("test")).thenReturn(Role.builder("test").cluster(ClusterPrivilege.MONITOR).build());
        when(rolesStore.role(SuperuserRole.NAME)).thenReturn(Role.builder(SuperuserRole.DESCRIPTOR).build());
        ClusterService clusterService = mock(ClusterService.class);
        ClusterState state = mock(ClusterState.class);
        when(clusterService.state()).thenReturn(state);
        when(state.metaData()).thenReturn(metaData);

        AuthorizationService authzService = new AuthorizationService(settings, rolesStore, clusterService,
                mock(AuditTrailService.class), new DefaultAuthenticationFailureHandler(), mock(ThreadPool.class),
                new AnonymousUser(settings));
        defaultIndicesResolver = new DefaultIndicesAndAliasesResolver(authzService, indexNameExpressionResolver);
    }

    public void testResolveEmptyIndicesExpandWilcardsOpenAndClosed() {
        SearchRequest request = new SearchRequest();
        request.indicesOptions(IndicesOptions.fromOptions(randomBoolean(), randomBoolean(), true, true));
        Set<String> indices = defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData);
        String[] replacedIndices = new String[]{"bar", "bar-closed", "foofoobar", "foofoo", "foofoo-closed"};
        assertThat(indices.size(), equalTo(replacedIndices.length));
        assertThat(request.indices().length, equalTo(replacedIndices.length));
        assertThat(indices, hasItems(replacedIndices));
        assertThat(request.indices(), arrayContainingInAnyOrder(replacedIndices));
    }

    public void testResolveEmptyIndicesExpandWilcardsOpen() {
        SearchRequest request = new SearchRequest();
        request.indicesOptions(IndicesOptions.fromOptions(randomBoolean(), randomBoolean(), true, false));
        Set<String> indices = defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData);
        String[] replacedIndices = new String[]{"bar", "foofoobar", "foofoo"};
        assertThat(indices.size(), equalTo(replacedIndices.length));
        assertThat(request.indices().length, equalTo(replacedIndices.length));
        assertThat(indices, hasItems(replacedIndices));
        assertThat(request.indices(), arrayContainingInAnyOrder(replacedIndices));
    }

    public void testResolveAllExpandWilcardsOpenAndClosed() {
        SearchRequest request = new SearchRequest("_all");
        request.indicesOptions(IndicesOptions.fromOptions(randomBoolean(), randomBoolean(), true, true));
        Set<String> indices = defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData);
        String[] replacedIndices = new String[]{"bar", "bar-closed", "foofoobar", "foofoo", "foofoo-closed"};
        assertThat(indices.size(), equalTo(replacedIndices.length));
        assertThat(request.indices().length, equalTo(replacedIndices.length));
        assertThat(indices, hasItems(replacedIndices));
        assertThat(request.indices(), arrayContainingInAnyOrder(replacedIndices));
    }

    public void testResolveAllExpandWilcardsOpen() {
        SearchRequest request = new SearchRequest("_all");
        request.indicesOptions(IndicesOptions.fromOptions(randomBoolean(), randomBoolean(), true, false));
        Set<String> indices = defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData);
        String[] replacedIndices = new String[]{"bar", "foofoobar", "foofoo"};
        assertThat(indices.size(), equalTo(replacedIndices.length));
        assertThat(request.indices().length, equalTo(replacedIndices.length));
        assertThat(indices, hasItems(replacedIndices));
        assertThat(request.indices(), arrayContainingInAnyOrder(replacedIndices));
    }

    public void testResolveWildcardsStrictExpand() {
        SearchRequest request = new SearchRequest("barbaz", "foofoo*");
        request.indicesOptions(IndicesOptions.fromOptions(false, randomBoolean(), true, true));
        Set<String> indices = defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData);
        String[] replacedIndices = new String[]{"barbaz", "foofoobar", "foofoo", "foofoo-closed"};
        assertThat(indices.size(), equalTo(replacedIndices.length));
        assertThat(request.indices().length, equalTo(replacedIndices.length));
        assertThat(indices, hasItems(replacedIndices));
        assertThat(request.indices(), arrayContainingInAnyOrder(replacedIndices));
    }

    public void testResolveWildcardsExpandOpenAndClosedIgnoreUnavailable() {
        SearchRequest request = new SearchRequest("barbaz", "foofoo*");
        request.indicesOptions(IndicesOptions.fromOptions(true, randomBoolean(), true, true));
        Set<String> indices = defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData);
        String[] replacedIndices = new String[]{"foofoobar", "foofoo", "foofoo-closed"};
        assertThat(indices.size(), equalTo(replacedIndices.length));
        assertThat(request.indices().length, equalTo(replacedIndices.length));
        assertThat(indices, hasItems(replacedIndices));
        assertThat(request.indices(), arrayContainingInAnyOrder(replacedIndices));
    }

    public void testResolveWildcardsStrictExpandOpen() {
        SearchRequest request = new SearchRequest("barbaz", "foofoo*");
        request.indicesOptions(IndicesOptions.fromOptions(false, randomBoolean(), true, false));
        Set<String> indices = defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData);
        String[] replacedIndices = new String[]{"barbaz", "foofoobar", "foofoo"};
        assertThat(indices.size(), equalTo(replacedIndices.length));
        assertThat(request.indices().length, equalTo(replacedIndices.length));
        assertThat(indices, hasItems(replacedIndices));
        assertThat(request.indices(), arrayContainingInAnyOrder(replacedIndices));
    }

    public void testResolveWildcardsLenientExpandOpen() {
        SearchRequest request = new SearchRequest("barbaz", "foofoo*");
        request.indicesOptions(IndicesOptions.fromOptions(true, randomBoolean(), true, false));
        Set<String> indices = defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData);
        String[] replacedIndices = new String[]{"foofoobar", "foofoo"};
        assertThat(indices.size(), equalTo(replacedIndices.length));
        assertThat(request.indices().length, equalTo(replacedIndices.length));
        assertThat(indices, hasItems(replacedIndices));
        assertThat(request.indices(), arrayContainingInAnyOrder(replacedIndices));
    }

    public void testResolveWildcardsMinusExpandWilcardsOpen() {
        SearchRequest request = new SearchRequest("-foofoo*");
        request.indicesOptions(IndicesOptions.fromOptions(randomBoolean(), randomBoolean(), true, false));
        Set<String> indices = defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData);
        String[] replacedIndices = new String[]{"bar"};
        assertThat(indices.size(), equalTo(replacedIndices.length));
        assertThat(request.indices().length, equalTo(replacedIndices.length));
        assertThat(indices, hasItems(replacedIndices));
        assertThat(request.indices(), arrayContainingInAnyOrder(replacedIndices));
    }

    public void testResolveWildcardsMinusExpandWilcardsOpenAndClosed() {
        SearchRequest request = new SearchRequest("-foofoo*");
        request.indicesOptions(IndicesOptions.fromOptions(randomBoolean(), randomBoolean(), true, true));
        Set<String> indices = defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData);
        String[] replacedIndices = new String[]{"bar", "bar-closed"};
        assertThat(indices.size(), equalTo(replacedIndices.length));
        assertThat(request.indices().length, equalTo(replacedIndices.length));
        assertThat(indices, hasItems(replacedIndices));
        assertThat(request.indices(), arrayContainingInAnyOrder(replacedIndices));
    }

    public void testResolveWildcardsPlusAndMinusExpandWilcardsOpenStrict() {
        SearchRequest request = new SearchRequest("-foofoo*", "+barbaz", "+foob*");
        request.indicesOptions(IndicesOptions.fromOptions(false, true, true, false));
        Set<String> indices = defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData);
        String[] replacedIndices = new String[]{"bar", "barbaz"};
        assertThat(indices.size(), equalTo(replacedIndices.length));
        assertThat(request.indices().length, equalTo(replacedIndices.length));
        assertThat(indices, hasItems(replacedIndices));
        assertThat(request.indices(), arrayContainingInAnyOrder(replacedIndices));
    }

    public void testResolveWildcardsPlusAndMinusExpandWilcardsOpenIgnoreUnavailable() {
        SearchRequest request = new SearchRequest("-foofoo*", "+barbaz", "+foob*");
        request.indicesOptions(IndicesOptions.fromOptions(true, true, true, false));
        Set<String> indices = defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData);
        String[] replacedIndices = new String[]{"bar"};
        assertThat(indices.size(), equalTo(replacedIndices.length));
        assertThat(request.indices().length, equalTo(replacedIndices.length));
        assertThat(indices, hasItems(replacedIndices));
        assertThat(request.indices(), arrayContainingInAnyOrder(replacedIndices));
    }

    public void testResolveWildcardsPlusAndMinusExpandWilcardsOpenAndClosedStrict() {
        SearchRequest request = new SearchRequest("-foofoo*", "+barbaz");
        request.indicesOptions(IndicesOptions.fromOptions(false, randomBoolean(), true, true));
        Set<String> indices = defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData);
        String[] replacedIndices = new String[]{"bar", "bar-closed", "barbaz"};
        assertThat(indices.size(), equalTo(replacedIndices.length));
        assertThat(request.indices().length, equalTo(replacedIndices.length));
        assertThat(indices, hasItems(replacedIndices));
        assertThat(request.indices(), arrayContainingInAnyOrder(replacedIndices));
    }

    public void testResolveWildcardsPlusAndMinusExpandWilcardsOpenAndClosedIgnoreUnavailable() {
        SearchRequest request = new SearchRequest("-foofoo*", "+barbaz");
        request.indicesOptions(IndicesOptions.fromOptions(true, randomBoolean(), true, true));
        Set<String> indices = defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData);
        String[] replacedIndices = new String[]{"bar", "bar-closed"};
        assertThat(indices.size(), equalTo(replacedIndices.length));
        assertThat(request.indices().length, equalTo(replacedIndices.length));
        assertThat(indices, hasItems(replacedIndices));
        assertThat(request.indices(), arrayContainingInAnyOrder(replacedIndices));
    }

    public void testResolveNonMatchingIndicesAllowNoIndices() {
        SearchRequest request = new SearchRequest("missing*");
        request.indicesOptions(IndicesOptions.fromOptions(randomBoolean(), true, true, randomBoolean()));
        assertNoIndices(request, defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData));
    }

    public void testResolveNonMatchingIndicesDisallowNoIndices() {
        SearchRequest request = new SearchRequest("missing*");
        request.indicesOptions(IndicesOptions.fromOptions(randomBoolean(), false, true, randomBoolean()));
        IndexNotFoundException e = expectThrows(IndexNotFoundException.class,
                () -> defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData));
        assertEquals("no such index", e.getMessage());
    }

    public void testResolveExplicitIndicesStrict() {
        SearchRequest request = new SearchRequest("missing", "bar", "barbaz");
        request.indicesOptions(IndicesOptions.fromOptions(false, randomBoolean(), randomBoolean(), randomBoolean()));
        Set<String> indices = defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData);
        String[] replacedIndices = new String[]{"missing", "bar", "barbaz"};
        assertThat(indices.size(), equalTo(replacedIndices.length));
        assertThat(request.indices().length, equalTo(replacedIndices.length));
        assertThat(indices, hasItems(replacedIndices));
        assertThat(request.indices(), arrayContainingInAnyOrder(replacedIndices));
    }

    public void testResolveExplicitIndicesIgnoreUnavailable() {
        SearchRequest request = new SearchRequest("missing", "bar", "barbaz");
        request.indicesOptions(IndicesOptions.fromOptions(true, randomBoolean(), randomBoolean(), randomBoolean()));
        Set<String> indices = defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData);
        String[] replacedIndices = new String[]{"bar"};
        assertThat(indices.size(), equalTo(replacedIndices.length));
        assertThat(request.indices().length, equalTo(replacedIndices.length));
        assertThat(indices, hasItems(replacedIndices));
        assertThat(request.indices(), arrayContainingInAnyOrder(replacedIndices));
    }

    public void testResolveNoAuthorizedIndicesAllowNoIndices() {
        SearchRequest request = new SearchRequest();
        request.indicesOptions(IndicesOptions.fromOptions(randomBoolean(), true, true, randomBoolean()));
        assertNoIndices(request, defaultIndicesResolver.resolve(userNoIndices, SearchAction.NAME, request, metaData));
    }

    public void testResolveNoAuthorizedIndicesDisallowNoIndices() {
        SearchRequest request = new SearchRequest();
        request.indicesOptions(IndicesOptions.fromOptions(randomBoolean(), false, true, randomBoolean()));
        IndexNotFoundException e = expectThrows(IndexNotFoundException.class,
                () -> defaultIndicesResolver.resolve(userNoIndices, SearchAction.NAME, request, metaData));
        assertEquals("no such index", e.getMessage());
    }

    public void testResolveMissingIndexStrict() {
        SearchRequest request = new SearchRequest("bar*", "missing");
        request.indicesOptions(IndicesOptions.fromOptions(false, true, true, false));
        Set<String> indices = defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData);
        String[] expectedIndices = new String[]{"bar", "missing"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(request.indices().length, equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        assertThat(request.indices(), equalTo(expectedIndices));
    }

    public void testResolveMissingIndexIgnoreUnavailable() {
        SearchRequest request = new SearchRequest("bar*", "missing");
        request.indicesOptions(IndicesOptions.fromOptions(true, randomBoolean(), true, false));
        Set<String> indices = defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData);
        String[] expectedIndices = new String[]{"bar"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(request.indices().length, equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        assertThat(request.indices(), equalTo(expectedIndices));
    }

    public void testResolveNonMatchingIndicesAndExplicit() {
        SearchRequest request = new SearchRequest("missing*", "bar");
        request.indicesOptions(IndicesOptions.fromOptions(randomBoolean(), true, true, randomBoolean()));
        Set<String> indices = defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData);
        String[] expectedIndices = new String[]{"bar"};
        assertThat(indices.toArray(new String[indices.size()]), equalTo(expectedIndices));
        assertThat(request.indices(), equalTo(expectedIndices));
    }

    public void testResolveNoExpandStrict() {
        SearchRequest request = new SearchRequest("missing*");
        request.indicesOptions(IndicesOptions.fromOptions(false, randomBoolean(), false, false));
        Set<String> indices = defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData);
        String[] expectedIndices = new String[]{"missing*"};
        assertThat(indices.toArray(new String[indices.size()]), equalTo(expectedIndices));
        assertThat(request.indices(), equalTo(expectedIndices));
    }

    public void testResolveNoExpandIgnoreUnavailable() {
        SearchRequest request = new SearchRequest("missing*");
        request.indicesOptions(IndicesOptions.fromOptions(true, true, false, false));
        assertNoIndices(request, defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData));
    }

    public void testResolveIndicesAliasesRequest() {
        IndicesAliasesRequest request = new IndicesAliasesRequest();
        request.addAliasAction(AliasActions.add().alias("alias1").indices("foo", "foofoo"));
        request.addAliasAction(AliasActions.add().alias("alias2").indices("foo", "foobar"));
        Set<String> indices = defaultIndicesResolver.resolve(user, IndicesAliasesAction.NAME, request, metaData);
        //the union of all indices and aliases gets returned
        String[] expectedIndices = new String[]{"alias1", "alias2", "foo", "foofoo", "foobar"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        assertThat(request.getAliasActions().get(0).indices(), arrayContainingInAnyOrder("foo", "foofoo"));
        assertThat(request.getAliasActions().get(0).aliases(), arrayContainingInAnyOrder("alias1"));
        assertThat(request.getAliasActions().get(1).indices(), arrayContainingInAnyOrder("foo", "foobar"));
        assertThat(request.getAliasActions().get(1).aliases(), arrayContainingInAnyOrder("alias2"));
    }

    public void testResolveIndicesAliasesRequestExistingAlias() {
        IndicesAliasesRequest request = new IndicesAliasesRequest();
        request.addAliasAction(AliasActions.add().alias("alias1").indices("foo", "foofoo"));
        request.addAliasAction(AliasActions.add().alias("foofoobar").indices("foo", "foobar"));
        Set<String> indices = defaultIndicesResolver.resolve(user, IndicesAliasesAction.NAME, request, metaData);
        //the union of all indices and aliases gets returned, foofoobar is an existing alias but that doesn't make any difference
        String[] expectedIndices = new String[]{"alias1", "foofoobar", "foo", "foofoo", "foobar"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        assertThat(request.getAliasActions().get(0).indices(), arrayContainingInAnyOrder("foo", "foofoo"));
        assertThat(request.getAliasActions().get(0).aliases(), arrayContainingInAnyOrder("alias1"));
        assertThat(request.getAliasActions().get(1).indices(), arrayContainingInAnyOrder("foo", "foobar"));
        assertThat(request.getAliasActions().get(1).aliases(), arrayContainingInAnyOrder("foofoobar"));
    }

    public void testResolveIndicesAliasesRequestMissingIndex() {
        IndicesAliasesRequest request = new IndicesAliasesRequest();
        request.addAliasAction(AliasActions.add().alias("alias1").indices("foo", "foofoo"));
        request.addAliasAction(AliasActions.add().alias("alias2").index("missing"));
        Set<String> indices = defaultIndicesResolver.resolve(user, IndicesAliasesAction.NAME, request, metaData);
        //the union of all indices and aliases gets returned, missing is not an existing index/alias but that doesn't make any difference
        String[] expectedIndices = new String[]{"alias1", "alias2", "foo", "foofoo", "missing"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        assertThat(request.getAliasActions().get(0).indices(), arrayContainingInAnyOrder("foo", "foofoo"));
        assertThat(request.getAliasActions().get(0).aliases(), arrayContainingInAnyOrder("alias1"));
        assertThat(request.getAliasActions().get(1).indices(), arrayContainingInAnyOrder("missing"));
        assertThat(request.getAliasActions().get(1).aliases(), arrayContainingInAnyOrder("alias2"));
    }

    public void testResolveWildcardsIndicesAliasesRequest() {
        IndicesAliasesRequest request = new IndicesAliasesRequest();
        request.addAliasAction(AliasActions.add().alias("alias1").index("foo*"));
        request.addAliasAction(AliasActions.add().alias("alias2").index("bar*"));
        Set<String> indices = defaultIndicesResolver.resolve(user, IndicesAliasesAction.NAME, request, metaData);
        //the union of all resolved indices and aliases gets returned, based on indices and aliases that user is authorized for
        String[] expectedIndices = new String[]{"alias1", "alias2", "foofoo", "foofoobar", "bar"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        //wildcards get replaced on each single action
        assertThat(request.getAliasActions().get(0).indices(), arrayContainingInAnyOrder("foofoobar", "foofoo"));
        assertThat(request.getAliasActions().get(0).aliases(), arrayContainingInAnyOrder("alias1"));
        assertThat(request.getAliasActions().get(1).indices(), arrayContainingInAnyOrder("bar"));
        assertThat(request.getAliasActions().get(1).aliases(), arrayContainingInAnyOrder("alias2"));
    }

    public void testResolveWildcardsIndicesAliasesRequestNoMatchingIndices() {
        IndicesAliasesRequest request = new IndicesAliasesRequest();
        request.addAliasAction(AliasActions.add().alias("alias1").index("foo*"));
        request.addAliasAction(AliasActions.add().alias("alias2").index("bar*"));
        request.addAliasAction(AliasActions.add().alias("alias3").index("non_matching_*"));
        //if a single operation contains wildcards and ends up being resolved to no indices, it makes the whole request fail
        try {
            defaultIndicesResolver.resolve(user, IndicesAliasesAction.NAME, request, metaData);
            fail("Expected IndexNotFoundException");
        } catch (IndexNotFoundException e) {
            assertThat(e.getMessage(), is("no such index"));
        }
    }

    public void testResolveAllIndicesAliasesRequest() {
        IndicesAliasesRequest request = new IndicesAliasesRequest();
        request.addAliasAction(AliasActions.add().alias("alias1").index("_all"));
        request.addAliasAction(AliasActions.add().alias("alias2").index("_all"));
        Set<String> indices = defaultIndicesResolver.resolve(user, IndicesAliasesAction.NAME, request, metaData);
        //the union of all resolved indices and aliases gets returned
        String[] expectedIndices = new String[]{"bar", "foofoobar", "foofoo", "alias1", "alias2"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        String[] replacedIndices = new String[]{"bar", "foofoobar", "foofoo"};
        //_all gets replaced with all indices that user is authorized for, on each single action
        assertThat(request.getAliasActions().get(0).indices(), arrayContainingInAnyOrder(replacedIndices));
        assertThat(request.getAliasActions().get(0).aliases(), arrayContainingInAnyOrder("alias1"));
        assertThat(request.getAliasActions().get(1).indices(), arrayContainingInAnyOrder(replacedIndices));
        assertThat(request.getAliasActions().get(1).aliases(), arrayContainingInAnyOrder("alias2"));
    }

    public void testResolveAllIndicesAliasesRequestNoAuthorizedIndices() {
        IndicesAliasesRequest request = new IndicesAliasesRequest();
        request.addAliasAction(AliasActions.add().alias("alias1").index("_all"));
        //current user is not authorized for any index, _all resolves to no indices, the request fails
        try {
            defaultIndicesResolver.resolve(userNoIndices, IndicesAliasesAction.NAME, request, metaData);
            fail("Expected IndexNotFoundException");
        } catch (IndexNotFoundException e) {
            assertThat(e.getMessage(), is("no such index"));
        }
    }

    public void testResolveWildcardsIndicesAliasesRequestNoAuthorizedIndices() {
        IndicesAliasesRequest request = new IndicesAliasesRequest();
        request.addAliasAction(AliasActions.add().alias("alias1").index("foo*"));
        //current user is not authorized for any index, foo* resolves to no indices, the request fails
        try {
            defaultIndicesResolver.resolve(userNoIndices, IndicesAliasesAction.NAME, request, metaData);
            fail("Expected IndexNotFoundException");
        } catch (IndexNotFoundException e) {
            assertThat(e.getMessage(), is("no such index"));
        }
    }

    public void testResolveIndicesAliasesRequestDeleteActions() {
        IndicesAliasesRequest request = new IndicesAliasesRequest();
        request.addAliasAction(AliasActions.remove().index("foo").alias("foofoobar"));
        request.addAliasAction(AliasActions.remove().index("foofoo").alias("barbaz"));
        Set<String> indices = defaultIndicesResolver.resolve(user, IndicesAliasesAction.NAME, request, metaData);
        //the union of all indices and aliases gets returned
        String[] expectedIndices = new String[]{"foo", "foofoobar", "foofoo", "barbaz"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        assertThat(request.getAliasActions().get(0).indices(), arrayContainingInAnyOrder("foo"));
        assertThat(request.getAliasActions().get(0).aliases(), arrayContainingInAnyOrder("foofoobar"));
        assertThat(request.getAliasActions().get(1).indices(), arrayContainingInAnyOrder("foofoo"));
        assertThat(request.getAliasActions().get(1).aliases(), arrayContainingInAnyOrder("barbaz"));
    }

    public void testResolveIndicesAliasesRequestDeleteActionsMissingIndex() {
        IndicesAliasesRequest request = new IndicesAliasesRequest();
        request.addAliasAction(AliasActions.remove().index("foo").alias("foofoobar"));
        request.addAliasAction(AliasActions.remove().index("missing_index").alias("missing_alias"));
        Set<String> indices = defaultIndicesResolver.resolve(user, IndicesAliasesAction.NAME, request, metaData);
        //the union of all indices and aliases gets returned, doesn't matter is some of them don't exist
        String[] expectedIndices = new String[]{"foo", "foofoobar", "missing_index", "missing_alias"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        assertThat(request.getAliasActions().get(0).indices(), arrayContainingInAnyOrder("foo"));
        assertThat(request.getAliasActions().get(0).aliases(), arrayContainingInAnyOrder("foofoobar"));
        assertThat(request.getAliasActions().get(1).indices(), arrayContainingInAnyOrder("missing_index"));
        assertThat(request.getAliasActions().get(1).aliases(), arrayContainingInAnyOrder("missing_alias"));
    }

    public void testResolveWildcardsIndicesAliasesRequestDeleteActions() {
        IndicesAliasesRequest request = new IndicesAliasesRequest();
        request.addAliasAction(AliasActions.remove().index("foo*").alias("foofoobar"));
        request.addAliasAction(AliasActions.remove().index("bar*").alias("barbaz"));
        Set<String> indices = defaultIndicesResolver.resolve(user, IndicesAliasesAction.NAME, request, metaData);
        //union of all resolved indices and aliases gets returned, based on what user is authorized for
        String[] expectedIndices = new String[]{"foofoobar", "foofoo", "bar", "barbaz"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        //wildcards get replaced within each single action
        assertThat(request.getAliasActions().get(0).indices(), arrayContainingInAnyOrder("foofoobar", "foofoo"));
        assertThat(request.getAliasActions().get(0).aliases(), arrayContainingInAnyOrder("foofoobar"));
        assertThat(request.getAliasActions().get(1).indices(), arrayContainingInAnyOrder("bar"));
        assertThat(request.getAliasActions().get(1).aliases(), arrayContainingInAnyOrder("barbaz"));
    }

    public void testResolveAliasesWildcardsIndicesAliasesRequestDeleteActions() {
        IndicesAliasesRequest request = new IndicesAliasesRequest();
        request.addAliasAction(AliasActions.remove().index("*").alias("foo*"));
        request.addAliasAction(AliasActions.remove().index("*bar").alias("foo*"));
        Set<String> indices = defaultIndicesResolver.resolve(user, IndicesAliasesAction.NAME, request, metaData);
        //union of all resolved indices and aliases gets returned, based on what user is authorized for
        //note that the index side will end up containing matching aliases too, which is fine, as es core would do
        //the same and resolve those aliases to their corresponding concrete indices (which we let core do)
        String[] expectedIndices = new String[]{"bar", "foofoobar", "foofoo"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        //alias foofoobar on both sides, that's fine, es core would do the same, same as above
        assertThat(request.getAliasActions().get(0).indices(), arrayContainingInAnyOrder("bar", "foofoobar", "foofoo"));
        assertThat(request.getAliasActions().get(0).aliases(), arrayContainingInAnyOrder("foofoobar"));
        assertThat(request.getAliasActions().get(1).indices(), arrayContainingInAnyOrder("bar", "foofoobar"));
        assertThat(request.getAliasActions().get(1).aliases(), arrayContainingInAnyOrder("foofoobar"));
    }

    public void testResolveAllAliasesWildcardsIndicesAliasesRequestDeleteActions() {
        IndicesAliasesRequest request = new IndicesAliasesRequest();
        request.addAliasAction(AliasActions.remove().index("*").alias("_all"));
        request.addAliasAction(AliasActions.remove().index("_all").aliases("_all", "explicit"));
        Set<String> indices = defaultIndicesResolver.resolve(user, IndicesAliasesAction.NAME, request, metaData);
        //union of all resolved indices and aliases gets returned, based on what user is authorized for
        //note that the index side will end up containing matching aliases too, which is fine, as es core would do
        //the same and resolve those aliases to their corresponding concrete indices (which we let core do)
        String[] expectedIndices = new String[]{"bar", "foofoobar", "foofoo", "explicit"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        //alias foofoobar on both sides, that's fine, es core would do the same, same as above
        assertThat(request.getAliasActions().get(0).indices(), arrayContainingInAnyOrder("bar", "foofoobar", "foofoo"));
        assertThat(request.getAliasActions().get(0).aliases(), arrayContainingInAnyOrder("foofoobar"));
        assertThat(request.getAliasActions().get(0).indices(), arrayContainingInAnyOrder("bar", "foofoobar", "foofoo"));
        assertThat(request.getAliasActions().get(1).aliases(), arrayContainingInAnyOrder("foofoobar", "explicit"));
    }

    public void testResolveAliasesWildcardsIndicesAliasesRequestDeleteActionsNoAuthorizedIndices() {
        IndicesAliasesRequest request = new IndicesAliasesRequest();
        request.addAliasAction(AliasActions.remove().index("foo*").alias("foo*"));
        //no authorized aliases match bar*, hence this action fails and makes the whole request fail
        request.addAliasAction(AliasActions.remove().index("*bar").alias("bar*"));
        try {
            defaultIndicesResolver.resolve(user, IndicesAliasesAction.NAME, request, metaData);
            fail("Expected IndexNotFoundException");
        } catch (IndexNotFoundException e) {
            assertThat(e.getMessage(), is("no such index"));
        }
    }

    public void testResolveWildcardsIndicesAliasesRequestAddAndDeleteActions() {
        IndicesAliasesRequest request = new IndicesAliasesRequest();
        request.addAliasAction(AliasActions.remove().index("foo*").alias("foofoobar"));
        request.addAliasAction(AliasActions.add().index("bar*").alias("foofoobar"));
        Set<String> indices = defaultIndicesResolver.resolve(user, IndicesAliasesAction.NAME, request, metaData);
        //union of all resolved indices and aliases gets returned, based on what user is authorized for
        String[] expectedIndices = new String[]{"foofoobar", "foofoo", "bar"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        //every single action has its indices replaced with matching (authorized) ones
        assertThat(request.getAliasActions().get(0).indices(), arrayContainingInAnyOrder("foofoobar", "foofoo"));
        assertThat(request.getAliasActions().get(0).aliases(), arrayContainingInAnyOrder("foofoobar"));
        assertThat(request.getAliasActions().get(1).indices(), arrayContainingInAnyOrder("bar"));
        assertThat(request.getAliasActions().get(1).aliases(), arrayContainingInAnyOrder("foofoobar"));
    }

    public void testResolveGetAliasesRequestStrict() {
        GetAliasesRequest request = new GetAliasesRequest("alias1").indices("foo", "foofoo");
        request.indicesOptions(IndicesOptions.fromOptions(false, randomBoolean(), randomBoolean(), randomBoolean()));
        Set<String> indices = defaultIndicesResolver.resolve(user, GetAliasesAction.NAME, request, metaData);
        //the union of all indices and aliases gets returned
        String[] expectedIndices = new String[]{"alias1", "foo", "foofoo"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        assertThat(request.indices(), arrayContainingInAnyOrder("foo", "foofoo"));
        assertThat(request.aliases(), arrayContainingInAnyOrder("alias1"));
    }

    public void testResolveGetAliasesRequestIgnoreUnavailable() {
        GetAliasesRequest request = new GetAliasesRequest("alias1").indices("foo", "foofoo");
        request.indicesOptions(IndicesOptions.fromOptions(true, randomBoolean(), randomBoolean(), randomBoolean()));
        Set<String> indices = defaultIndicesResolver.resolve(user, GetAliasesAction.NAME, request, metaData);
        String[] expectedIndices = new String[]{"alias1", "foofoo"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        assertThat(request.indices(), arrayContainingInAnyOrder("foofoo"));
        assertThat(request.aliases(), arrayContainingInAnyOrder("alias1"));
    }

    public void testResolveGetAliasesRequestMissingIndexStrict() {
        GetAliasesRequest request = new GetAliasesRequest();
        request.indicesOptions(IndicesOptions.fromOptions(false, randomBoolean(), true, randomBoolean()));
        request.indices("missing");
        request.aliases("alias2");
        Set<String> indices = defaultIndicesResolver.resolve(user, GetAliasesAction.NAME, request, metaData);
        //the union of all indices and aliases gets returned, missing is not an existing index/alias but that doesn't make any difference
        String[] expectedIndices = new String[]{"alias2", "missing"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        assertThat(request.indices(), arrayContainingInAnyOrder("missing"));
        assertThat(request.aliases(), arrayContainingInAnyOrder("alias2"));
    }

    public void testGetAliasesRequestMissingIndexIgnoreUnavailableDisallowNoIndices() {
        GetAliasesRequest request = new GetAliasesRequest();
        request.indicesOptions(IndicesOptions.fromOptions(true, false, randomBoolean(), randomBoolean()));
        request.indices("missing");
        request.aliases("alias2");
        IndexNotFoundException exception = expectThrows(IndexNotFoundException.class,
                () -> defaultIndicesResolver.resolve(user, GetAliasesAction.NAME, request, metaData));
        assertEquals("no such index", exception.getMessage());
    }

    public void testGetAliasesRequestMissingIndexIgnoreUnavailableAllowNoIndices() {
        GetAliasesRequest request = new GetAliasesRequest();
        request.indicesOptions(IndicesOptions.fromOptions(true, true, randomBoolean(), randomBoolean()));
        request.indices("missing");
        request.aliases("alias2");
        assertNoIndices(request, defaultIndicesResolver.resolve(user, GetAliasesAction.NAME, request, metaData));
    }

    public void testGetAliasesRequestMissingIndexStrict() {
        GetAliasesRequest request = new GetAliasesRequest();
        request.indicesOptions(IndicesOptions.fromOptions(false, randomBoolean(), randomBoolean(), randomBoolean()));
        request.indices("missing");
        request.aliases("alias2");
        Set<String> indices = defaultIndicesResolver.resolve(user, GetAliasesAction.NAME, request, metaData);
        String[] expectedIndices = new String[]{"alias2", "missing"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        assertThat(request.indices(), arrayContainingInAnyOrder("missing"));
        assertThat(request.aliases(), arrayContainingInAnyOrder("alias2"));
    }

    public void testResolveWildcardsGetAliasesRequestStrictExpand() {
        GetAliasesRequest request = new GetAliasesRequest();
        request.indicesOptions(IndicesOptions.fromOptions(false, randomBoolean(), true, true));
        request.aliases("alias1");
        request.indices("foo*");
        Set<String> indices = defaultIndicesResolver.resolve(user, GetAliasesAction.NAME, request, metaData);
        //the union of all resolved indices and aliases gets returned, based on indices and aliases that user is authorized for
        String[] expectedIndices = new String[]{"alias1", "foofoo", "foofoo-closed", "foofoobar"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        //wildcards get replaced on each single action
        assertThat(request.indices(), arrayContainingInAnyOrder("foofoobar", "foofoo", "foofoo-closed"));
        assertThat(request.aliases(), arrayContainingInAnyOrder("alias1"));
    }

    public void testResolveWildcardsGetAliasesRequestStrictExpandOpen() {
        GetAliasesRequest request = new GetAliasesRequest();
        request.indicesOptions(IndicesOptions.fromOptions(false, randomBoolean(), true, false));
        request.aliases("alias1");
        request.indices("foo*");
        Set<String> indices = defaultIndicesResolver.resolve(user, GetAliasesAction.NAME, request, metaData);
        //the union of all resolved indices and aliases gets returned, based on indices and aliases that user is authorized for
        String[] expectedIndices = new String[]{"alias1", "foofoo", "foofoobar"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        //wildcards get replaced on each single action
        assertThat(request.indices(), arrayContainingInAnyOrder("foofoobar", "foofoo"));
        assertThat(request.aliases(), arrayContainingInAnyOrder("alias1"));
    }

    public void testResolveWildcardsGetAliasesRequestLenientExpandOpen() {
        GetAliasesRequest request = new GetAliasesRequest();
        request.indicesOptions(IndicesOptions.fromOptions(true, randomBoolean(), true, false));
        request.aliases("alias1");
        request.indices("foo*", "bar", "missing");
        Set<String> indices = defaultIndicesResolver.resolve(user, GetAliasesAction.NAME, request, metaData);
        //the union of all resolved indices and aliases gets returned, based on indices and aliases that user is authorized for
        String[] expectedIndices = new String[]{"alias1", "foofoo", "foofoobar", "bar"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        //wildcards get replaced on each single action
        assertThat(request.indices(), arrayContainingInAnyOrder("foofoobar", "foofoo", "bar"));
        assertThat(request.aliases(), arrayContainingInAnyOrder("alias1"));
    }

    public void testWildcardsGetAliasesRequestNoMatchingIndicesDisallowNoIndices() {
        GetAliasesRequest request = new GetAliasesRequest();
        request.indicesOptions(IndicesOptions.fromOptions(randomBoolean(), false, true, randomBoolean()));
        request.aliases("alias3");
        request.indices("non_matching_*");
        IndexNotFoundException e = expectThrows(IndexNotFoundException.class,
                () -> defaultIndicesResolver.resolve(user, GetAliasesAction.NAME, request, metaData));
        assertEquals("no such index", e.getMessage());
    }

    public void testWildcardsGetAliasesRequestNoMatchingIndicesAllowNoIndices() {
        GetAliasesRequest request = new GetAliasesRequest();
        request.indicesOptions(IndicesOptions.fromOptions(randomBoolean(), true, true, randomBoolean()));
        request.aliases("alias3");
        request.indices("non_matching_*");
        assertNoIndices(request, defaultIndicesResolver.resolve(user, GetAliasesAction.NAME, request, metaData));
    }

    public void testResolveAllGetAliasesRequest() {
        GetAliasesRequest request = new GetAliasesRequest();
        //even if not set, empty means _all
        if (randomBoolean()) {
            request.indices("_all");
        }
        request.aliases("alias1");
        Set<String> indices = defaultIndicesResolver.resolve(user, GetAliasesAction.NAME, request, metaData);
        //the union of all resolved indices and aliases gets returned
        String[] expectedIndices = new String[]{"bar", "bar-closed", "foofoobar", "foofoo", "foofoo-closed", "alias1"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        String[] replacedIndices = new String[]{"bar", "bar-closed", "foofoobar", "foofoo", "foofoo-closed"};
        //_all gets replaced with all indices that user is authorized for
        assertThat(request.indices(), arrayContainingInAnyOrder(replacedIndices));
        assertThat(request.aliases(), arrayContainingInAnyOrder("alias1"));
    }

    public void testResolveAllGetAliasesRequestExpandWildcardsOpenOnly() {
        GetAliasesRequest request = new GetAliasesRequest();
        //set indices options to have wildcards resolved to open indices only (default is open and closed)
        request.indicesOptions(IndicesOptions.fromOptions(true, false, true, false));
        //even if not set, empty means _all
        if (randomBoolean()) {
            request.indices("_all");
        }
        request.aliases("alias1");
        Set<String> indices = defaultIndicesResolver.resolve(user, GetAliasesAction.NAME, request, metaData);
        //the union of all resolved indices and aliases gets returned
        String[] expectedIndices = new String[]{"bar", "foofoobar", "foofoo", "alias1"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        String[] replacedIndices = new String[]{"bar", "foofoobar", "foofoo"};
        //_all gets replaced with all indices that user is authorized for
        assertThat(request.indices(), arrayContainingInAnyOrder(replacedIndices));
        assertThat(request.aliases(), arrayContainingInAnyOrder("alias1"));
    }

    public void testAllGetAliasesRequestNoAuthorizedIndicesAllowNoIndices() {
        GetAliasesRequest request = new GetAliasesRequest();
        request.indicesOptions(IndicesOptions.fromOptions(randomBoolean(), true, true, randomBoolean()));
        request.aliases("alias1");
        request.indices("_all");
        assertNoIndices(request, defaultIndicesResolver.resolve(userNoIndices, GetAliasesAction.NAME, request, metaData));
    }

    public void testAllGetAliasesRequestNoAuthorizedIndicesDisallowNoIndices() {
        GetAliasesRequest request = new GetAliasesRequest();
        request.indicesOptions(IndicesOptions.fromOptions(randomBoolean(), false, true, randomBoolean()));
        request.aliases("alias1");
        request.indices("_all");
        IndexNotFoundException e = expectThrows(IndexNotFoundException.class,
                () -> defaultIndicesResolver.resolve(userNoIndices, GetAliasesAction.NAME, request, metaData));
        assertEquals("no such index", e.getMessage());
    }

    public void testWildcardsGetAliasesRequestNoAuthorizedIndicesAllowNoIndices() {
        GetAliasesRequest request = new GetAliasesRequest();
        request.aliases("alias1");
        request.indices("foo*");
        request.indicesOptions(IndicesOptions.fromOptions(randomBoolean(), true, true, randomBoolean()));
        assertNoIndices(request, defaultIndicesResolver.resolve(userNoIndices, GetAliasesAction.NAME, request, metaData));
    }

    public void testWildcardsGetAliasesRequestNoAuthorizedIndicesDisallowNoIndices() {
        GetAliasesRequest request = new GetAliasesRequest();
        request.indicesOptions(IndicesOptions.fromOptions(randomBoolean(), false, true, randomBoolean()));
        request.aliases("alias1");
        request.indices("foo*");
        //current user is not authorized for any index, foo* resolves to no indices, the request fails
        IndexNotFoundException e = expectThrows(IndexNotFoundException.class,
                () -> defaultIndicesResolver.resolve(userNoIndices, GetAliasesAction.NAME, request, metaData));
        assertEquals("no such index", e.getMessage());
    }

    public void testResolveAllAliasesGetAliasesRequest() {
        GetAliasesRequest request = new GetAliasesRequest();
        if (randomBoolean()) {
            request.aliases("_all");
        }
        if (randomBoolean()) {
            request.indices("_all");
        }
        Set<String> indices = defaultIndicesResolver.resolve(user, GetAliasesAction.NAME, request, metaData);
        //the union of all resolved indices and aliases gets returned
        String[] expectedIndices = new String[]{"bar", "bar-closed", "foofoobar", "foofoo", "foofoo-closed"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        //_all gets replaced with all indices that user is authorized for
        assertThat(request.indices(), arrayContainingInAnyOrder(expectedIndices));
        assertThat(request.aliases(), arrayContainingInAnyOrder("foofoobar"));
    }

    public void testResolveAllAndExplicitAliasesGetAliasesRequest() {
        GetAliasesRequest request = new GetAliasesRequest(new String[]{"_all", "explicit"});
        if (randomBoolean()) {
            request.indices("_all");
        }
        Set<String> indices = defaultIndicesResolver.resolve(user, GetAliasesAction.NAME, request, metaData);
        //the union of all resolved indices and aliases gets returned
        String[] expectedIndices = new String[]{"bar", "bar-closed", "foofoobar", "foofoo", "foofoo-closed", "explicit"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        //_all gets replaced with all indices that user is authorized for
        assertThat(request.indices(), arrayContainingInAnyOrder("bar", "bar-closed", "foofoobar", "foofoo", "foofoo-closed"));
        assertThat(request.aliases(), arrayContainingInAnyOrder("foofoobar", "explicit"));
    }

    public void testResolveAllAndWildcardsAliasesGetAliasesRequest() {
        GetAliasesRequest request = new GetAliasesRequest(new String[]{"_all", "foo*", "non_matching_*"});
        if (randomBoolean()) {
            request.indices("_all");
        }
        Set<String> indices = defaultIndicesResolver.resolve(user, GetAliasesAction.NAME, request, metaData);
        //the union of all resolved indices and aliases gets returned
        String[] expectedIndices = new String[]{"bar", "bar-closed", "foofoobar", "foofoo", "foofoo-closed"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        //_all gets replaced with all indices that user is authorized for
        assertThat(request.indices(), arrayContainingInAnyOrder(expectedIndices));
        assertThat(request.aliases(), arrayContainingInAnyOrder("foofoobar", "foofoobar"));
    }

    public void testResolveAliasesWildcardsGetAliasesRequest() {
        GetAliasesRequest request = new GetAliasesRequest();
        request.indices("*bar");
        request.aliases("foo*");
        Set<String> indices = defaultIndicesResolver.resolve(user, GetAliasesAction.NAME, request, metaData);
        //union of all resolved indices and aliases gets returned, based on what user is authorized for
        //note that the index side will end up containing matching aliases too, which is fine, as es core would do
        //the same and resolve those aliases to their corresponding concrete indices (which we let core do)
        String[] expectedIndices = new String[]{"bar", "foofoobar"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        //alias foofoobar on both sides, that's fine, es core would do the same, same as above
        assertThat(request.indices(), arrayContainingInAnyOrder("bar", "foofoobar"));
        assertThat(request.aliases(), arrayContainingInAnyOrder("foofoobar"));
    }

    public void testResolveAliasesWildcardsGetAliasesRequestNoAuthorizedIndices() {
        GetAliasesRequest request = new GetAliasesRequest();
        //no authorized aliases match bar*, hence the request fails
        request.aliases("bar*");
        request.indices("*bar");
        IndexNotFoundException e = expectThrows(IndexNotFoundException.class,
                () -> defaultIndicesResolver.resolve(user, GetAliasesAction.NAME, request, metaData));
        assertEquals("no such index", e.getMessage());
    }

    public void testResolveAliasesAllGetAliasesRequestNoAuthorizedIndices() {
        GetAliasesRequest request = new GetAliasesRequest();
        if (randomBoolean()) {
            request.aliases("_all");
        }
        request.indices("non_existing");
        //current user is not authorized for any index, foo* resolves to no indices, the request fails
        IndexNotFoundException e = expectThrows(IndexNotFoundException.class,
                () -> defaultIndicesResolver.resolve(userNoIndices, GetAliasesAction.NAME, request, metaData));
        assertEquals("no such index", e.getMessage());
    }

    //msearch is a CompositeIndicesRequest whose items (SearchRequests) implement IndicesRequest.Replaceable, wildcards will get replaced
    @AwaitsFix(bugUrl = "multi requests endpoints need fixing, we shouldn't merge all the indices in one collection")
    public void testResolveMultiSearchNoWildcards() {
        MultiSearchRequest request = new MultiSearchRequest();
        request.add(Requests.searchRequest("foo", "bar"));
        request.add(Requests.searchRequest("bar2"));
        Set<String> indices = defaultIndicesResolver.resolve(user, MultiSearchAction.NAME, request, metaData);
        String[] expectedIndices = new String[]{"foo", "bar", "bar2"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        assertThat(request.subRequests().get(0).indices(), equalTo(new String[]{"foo", "bar"}));
        assertThat(request.subRequests().get(1).indices(), equalTo(new String[]{"bar2"}));
    }

    @AwaitsFix(bugUrl = "multi requests endpoints need fixing, we shouldn't merge all the indices in one collection")
    public void testResolveMultiSearchNoWildcardsMissingIndex() {
        MultiSearchRequest request = new MultiSearchRequest();
        request.add(Requests.searchRequest("foo", "bar"));
        request.add(Requests.searchRequest("bar2"));
        request.add(Requests.searchRequest("missing"));
        Set<String> indices = defaultIndicesResolver.resolve(user, MultiSearchAction.NAME, request, metaData);
        String[] expectedIndices = new String[]{"foo", "bar", "bar2", "missing"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        assertThat(request.subRequests().get(0).indices(), equalTo(new String[]{"foo", "bar"}));
        assertThat(request.subRequests().get(1).indices(), equalTo(new String[]{"bar2"}));
        assertThat(request.subRequests().get(2).indices(), equalTo(new String[]{"missing"}));
    }

    @AwaitsFix(bugUrl = "multi requests endpoints need fixing, we shouldn't merge all the indices in one collection")
    public void testResolveMultiSearchWildcardsExpandOpen() {
        MultiSearchRequest request = new MultiSearchRequest();
        request.add(Requests.searchRequest("bar*")).indicesOptions(
                randomFrom(IndicesOptions.strictExpandOpen(), IndicesOptions.lenientExpandOpen()));
        request.add(Requests.searchRequest("foobar"));
        Set<String> indices = defaultIndicesResolver.resolve(user, MultiSearchAction.NAME, request, metaData);
        String[] expectedIndices = new String[]{"bar", "foobar"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        assertThat(request.subRequests().get(0).indices(), equalTo(new String[]{"bar"}));
        assertThat(request.subRequests().get(1).indices(), equalTo(new String[]{"foobar"}));
    }

    @AwaitsFix(bugUrl = "multi requests endpoints need fixing, we shouldn't merge all the indices in one collection")
    public void testResolveMultiSearchWildcardsExpandOpenAndClose() {
        MultiSearchRequest request = new MultiSearchRequest();
        request.add(Requests.searchRequest("bar*").indicesOptions(IndicesOptions.strictExpand()));
        request.add(Requests.searchRequest("foobar"));
        Set<String> indices = defaultIndicesResolver.resolve(user, MultiSearchAction.NAME, request, metaData);
        String[] expectedIndices = new String[]{"bar", "bar-closed", "foobar"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        assertThat(request.subRequests().get(0).indices(), equalTo(new String[]{"bar", "bar-closed"}));
        assertThat(request.subRequests().get(1).indices(), equalTo(new String[]{"foobar"}));
    }

    @AwaitsFix(bugUrl = "multi requests endpoints need fixing, we shouldn't merge all the indices in one collection")
    public void testResolveMultiSearchWildcardsMissingIndex() {
        MultiSearchRequest request = new MultiSearchRequest();
        request.add(Requests.searchRequest("bar*"));
        request.add(Requests.searchRequest("missing"));
        Set<String> indices = defaultIndicesResolver.resolve(user, MultiSearchAction.NAME, request, metaData);
        String[] expectedIndices = new String[]{"bar", "missing"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        assertThat(request.subRequests().get(0).indices(), equalTo(new String[]{"bar"}));
        assertThat(request.subRequests().get(1).indices(), equalTo(new String[]{"missing"}));
    }

    @AwaitsFix(bugUrl = "multi requests endpoints need fixing, we shouldn't merge all the indices in one collection")
    public void testResolveMultiSearchWildcardsNoMatchingIndices() {
        MultiSearchRequest request = new MultiSearchRequest();
        request.add(Requests.searchRequest("missing*"));
        request.add(Requests.searchRequest("foobar"));
        try {
            defaultIndicesResolver.resolve(user, MultiSearchAction.NAME, request, metaData);
            fail("Expected IndexNotFoundException");
        } catch (IndexNotFoundException e) {
            assertThat(e.getMessage(), is("no such index"));
        }
    }

    @AwaitsFix(bugUrl = "multi requests endpoints need fixing, we shouldn't merge all the indices in one collection")
    public void testMultiSearchWildcardsNoAuthorizedIndices() {
        MultiSearchRequest request = new MultiSearchRequest();
        request.add(Requests.searchRequest("foofoo*"));
        request.add(Requests.searchRequest("foobar"));
        try {
            defaultIndicesResolver.resolve(userNoIndices, MultiSearchAction.NAME, request, metaData);
            fail("Expected IndexNotFoundException");
        } catch (IndexNotFoundException e) {
            assertThat(e.getMessage(), is("no such index"));
        }
    }

    @AwaitsFix(bugUrl = "multi requests endpoints need fixing, we shouldn't merge all the indices in one collection")
    public void testResolveMultiSearchWildcardsNoAuthorizedIndices() {
        MultiSearchRequest request = new MultiSearchRequest();
        request.add(Requests.searchRequest("foofoo*"));
        request.add(Requests.searchRequest("foobar"));
        try {
            defaultIndicesResolver.resolve(userNoIndices, MultiSearchAction.NAME, request, metaData);
            fail("Expected IndexNotFoundException");
        } catch (IndexNotFoundException e) {
            assertThat(e.getMessage(), is("no such index"));
        }
    }

    //mget is a CompositeIndicesRequest whose items don't support expanding wildcards
    public void testResolveMultiGet() {
        MultiGetRequest request = new MultiGetRequest();
        request.add("foo", "type", "id");
        request.add("bar", "type", "id");
        Set<String> indices = defaultIndicesResolver.resolve(user, MultiGetAction.NAME, request, metaData);
        String[] expectedIndices = new String[]{"foo", "bar"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        assertThat(request.subRequests().get(0).indices(), equalTo(new String[]{"foo"}));
        assertThat(request.subRequests().get(1).indices(), equalTo(new String[]{"bar"}));
    }

    public void testResolveMultiGetMissingIndex() {
        MultiGetRequest request = new MultiGetRequest();
        request.add("foo", "type", "id");
        request.add("missing", "type", "id");
        Set<String> indices = defaultIndicesResolver.resolve(user, MultiGetAction.NAME, request, metaData);
        String[] expectedIndices = new String[]{"foo", "missing"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        assertThat(request.subRequests().get(0).indices(), equalTo(new String[]{"foo"}));
        assertThat(request.subRequests().get(1).indices(), equalTo(new String[]{"missing"}));
    }

    public void testResolveAdminAction() {
        DeleteIndexRequest request = new DeleteIndexRequest("*");
        Set<String> indices = defaultIndicesResolver.resolve(user, DeleteIndexAction.NAME, request, metaData);
        String[] expectedIndices = new String[]{"bar", "bar-closed", "foofoobar", "foofoo", "foofoo-closed"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        assertThat(request.indices(), arrayContainingInAnyOrder(expectedIndices));
    }

    public void testXPackUserHasAccessToSecurityIndex() {
        SearchRequest request = new SearchRequest();
        Set<String> indices = defaultIndicesResolver.resolve(XPackUser.INSTANCE, SearchAction.NAME, request, metaData);
        assertThat(indices, hasItem(SecurityTemplateService.SECURITY_INDEX_NAME));

        IndicesAliasesRequest aliasesRequest = new IndicesAliasesRequest();
        aliasesRequest.addAliasAction(AliasActions.add().alias("security_alias").index("*"));
        indices = defaultIndicesResolver.resolve(XPackUser.INSTANCE, IndicesAliasesAction.NAME, aliasesRequest, metaData);
        assertThat(indices, hasItem(SecurityTemplateService.SECURITY_INDEX_NAME));
    }

    public void testNonXPackUserAccessingSecurityIndex() {
        User allAccessUser = new User("all_access", new String[] { "all_access" } );
        when(rolesStore.role("all_access")).thenReturn(
                Role.builder("all_access").add(IndexPrivilege.ALL, "*").cluster(ClusterPrivilege.ALL).build());

        SearchRequest request = new SearchRequest();
        Set<String> indices = defaultIndicesResolver.resolve(allAccessUser, SearchAction.NAME, request, metaData);
        assertThat(indices, not(hasItem(SecurityTemplateService.SECURITY_INDEX_NAME)));

        IndicesAliasesRequest aliasesRequest = new IndicesAliasesRequest();
        aliasesRequest.addAliasAction(AliasActions.add().alias("security_alias1").index("*"));
        indices = defaultIndicesResolver.resolve(allAccessUser, IndicesAliasesAction.NAME, aliasesRequest, metaData);
        assertThat(indices, not(hasItem(SecurityTemplateService.SECURITY_INDEX_NAME)));
    }

    public void testUnauthorizedDateMathExpressionIgnoreUnavailable() {
        SearchRequest request = new SearchRequest("<datetime-{now/M}>");
        request.indicesOptions(IndicesOptions.fromOptions(true, true, randomBoolean(), randomBoolean()));
        assertNoIndices(request, defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData));
    }

    public void testUnauthorizedDateMathExpressionIgnoreUnavailableDisallowNoIndices() {
        SearchRequest request = new SearchRequest("<datetime-{now/M}>");
        request.indicesOptions(IndicesOptions.fromOptions(true, false, randomBoolean(), randomBoolean()));
        IndexNotFoundException e = expectThrows(IndexNotFoundException.class,
                () -> defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData));
        assertEquals("no such index" , e.getMessage());
    }

    public void testUnauthorizedDateMathExpressionStrict() {
        SearchRequest request = new SearchRequest("<datetime-{now/M}>");
        request.indicesOptions(IndicesOptions.fromOptions(false, randomBoolean(), randomBoolean(), randomBoolean()));
        IndexNotFoundException e = expectThrows(IndexNotFoundException.class,
                () -> defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData));
        assertEquals("no such index" , e.getMessage());
    }

    public void testResolveDateMathExpression() {
        // make the user authorized
        String dateTimeIndex = indexNameExpressionResolver.resolveDateMathExpression("<datetime-{now/M}>");
        String[] authorizedIndices = new String[] { "bar", "bar-closed", "foofoobar", "foofoo", "missing", "foofoo-closed", dateTimeIndex};
        when(rolesStore.role("role")).thenReturn(Role.builder("role").add(IndexPrivilege.ALL, authorizedIndices).build());

        SearchRequest request = new SearchRequest("<datetime-{now/M}>");
        if (randomBoolean()) {
            request.indicesOptions(IndicesOptions.fromOptions(randomBoolean(), randomBoolean(), randomBoolean(), randomBoolean()));
        }
        Set<String> indices = defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData);
        assertThat(indices.size(), equalTo(1));
        assertThat(request.indices()[0], equalTo(indexNameExpressionResolver.resolveDateMathExpression("<datetime-{now/M}>")));
    }

    public void testMissingDateMathExpressionIgnoreUnavailable() {
        SearchRequest request = new SearchRequest("<foobar-{now/M}>");
        request.indicesOptions(IndicesOptions.fromOptions(true, true, randomBoolean(), randomBoolean()));
        assertNoIndices(request, defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData));
    }

    public void testMissingDateMathExpressionIgnoreUnavailableDisallowNoIndices() {
        SearchRequest request = new SearchRequest("<foobar-{now/M}>");
        request.indicesOptions(IndicesOptions.fromOptions(true, false, randomBoolean(), randomBoolean()));
        IndexNotFoundException e = expectThrows(IndexNotFoundException.class,
                () -> defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData));
        assertEquals("no such index" , e.getMessage());
    }

    public void testMissingDateMathExpressionStrict() {
        SearchRequest request = new SearchRequest("<foobar-{now/M}>");
        request.indicesOptions(IndicesOptions.fromOptions(false, randomBoolean(), randomBoolean(), randomBoolean()));
        IndexNotFoundException e = expectThrows(IndexNotFoundException.class,
                () -> defaultIndicesResolver.resolve(user, SearchAction.NAME, request, metaData));
        assertEquals("no such index" , e.getMessage());
    }

    public void testAliasDateMathExpressionNotSupported() {
        // make the user authorized
        String[] authorizedIndices = new String[] { "bar", "bar-closed", "foofoobar", "foofoo", "missing", "foofoo-closed",
                indexNameExpressionResolver.resolveDateMathExpression("<datetime-{now/M}>")};
        when(rolesStore.role("role")).thenReturn(Role.builder("role").add(IndexPrivilege.ALL, authorizedIndices).build());
        GetAliasesRequest request = new GetAliasesRequest("<datetime-{now/M}>").indices("foo", "foofoo");
        Set<String> indices = defaultIndicesResolver.resolve(user, GetAliasesAction.NAME, request, metaData);
        //the union of all indices and aliases gets returned
        String[] expectedIndices = new String[]{"<datetime-{now/M}>", "foo", "foofoo"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        assertThat(request.indices(), arrayContainingInAnyOrder("foo", "foofoo"));
        assertThat(request.aliases(), arrayContainingInAnyOrder("<datetime-{now/M}>"));
    }

    public void testCompositeRequestsCanResolveExpressions() {
        // make the user authorized
        String[] authorizedIndices = new String[] { "bar", "bar-closed", "foofoobar", "foofoo", "missing", "foofoo-closed",
                indexNameExpressionResolver.resolveDateMathExpression("<datetime-{now/M}>")};
        when(rolesStore.role("role")).thenReturn(Role.builder("role").add(IndexPrivilege.ALL, authorizedIndices).build());
        MultiSearchRequest multiSearchRequest = new MultiSearchRequest();
        multiSearchRequest.add(new SearchRequest("<datetime-{now/M}>"));
        multiSearchRequest.add(new SearchRequest("bar"));
        Set<String> indices = defaultIndicesResolver.resolve(user, MultiSearchAction.NAME, multiSearchRequest, metaData);

        String resolvedName = indexNameExpressionResolver.resolveDateMathExpression("<datetime-{now/M}>");
        String[] expectedIndices = new String[]{resolvedName, "bar"};
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        assertThat(multiSearchRequest.requests().get(0).indices(), arrayContaining(resolvedName));
        assertThat(multiSearchRequest.requests().get(1).indices(), arrayContaining("bar"));

        // multi get doesn't support replacing indices but we should authorize the proper indices
        MultiGetRequest multiGetRequest = new MultiGetRequest();
        multiGetRequest.add("<datetime-{now/M}>", "type", "1");
        multiGetRequest.add("bar", "type", "1");
        indices = defaultIndicesResolver.resolve(user, MultiGetAction.NAME, multiGetRequest, metaData);
        assertThat(indices.size(), equalTo(expectedIndices.length));
        assertThat(indices, hasItems(expectedIndices));
        assertThat(multiGetRequest.getItems().get(0).indices(), arrayContaining("<datetime-{now/M}>"));
        assertThat(multiGetRequest.getItems().get(1).indices(), arrayContaining("bar"));
    }

    // TODO with the removal of DeleteByQuery is there another way to test resolving a write action?

    private static IndexMetaData.Builder indexBuilder(String index) {
        return IndexMetaData.builder(index).settings(Settings.builder()
                .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 0));
    }

    private static void assertNoIndices(IndicesRequest.Replaceable request, Set<String> resolvedIndices) {
        assertEquals(1, resolvedIndices.size());
        assertEquals(DefaultIndicesAndAliasesResolver.NO_INDEX, resolvedIndices.iterator().next());
        assertEquals(1, request.indices().length);
        assertEquals(DefaultIndicesAndAliasesResolver.NO_INDEX, request.indices()[0]);
    }
}
