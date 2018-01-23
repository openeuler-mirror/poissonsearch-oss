/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.audit.logfile;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.mock.orig.Mockito;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.rest.FakeRestRequest;
import org.elasticsearch.test.rest.FakeRestRequest.Builder;
import org.elasticsearch.transport.TransportMessage;
import org.elasticsearch.xpack.core.security.audit.logfile.CapturingLogger;
import org.elasticsearch.xpack.core.security.authc.AuthenticationToken;
import org.elasticsearch.xpack.core.security.user.SystemUser;
import org.elasticsearch.xpack.core.security.user.User;
import org.elasticsearch.xpack.security.audit.logfile.LoggingAuditTrail.AuditEventMetaInfo;
import org.elasticsearch.xpack.security.audit.logfile.LoggingAuditTrailTests.MockMessage;
import org.elasticsearch.xpack.security.audit.logfile.LoggingAuditTrailTests.RestContent;
import org.elasticsearch.xpack.security.rest.RemoteHostHeader;
import org.elasticsearch.xpack.security.transport.filter.SecurityIpFilterRule;
import org.junit.Before;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class LoggingAuditTrailFilterTests extends ESTestCase {

    private static final String FILTER_MARKER = "filterMarker_";
    private static final String UNFILTER_MARKER = "nofilter_";

    private Settings settings;
    private DiscoveryNode localNode;
    private ClusterService clusterService;
    private ThreadContext threadContext;
    private Logger logger;
    List<String> logOutput;

    @Before
    public void init() throws Exception {
        settings = Settings.builder()
                .put("xpack.security.audit.logfile.prefix.emit_node_host_address", randomBoolean())
                .put("xpack.security.audit.logfile.prefix.emit_node_host_name", randomBoolean())
                .put("xpack.security.audit.logfile.prefix.emit_node_name", randomBoolean())
                .put("xpack.security.audit.logfile.events.emit_request_body", randomBoolean())
                .put("xpack.security.audit.logfile.events.include", "_all")
                .build();
        localNode = mock(DiscoveryNode.class);
        when(localNode.getHostAddress()).thenReturn(buildNewFakeTransportAddress().toString());
        clusterService = mock(ClusterService.class);
        when(clusterService.localNode()).thenReturn(localNode);
        Mockito.doAnswer((Answer) invocation -> {
            final LoggingAuditTrail arg0 = (LoggingAuditTrail) invocation.getArguments()[0];
            arg0.updateLocalNodeInfo(localNode);
            return null;
        }).when(clusterService).addListener(Mockito.isA(LoggingAuditTrail.class));
        threadContext = new ThreadContext(Settings.EMPTY);
        logger = CapturingLogger.newCapturingLogger(Level.INFO);
        logOutput = CapturingLogger.output(logger.getName(), Level.INFO);
    }

    public void testSingleCompletePolicyPredicate() throws Exception {
        // create complete filter policy
        final Settings.Builder settingsBuilder = Settings.builder().put(settings);
        // filter by username
        final List<String> filteredUsernames = randomNonEmptyListOfFilteredNames();
        final List<User> filteredUsers = filteredUsernames.stream().map(u -> {
            if (randomBoolean()) {
                return new User(u);
            } else {
                return new User(new User(u), new User(UNFILTER_MARKER + randomAlphaOfLengthBetween(1, 4)));
            }
        }).collect(Collectors.toList());
        settingsBuilder.putList("xpack.security.audit.logfile.events.ignore_filters.completeFilterPolicy.users", filteredUsernames);
        // filter by realms
        final List<String> filteredRealms = randomNonEmptyListOfFilteredNames();
        settingsBuilder.putList("xpack.security.audit.logfile.events.ignore_filters.completeFilterPolicy.realms", filteredRealms);
        // filter by roles
        final List<String> filteredRoles = randomNonEmptyListOfFilteredNames();
        settingsBuilder.putList("xpack.security.audit.logfile.events.ignore_filters.completeFilterPolicy.roles", filteredRoles);
        // filter by indices
        final List<String> filteredIndices = randomNonEmptyListOfFilteredNames();
        settingsBuilder.putList("xpack.security.audit.logfile.events.ignore_filters.completeFilterPolicy.indices", filteredIndices);

        final LoggingAuditTrail auditTrail = new LoggingAuditTrail(settingsBuilder.build(), clusterService, logger, threadContext);

        // all fields match
        assertTrue("Matches the filter predicate.", auditTrail.filterPolicyPredicate.test(new AuditEventMetaInfo(
                Optional.of(randomFrom(filteredUsers)), Optional.of(randomFrom(filteredRealms)),
                Optional.of(randomSubsetOf(randomIntBetween(1, filteredRoles.size()), filteredRoles).toArray(new String[0])),
                Optional.of(randomSubsetOf(randomIntBetween(1, filteredIndices.size()), filteredIndices).toArray(new String[0])))));
        final User unfilteredUser;
        if (randomBoolean()) {
            unfilteredUser = new User(UNFILTER_MARKER + randomAlphaOfLengthBetween(1, 8));
        } else {
            unfilteredUser = new User(new User(UNFILTER_MARKER + randomAlphaOfLengthBetween(1, 8)),
                    new User(randomFrom(filteredUsers).principal()));
        }
        // one field does not match or is empty
        assertFalse("Does not match the filter predicate because of the user.",
                auditTrail.filterPolicyPredicate.test(new AuditEventMetaInfo(Optional.of(unfilteredUser),
                        Optional.of(randomFrom(filteredRealms)),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredRoles.size()), filteredRoles).toArray(new String[0])),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredIndices.size()), filteredIndices).toArray(new String[0])))));
        assertFalse("Does not match the filter predicate because of the empty user.",
                auditTrail.filterPolicyPredicate.test(new AuditEventMetaInfo(Optional.empty(), Optional.of(randomFrom(filteredRealms)),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredRoles.size()), filteredRoles).toArray(new String[0])),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredIndices.size()), filteredIndices).toArray(new String[0])))));
        assertFalse("Does not match the filter predicate because of the realm.",
                auditTrail.filterPolicyPredicate.test(new AuditEventMetaInfo(Optional.of(randomFrom(filteredUsers)),
                        Optional.of(UNFILTER_MARKER + randomAlphaOfLengthBetween(1, 8)),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredRoles.size()), filteredRoles).toArray(new String[0])),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredIndices.size()), filteredIndices).toArray(new String[0])))));
        assertFalse("Does not match the filter predicate because of the empty realm.",
                auditTrail.filterPolicyPredicate.test(new AuditEventMetaInfo(Optional.of(randomFrom(filteredUsers)), Optional.empty(),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredRoles.size()), filteredRoles).toArray(new String[0])),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredIndices.size()), filteredIndices).toArray(new String[0])))));
        final List<String> someRolesDoNotMatch = new ArrayList<>(randomSubsetOf(randomIntBetween(0, filteredRoles.size()), filteredRoles));
        for (int i = 0; i < randomIntBetween(1, 8); i++) {
            someRolesDoNotMatch.add(UNFILTER_MARKER + randomAlphaOfLengthBetween(1, 8));
        }
        assertFalse("Does not match the filter predicate because of some of the roles.",
                auditTrail.filterPolicyPredicate.test(new AuditEventMetaInfo(Optional.of(randomFrom(filteredUsers)),
                        Optional.of(randomFrom(filteredRealms)), Optional.of(someRolesDoNotMatch.toArray(new String[0])),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredIndices.size()), filteredIndices).toArray(new String[0])))));
        final Optional<String[]> emptyRoles = randomBoolean() ? Optional.empty() : Optional.of(new String[0]);
        assertFalse("Does not match the filter predicate because of the empty roles.",
                auditTrail.filterPolicyPredicate.test(new AuditEventMetaInfo(Optional.of(randomFrom(filteredUsers)),
                        Optional.of(randomFrom(filteredRealms)), emptyRoles,
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredIndices.size()), filteredIndices).toArray(new String[0])))));
        final List<String> someIndicesDoNotMatch = new ArrayList<>(
                randomSubsetOf(randomIntBetween(0, filteredIndices.size()), filteredIndices));
        for (int i = 0; i < randomIntBetween(1, 8); i++) {
            someIndicesDoNotMatch.add(UNFILTER_MARKER + randomAlphaOfLengthBetween(1, 8));
        }
        assertFalse("Does not match the filter predicate because of some of the indices.", auditTrail.filterPolicyPredicate
                .test(new AuditEventMetaInfo(Optional.of(randomFrom(filteredUsers)), Optional.of(randomFrom(filteredRealms)),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredRoles.size()), filteredRoles).toArray(new String[0])),
                        Optional.of(someIndicesDoNotMatch.toArray(new String[0])))));
        final Optional<String[]> emptyIndices = randomBoolean() ? Optional.empty() : Optional.of(new String[0]);
        assertFalse("Does not match the filter predicate because of the empty indices.", auditTrail.filterPolicyPredicate
                .test(new AuditEventMetaInfo(Optional.of(randomFrom(filteredUsers)), Optional.of(randomFrom(filteredRealms)),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredRoles.size()), filteredRoles).toArray(new String[0])),
                        emptyIndices)));
    }

    public void testSingleCompleteWithEmptyFieldPolicyPredicate() throws Exception {
        // create complete filter policy
        final Settings.Builder settingsBuilder = Settings.builder().put(settings);
        // filter by username
        final List<String> filteredUsernames = randomNonEmptyListOfFilteredNames();
        final List<User> filteredUsers = filteredUsernames.stream().map(u -> {
            if (randomBoolean()) {
                return new User(u);
            } else {
                return new User(new User(u), new User(UNFILTER_MARKER + randomAlphaOfLengthBetween(1, 4)));
            }
        }).collect(Collectors.toList());
        filteredUsernames.add(""); // filter by missing user name
        settingsBuilder.putList("xpack.security.audit.logfile.events.ignore_filters.completeFilterPolicy.users", filteredUsernames);
        // filter by realms
        final List<String> filteredRealms = randomNonEmptyListOfFilteredNames();
        filteredRealms.add(""); // filter by missing realm name
        settingsBuilder.putList("xpack.security.audit.logfile.events.ignore_filters.completeFilterPolicy.realms", filteredRealms);
        filteredRealms.remove("");
        // filter by roles
        final List<String> filteredRoles = randomNonEmptyListOfFilteredNames();
        filteredRoles.add(""); // filter by missing role name
        settingsBuilder.putList("xpack.security.audit.logfile.events.ignore_filters.completeFilterPolicy.roles", filteredRoles);
        filteredRoles.remove("");
        // filter by indices
        final List<String> filteredIndices = randomNonEmptyListOfFilteredNames();
        filteredIndices.add(""); // filter by missing index name
        settingsBuilder.putList("xpack.security.audit.logfile.events.ignore_filters.completeFilterPolicy.indices", filteredIndices);
        filteredIndices.remove("");

        final LoggingAuditTrail auditTrail = new LoggingAuditTrail(settingsBuilder.build(), clusterService, logger, threadContext);

        // all fields match
        assertTrue("Matches the filter predicate.",
                auditTrail.filterPolicyPredicate.test(new AuditEventMetaInfo(Optional.of(randomFrom(filteredUsers)),
                        Optional.of(randomFrom(filteredRealms)),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredRoles.size()), filteredRoles).toArray(new String[0])),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredIndices.size()), filteredIndices).toArray(new String[0])))));
        final User unfilteredUser;
        if (randomBoolean()) {
            unfilteredUser = new User(UNFILTER_MARKER + randomAlphaOfLengthBetween(1, 8));
        } else {
            unfilteredUser = new User(new User(UNFILTER_MARKER + randomAlphaOfLengthBetween(1, 8)),
                    new User(randomFrom(filteredUsers).principal()));
        }
        // one field does not match or is empty
        assertFalse("Does not match the filter predicate because of the user.",
                auditTrail.filterPolicyPredicate.test(new AuditEventMetaInfo(Optional.of(unfilteredUser),
                        Optional.of(randomFrom(filteredRealms)),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredRoles.size()), filteredRoles).toArray(new String[0])),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredIndices.size()), filteredIndices).toArray(new String[0])))));
        assertTrue("Matches the filter predicate because of the empty user.",
                auditTrail.filterPolicyPredicate.test(new AuditEventMetaInfo(Optional.empty(), Optional.of(randomFrom(filteredRealms)),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredRoles.size()), filteredRoles).toArray(new String[0])),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredIndices.size()), filteredIndices).toArray(new String[0])))));
        assertFalse("Does not match the filter predicate because of the realm.",
                auditTrail.filterPolicyPredicate.test(new AuditEventMetaInfo(Optional.of(randomFrom(filteredUsers)),
                        Optional.of(UNFILTER_MARKER + randomAlphaOfLengthBetween(1, 8)),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredRoles.size()), filteredRoles).toArray(new String[0])),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredIndices.size()), filteredIndices).toArray(new String[0])))));
        assertTrue("Matches the filter predicate because of the empty realm.",
                auditTrail.filterPolicyPredicate.test(new AuditEventMetaInfo(Optional.of(randomFrom(filteredUsers)), Optional.empty(),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredRoles.size()), filteredRoles).toArray(new String[0])),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredIndices.size()), filteredIndices).toArray(new String[0])))));
        final List<String> someRolesDoNotMatch = new ArrayList<>(randomSubsetOf(randomIntBetween(0, filteredRoles.size()), filteredRoles));
        for (int i = 0; i < randomIntBetween(1, 8); i++) {
            someRolesDoNotMatch.add(UNFILTER_MARKER + randomAlphaOfLengthBetween(1, 8));
        }
        assertFalse("Does not match the filter predicate because of some of the roles.",
                auditTrail.filterPolicyPredicate.test(new AuditEventMetaInfo(Optional.of(randomFrom(filteredUsers)),
                        Optional.of(randomFrom(filteredRealms)), Optional.of(someRolesDoNotMatch.toArray(new String[0])),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredIndices.size()), filteredIndices).toArray(new String[0])))));
        final Optional<String[]> emptyRoles = randomBoolean() ? Optional.empty() : Optional.of(new String[0]);
        assertTrue("Matches the filter predicate because of the empty roles.",
                auditTrail.filterPolicyPredicate.test(new AuditEventMetaInfo(Optional.of(randomFrom(filteredUsers)),
                        Optional.of(randomFrom(filteredRealms)), emptyRoles,
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredIndices.size()), filteredIndices).toArray(new String[0])))));
        final List<String> someIndicesDoNotMatch = new ArrayList<>(
                randomSubsetOf(randomIntBetween(0, filteredIndices.size()), filteredIndices));
        for (int i = 0; i < randomIntBetween(1, 8); i++) {
            someIndicesDoNotMatch.add(UNFILTER_MARKER + randomAlphaOfLengthBetween(1, 8));
        }
        assertFalse("Does not match the filter predicate because of some of the indices.", auditTrail.filterPolicyPredicate
                .test(new AuditEventMetaInfo(Optional.of(randomFrom(filteredUsers)), Optional.of(randomFrom(filteredRealms)),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredRoles.size()), filteredRoles).toArray(new String[0])),
                        Optional.of(someIndicesDoNotMatch.toArray(new String[0])))));
        final Optional<String[]> emptyIndices = randomBoolean() ? Optional.empty() : Optional.of(new String[0]);
        assertTrue("Matches the filter predicate because of the empty indices.", auditTrail.filterPolicyPredicate
                .test(new AuditEventMetaInfo(Optional.of(randomFrom(filteredUsers)), Optional.of(randomFrom(filteredRealms)),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredRoles.size()), filteredRoles).toArray(new String[0])),
                        emptyIndices)));
    }

    public void testTwoPolicyPredicatesWithMissingFields() throws Exception {
        final Settings.Builder settingsBuilder = Settings.builder().put(settings);
        // first policy: realms and roles filters
        final List<String> filteredRealms = randomNonEmptyListOfFilteredNames();
        settingsBuilder.putList("xpack.security.audit.logfile.events.ignore_filters.firstPolicy.realms", filteredRealms);
        final List<String> filteredRoles = randomNonEmptyListOfFilteredNames();
        settingsBuilder.putList("xpack.security.audit.logfile.events.ignore_filters.firstPolicy.roles", filteredRoles);
        // second policy: users and indices filters
        final List<String> filteredUsernames = randomNonEmptyListOfFilteredNames();
        final List<User> filteredUsers = filteredUsernames.stream().map(u -> {
            if (randomBoolean()) {
                return new User(u);
            } else {
                return new User(new User(u), new User(UNFILTER_MARKER + randomAlphaOfLengthBetween(1, 4)));
            }
        }).collect(Collectors.toList());
        settingsBuilder.putList("xpack.security.audit.logfile.events.ignore_filters.secondPolicy.users", filteredUsernames);
        // filter by indices
        final List<String> filteredIndices = randomNonEmptyListOfFilteredNames();
        settingsBuilder.putList("xpack.security.audit.logfile.events.ignore_filters.secondPolicy.indices", filteredIndices);

        final LoggingAuditTrail auditTrail = new LoggingAuditTrail(settingsBuilder.build(), clusterService, logger, threadContext);

        final User unfilteredUser;
        if (randomBoolean()) {
            unfilteredUser = new User(UNFILTER_MARKER + randomAlphaOfLengthBetween(1, 8));
        } else {
            unfilteredUser = new User(new User(UNFILTER_MARKER + randomAlphaOfLengthBetween(1, 8)),
                    new User(randomFrom(filteredUsers).principal()));
        }
        final List<String> someRolesDoNotMatch = new ArrayList<>(randomSubsetOf(randomIntBetween(0, filteredRoles.size()), filteredRoles));
        for (int i = 0; i < randomIntBetween(1, 8); i++) {
            someRolesDoNotMatch.add(UNFILTER_MARKER + randomAlphaOfLengthBetween(1, 8));
        }
        final List<String> someIndicesDoNotMatch = new ArrayList<>(
                randomSubsetOf(randomIntBetween(0, filteredIndices.size()), filteredIndices));
        for (int i = 0; i < randomIntBetween(1, 8); i++) {
            someIndicesDoNotMatch.add(UNFILTER_MARKER + randomAlphaOfLengthBetween(1, 8));
        }
        // matches both the first and the second policies
        assertTrue("Matches both the first and the second filter predicates.",
                auditTrail.filterPolicyPredicate.test(new AuditEventMetaInfo(Optional.of(randomFrom(filteredUsers)),
                        Optional.of(randomFrom(filteredRealms)),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredRoles.size()), filteredRoles).toArray(new String[0])),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredIndices.size()), filteredIndices).toArray(new String[0])))));
        // matches first policy but not the second
        assertTrue("Matches the first filter predicate but not the second.",
                auditTrail.filterPolicyPredicate.test(new AuditEventMetaInfo(Optional.of(unfilteredUser),
                        Optional.of(randomFrom(filteredRealms)),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredRoles.size()), filteredRoles).toArray(new String[0])),
                        Optional.of(someIndicesDoNotMatch.toArray(new String[0])))));
        // matches the second policy but not the first
        assertTrue("Matches the second filter predicate but not the first.",
                auditTrail.filterPolicyPredicate.test(new AuditEventMetaInfo(Optional.of(randomFrom(filteredUsers)),
                        Optional.of(UNFILTER_MARKER + randomAlphaOfLengthBetween(1, 8)),
                        Optional.of(someRolesDoNotMatch.toArray(new String[0])),
                        Optional.of(randomSubsetOf(randomIntBetween(1, filteredIndices.size()), filteredIndices).toArray(new String[0])))));
        // matches neither the first nor the second policies
        assertFalse("Matches neither the first nor the second filter predicates.",
                auditTrail.filterPolicyPredicate.test(new AuditEventMetaInfo(Optional.of(unfilteredUser),
                        Optional.of(UNFILTER_MARKER + randomAlphaOfLengthBetween(1, 8)),
                        Optional.of(someRolesDoNotMatch.toArray(new String[0])),
                        Optional.of(someIndicesDoNotMatch.toArray(new String[0])))));
    }

    public void testUsersFilter() throws Exception {
        final List<String> allFilteredUsers = new ArrayList<>();
        final Settings.Builder settingsBuilder = Settings.builder().put(settings);
        for (int i = 0; i < randomIntBetween(1, 4); i++) {
            final List<String> filteredUsers = randomNonEmptyListOfFilteredNames();
            allFilteredUsers.addAll(filteredUsers);
            settingsBuilder.putList("xpack.security.audit.logfile.events.ignore_filters.policy" + i + ".users", filteredUsers);
        }
        // a filter for a field consisting of an empty string ("") or an empty list([])
        // will match events that lack that field
        final boolean filterMissingUser = randomBoolean();
        if (filterMissingUser) {
            if (randomBoolean()) {
                final List<String> filteredUsers = randomNonEmptyListOfFilteredNames();
                // possibly renders list empty
                filteredUsers.remove(0);
                allFilteredUsers.addAll(filteredUsers);
                filteredUsers.add("");
                settingsBuilder.putList("xpack.security.audit.logfile.events.ignore_filters.missingPolicy.users", filteredUsers);
            } else {
                settingsBuilder.putList("xpack.security.audit.logfile.events.ignore_filters.missingPolicy.users",
                        Collections.emptyList());
            }
        }
        User filteredUser;
        if (randomBoolean()) {
            filteredUser = new User(randomFrom(allFilteredUsers), new String[] { "r1" }, new User("authUsername", new String[] { "r2" }));
        } else {
            filteredUser = new User(randomFrom(allFilteredUsers), new String[] { "r1" });
        }
        User unfilteredUser;
        if (randomBoolean()) {
            unfilteredUser = new User(UNFILTER_MARKER + randomAlphaOfLengthBetween(1, 4), new String[] { "r1" },
                    new User("authUsername", new String[] { "r2" }));
        } else {
            unfilteredUser = new User(UNFILTER_MARKER + randomAlphaOfLengthBetween(1, 4), new String[] { "r1" });
        }
        final TransportMessage message = randomBoolean() ? new MockMessage(threadContext)
                : new MockIndicesRequest(threadContext, new String[] { "idx1", "idx2" });
        final MockToken filteredToken = new MockToken(randomFrom(allFilteredUsers));
        final MockToken unfilteredToken = new MockToken(UNFILTER_MARKER + randomAlphaOfLengthBetween(1, 4));

        final LoggingAuditTrail auditTrail = new LoggingAuditTrail(settingsBuilder.build(), clusterService, logger, threadContext);
        // anonymous accessDenied
        auditTrail.anonymousAccessDenied("_action", message);
        if (filterMissingUser) {
            assertThat("Anonymous message: not filtered out by the missing user filter", logOutput.size(), is(0));
        } else {
            assertThat("Anonymous message: filtered out by the user filters", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.anonymousAccessDenied(getRestRequest());
        if (filterMissingUser) {
            assertThat("Anonymous rest request: not filtered out by the missing user filter", logOutput.size(), is(0));
        } else {
            assertThat("Anonymous rest request: filtered out by user filters", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        // authenticationFailed
        auditTrail.authenticationFailed(getRestRequest());
        if (filterMissingUser) {
            assertThat("AuthenticationFailed no token rest request: not filtered out by the missing user filter", logOutput.size(), is(0));
        } else {
            assertThat("AuthenticationFailed no token rest request: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed(unfilteredToken, "_action", message);
        assertThat("AuthenticationFailed token request: unfiltered user is filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed(filteredToken, "_action", message);
        assertThat("AuthenticationFailed token request: filtered user is not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed("_action", message);
        if (filterMissingUser) {
            assertThat("AuthenticationFailed no token message: not filtered out by the missing user filter", logOutput.size(), is(0));
        } else {
            assertThat("AuthenticationFailed no token message: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed(unfilteredToken, getRestRequest());
        assertThat("AuthenticationFailed rest request: unfiltered user is filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed(filteredToken, getRestRequest());
        assertThat("AuthenticationFailed rest request: filtered user is not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed("_realm", unfilteredToken, "_action", message);
        assertThat("AuthenticationFailed realm message: unfiltered user is filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed("_realm", filteredToken, "_action", message);
        assertThat("AuthenticationFailed realm message: filtered user is not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed("_realm", unfilteredToken, getRestRequest());
        assertThat("AuthenticationFailed realm rest request: unfiltered user is filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed("_realm", filteredToken, getRestRequest());
        assertThat("AuthenticationFailed realm rest request: filtered user is not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        // accessGranted
        auditTrail.accessGranted(unfilteredUser, "_action", message, new String[] { "role1" });
        assertThat("AccessGranted message: unfiltered user is filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessGranted(filteredUser, "_action", message, new String[] { "role1" });
        assertThat("AccessGranted message: filtered user is not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessGranted(SystemUser.INSTANCE, "internal:_action", message, new String[] { "role1" });
        assertThat("AccessGranted internal message: system user is filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessGranted(unfilteredUser, "internal:_action", message, new String[] { "role1" });
        assertThat("AccessGranted internal message: unfiltered user is filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessGranted(filteredUser, "internal:_action", message, new String[] { "role1" });
        assertThat("AccessGranted internal message: filtered user is not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        // accessDenied
        auditTrail.accessDenied(unfilteredUser, "_action", message, new String[] { "role1" });
        assertThat("AccessDenied message: unfiltered user is filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessDenied(filteredUser, "_action", message, new String[] { "role1" });
        assertThat("AccessDenied message: filtered user is not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessDenied(SystemUser.INSTANCE, "internal:_action", message, new String[] { "role1" });
        assertThat("AccessDenied internal message: system user is filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessDenied(unfilteredUser, "internal:_action", message, new String[] { "role1" });
        assertThat("AccessDenied internal message: unfiltered user is filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessDenied(filteredUser, "internal:_action", message, new String[] { "role1" });
        assertThat("AccessDenied internal message: filtered user is not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        // tamperedRequest
        auditTrail.tamperedRequest(getRestRequest());
        if (filterMissingUser) {
            assertThat("Tampered rest: is not filtered out by the missing user filter", logOutput.size(), is(0));
        } else {
            assertThat("Tampered rest: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.tamperedRequest("_action", message);
        if (filterMissingUser) {
            assertThat("Tampered message: is not filtered out by the missing user filter", logOutput.size(), is(0));
        } else {
            assertThat("Tampered message: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.tamperedRequest(unfilteredUser, "_action", message);
        assertThat("Tampered message: unfiltered user is filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.tamperedRequest(filteredUser, "_action", message);
        assertThat("Tampered message: filtered user is not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        // connection denied
        auditTrail.connectionDenied(InetAddress.getLoopbackAddress(), "default", new SecurityIpFilterRule(false, "_all"));
        if (filterMissingUser) {
            assertThat("Connection denied: is not filtered out by the missing user filter", logOutput.size(), is(0));
        } else {
            assertThat("Connection denied: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        // connection granted
        auditTrail.connectionGranted(InetAddress.getLoopbackAddress(), "default", new SecurityIpFilterRule(false, "_all"));
        if (filterMissingUser) {
            assertThat("Connection granted: is not filtered out by the missing user filter", logOutput.size(), is(0));
        } else {
            assertThat("Connection granted: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        // runAsGranted
        auditTrail.runAsGranted(unfilteredUser, "_action", new MockMessage(threadContext), new String[] { "role1" });
        assertThat("RunAsGranted message: unfiltered user is filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.runAsGranted(filteredUser, "_action", new MockMessage(threadContext), new String[] { "role1" });
        assertThat("RunAsGranted message: filtered user is not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        // runAsDenied
        auditTrail.runAsDenied(unfilteredUser, "_action", new MockMessage(threadContext), new String[] { "role1" });
        assertThat("RunAsDenied message: unfiltered user is filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.runAsDenied(filteredUser, "_action", new MockMessage(threadContext), new String[] { "role1" });
        assertThat("RunAsDenied message: filtered user is not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.runAsDenied(unfilteredUser, getRestRequest(), new String[] { "role1" });
        assertThat("RunAsDenied rest request: unfiltered user is filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.runAsDenied(filteredUser, getRestRequest(), new String[] { "role1" });
        assertThat("RunAsDenied rest request: filtered user is not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        // authentication Success
        auditTrail.authenticationSuccess("_realm", unfilteredUser, getRestRequest());
        assertThat("AuthenticationSuccess rest request: unfiltered user is filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationSuccess("_realm", filteredUser, getRestRequest());
        assertThat("AuthenticationSuccess rest request: filtered user is not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationSuccess("_realm", unfilteredUser, "_action", message);
        assertThat("AuthenticationSuccess message: unfiltered user is filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationSuccess("_realm", filteredUser, "_action", message);
        assertThat("AuthenticationSuccess message: filtered user is not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();
    }

    public void testRealmsFilter() throws Exception {
        final List<String> allFilteredRealms = new ArrayList<>();
        final Settings.Builder settingsBuilder = Settings.builder().put(settings);
        for (int i = 0; i < randomIntBetween(1, 4); i++) {
            final List<String> filteredRealms = randomNonEmptyListOfFilteredNames();
            allFilteredRealms.addAll(filteredRealms);
            settingsBuilder.putList("xpack.security.audit.logfile.events.ignore_filters.policy" + i + ".realms", filteredRealms);
        }
        // a filter for a field consisting of an empty string ("") or an empty list([])
        // will match events that lack that field
        final boolean filterMissingRealm = randomBoolean();
        if (filterMissingRealm) {
            if (randomBoolean()) {
                final List<String> filteredRealms = randomNonEmptyListOfFilteredNames();
                // possibly renders list empty
                filteredRealms.remove(0);
                allFilteredRealms.addAll(filteredRealms);
                filteredRealms.add("");
                settingsBuilder.putList("xpack.security.audit.logfile.events.ignore_filters.missingPolicy.realms", filteredRealms);
            } else {
                settingsBuilder.putList("xpack.security.audit.logfile.events.ignore_filters.missingPolicy.realms",
                        Collections.emptyList());
            }
        }
        final String filteredRealm = randomFrom(allFilteredRealms);
        final String unfilteredRealm = UNFILTER_MARKER + randomAlphaOfLengthBetween(1, 4);
        User user;
        if (randomBoolean()) {
            user = new User("user1", new String[] { "r1" }, new User("authUsername", new String[] { "r2" }));
        } else {
            user = new User("user1", new String[] { "r1" });
        }
        final TransportMessage message = randomBoolean() ? new MockMessage(threadContext)
                : new MockIndicesRequest(threadContext, new String[] { "idx1", "idx2" });
        final MockToken authToken = new MockToken("token1");

        final LoggingAuditTrail auditTrail = new LoggingAuditTrail(settingsBuilder.build(), clusterService, logger, threadContext);
        // anonymous accessDenied
        auditTrail.anonymousAccessDenied("_action", message);
        if (filterMissingRealm) {
            assertThat("Anonymous message: not filtered out by the missing realm filter", logOutput.size(), is(0));
        } else {
            assertThat("Anonymous message: filtered out by the realm filters", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.anonymousAccessDenied(getRestRequest());
        if (filterMissingRealm) {
            assertThat("Anonymous rest request: not filtered out by the missing realm filter", logOutput.size(), is(0));
        } else {
            assertThat("Anonymous rest request: filtered out by realm filters", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        // authenticationFailed
        auditTrail.authenticationFailed(getRestRequest());
        if (filterMissingRealm) {
            assertThat("AuthenticationFailed no token rest request: not filtered out by the missing realm filter", logOutput.size(), is(0));
        } else {
            assertThat("AuthenticationFailed no token rest request: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed(authToken, "_action", message);
        if (filterMissingRealm) {
            assertThat("AuthenticationFailed token request: not filtered out by the missing realm filter", logOutput.size(), is(0));
        } else {
            assertThat("AuthenticationFailed token request: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed("_action", message);
        if (filterMissingRealm) {
            assertThat("AuthenticationFailed no token message: not filtered out by the missing realm filter", logOutput.size(), is(0));
        } else {
            assertThat("AuthenticationFailed no token message: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed(authToken, getRestRequest());
        if (filterMissingRealm) {
            assertThat("AuthenticationFailed rest request: not filtered out by the missing realm filter", logOutput.size(), is(0));
        } else {
            assertThat("AuthenticationFailed rest request: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed(unfilteredRealm, authToken, "_action", message);
        assertThat("AuthenticationFailed realm message: unfiltered realm is filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed(filteredRealm, authToken, "_action", message);
        assertThat("AuthenticationFailed realm message: filtered realm is not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed(unfilteredRealm, authToken, getRestRequest());
        assertThat("AuthenticationFailed realm rest request: unfiltered realm is filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed(filteredRealm, authToken, getRestRequest());
        assertThat("AuthenticationFailed realm rest request: filtered realm is not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        // accessGranted
        auditTrail.accessGranted(user, "_action", message, new String[] { "role1" });
        if (filterMissingRealm) {
            assertThat("AccessGranted message: not filtered out by the missing realm filter", logOutput.size(), is(0));
        } else {
            assertThat("AccessGranted message: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessGranted(SystemUser.INSTANCE, "internal:_action", message, new String[] { "role1" });
        if (filterMissingRealm) {
            assertThat("AccessGranted internal message system user: not filtered out by the missing realm filter", logOutput.size(), is(0));
        } else {
            assertThat("AccessGranted internal message system user: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessGranted(user, "internal:_action", message, new String[] { "role1" });
        if (filterMissingRealm) {
            assertThat("AccessGranted internal message: not filtered out by the missing realm filter", logOutput.size(), is(0));
        } else {
            assertThat("AccessGranted internal message: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        // accessDenied
        auditTrail.accessDenied(user, "_action", message, new String[] { "role1" });
        if (filterMissingRealm) {
            assertThat("AccessDenied message: not filtered out by the missing realm filter", logOutput.size(), is(0));
        } else {
            assertThat("AccessDenied message: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessDenied(SystemUser.INSTANCE, "internal:_action", message, new String[] { "role1" });
        if (filterMissingRealm) {
            assertThat("AccessDenied internal message system user: not filtered out by the missing realm filter", logOutput.size(), is(0));
        } else {
            assertThat("AccessDenied internal message system user: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessDenied(user, "internal:_action", message, new String[] { "role1" });
        if (filterMissingRealm) {
            assertThat("AccessGranted internal message: not filtered out by the missing realm filter", logOutput.size(), is(0));
        } else {
            assertThat("AccessGranted internal message: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        // tamperedRequest
        auditTrail.tamperedRequest(getRestRequest());
        if (filterMissingRealm) {
            assertThat("Tampered rest: is not filtered out by the missing realm filter", logOutput.size(), is(0));
        } else {
            assertThat("Tampered rest: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.tamperedRequest("_action", message);
        if (filterMissingRealm) {
            assertThat("Tampered message: is not filtered out by the missing realm filter", logOutput.size(), is(0));
        } else {
            assertThat("Tampered message: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.tamperedRequest(user, "_action", message);
        if (filterMissingRealm) {
            assertThat("Tampered message: is not filtered out by the missing realm filter", logOutput.size(), is(0));
        } else {
            assertThat("Tampered message: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        // connection denied
        auditTrail.connectionDenied(InetAddress.getLoopbackAddress(), "default", new SecurityIpFilterRule(false, "_all"));
        if (filterMissingRealm) {
            assertThat("Connection denied: is not filtered out by the missing realm filter", logOutput.size(), is(0));
        } else {
            assertThat("Connection denied: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        // connection granted
        auditTrail.connectionGranted(InetAddress.getLoopbackAddress(), "default", new SecurityIpFilterRule(false, "_all"));
        if (filterMissingRealm) {
            assertThat("Connection granted: is not filtered out by the missing realm filter", logOutput.size(), is(0));
        } else {
            assertThat("Connection granted: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        // runAsGranted
        auditTrail.runAsGranted(user, "_action", new MockMessage(threadContext), new String[] { "role1" });
        if (filterMissingRealm) {
            assertThat("RunAsGranted message: is not filtered out by the missing realm filter", logOutput.size(), is(0));
        } else {
            assertThat("RunAsGranted message: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        // runAsDenied
        auditTrail.runAsDenied(user, "_action", new MockMessage(threadContext), new String[] { "role1" });
        if (filterMissingRealm) {
            assertThat("RunAsDenied message: is not filtered out by the missing realm filter", logOutput.size(), is(0));
        } else {
            assertThat("RunAsDenied message: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.runAsDenied(user, getRestRequest(), new String[] { "role1" });
        if (filterMissingRealm) {
            assertThat("RunAsDenied rest request: is not filtered out by the missing realm filter", logOutput.size(), is(0));
        } else {
            assertThat("RunAsDenied rest request: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        // authentication Success
        auditTrail.authenticationSuccess(unfilteredRealm, user, getRestRequest());
        assertThat("AuthenticationSuccess rest request: unfiltered realm is filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationSuccess(filteredRealm, user, getRestRequest());
        assertThat("AuthenticationSuccess rest request: filtered realm is not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationSuccess(unfilteredRealm, user, "_action", message);
        assertThat("AuthenticationSuccess message: unfiltered realm is filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationSuccess(filteredRealm, user, "_action", message);
        assertThat("AuthenticationSuccess message: filtered realm is not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();
    }

    public void testRolesFilter() throws Exception {
        final List<List<String>> allFilteredRoles = new ArrayList<>();
        final Settings.Builder settingsBuilder = Settings.builder().put(settings);
        for (int i = 0; i < randomIntBetween(1, 4); i++) {
            final List<String> filteredRoles = randomNonEmptyListOfFilteredNames();
            allFilteredRoles.add(new ArrayList<>(filteredRoles));
            settingsBuilder.putList("xpack.security.audit.logfile.events.ignore_filters.policy" + i + ".roles", filteredRoles);
        }
        // a filter for a field consisting of an empty string ("") or an empty list([])
        // will match events that lack that field
        final boolean filterMissingRoles = randomBoolean();
        if (filterMissingRoles) {
            if (randomBoolean()) {
                final List<String> filteredRoles = randomNonEmptyListOfFilteredNames();
                // possibly renders list empty
                filteredRoles.remove(0);
                if (filteredRoles.isEmpty() == false) {
                    allFilteredRoles.add(new ArrayList<>(filteredRoles));
                }
                filteredRoles.add("");
                settingsBuilder.putList("xpack.security.audit.logfile.events.ignore_filters.missingPolicy.roles", filteredRoles);
            } else {
                settingsBuilder.putList("xpack.security.audit.logfile.events.ignore_filters.missingPolicy.roles",
                        Collections.emptyList());
            }
        }
        // filtered roles are a subset of the roles of any policy
        final List<String> filterPolicy = randomFrom(allFilteredRoles);
        final String[] filteredRoles = randomListFromLengthBetween(filterPolicy, 1, filterPolicy.size()).toArray(new String[0]);
        // unfiltered role sets either have roles distinct from any other policy or are
        // a mix of roles from 2 or more policies
        final List<String> unfilteredPolicy = randomFrom(allFilteredRoles);
        List<String> _unfilteredRoles;
        if (randomBoolean()) {
            _unfilteredRoles = randomListFromLengthBetween(unfilteredPolicy, 0, unfilteredPolicy.size());
            // add roles distinct from any role in any filter policy
            for (int i = 0; i < randomIntBetween(1, 4); i++) {
                _unfilteredRoles.add(UNFILTER_MARKER + randomAlphaOfLengthBetween(1, 4));
            }
        } else {
            _unfilteredRoles = randomListFromLengthBetween(unfilteredPolicy, 1, unfilteredPolicy.size());
            // add roles from other filter policies
            final List<String> otherRoles = randomNonEmptyListOfFilteredNames("other");
            _unfilteredRoles.addAll(randomListFromLengthBetween(otherRoles, 1, otherRoles.size()));
            settingsBuilder.putList("xpack.security.audit.logfile.events.ignore_filters.otherPolicy.roles", otherRoles);
        }
        final String[] unfilteredRoles = _unfilteredRoles.toArray(new String[0]);
        User user;
        if (randomBoolean()) {
            user = new User("user1", new String[] { "r1" }, new User("authUsername", new String[] { "r2" }));
        } else {
            user = new User("user1", new String[] { "r1" });
        }
        final TransportMessage message = randomBoolean() ? new MockMessage(threadContext)
                : new MockIndicesRequest(threadContext, new String[] { "idx1", "idx2" });
        final MockToken authToken = new MockToken("token1");

        final LoggingAuditTrail auditTrail = new LoggingAuditTrail(settingsBuilder.build(), clusterService, logger, threadContext);
        // anonymous accessDenied
        auditTrail.anonymousAccessDenied("_action", message);
        if (filterMissingRoles) {
            assertThat("Anonymous message: not filtered out by the missing roles filter", logOutput.size(), is(0));
        } else {
            assertThat("Anonymous message: filtered out by the roles filters", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.anonymousAccessDenied(getRestRequest());
        if (filterMissingRoles) {
            assertThat("Anonymous rest request: not filtered out by the missing roles filter", logOutput.size(), is(0));
        } else {
            assertThat("Anonymous rest request: filtered out by roles filters", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        // authenticationFailed
        auditTrail.authenticationFailed(getRestRequest());
        if (filterMissingRoles) {
            assertThat("AuthenticationFailed no token rest request: not filtered out by the missing roles filter", logOutput.size(), is(0));
        } else {
            assertThat("AuthenticationFailed no token rest request: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed(authToken, "_action", message);
        if (filterMissingRoles) {
            assertThat("AuthenticationFailed token request: not filtered out by the missing roles filter", logOutput.size(), is(0));
        } else {
            assertThat("AuthenticationFailed token request: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed("_action", message);
        if (filterMissingRoles) {
            assertThat("AuthenticationFailed no token message: not filtered out by the missing roles filter", logOutput.size(), is(0));
        } else {
            assertThat("AuthenticationFailed no token message: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed(authToken, getRestRequest());
        if (filterMissingRoles) {
            assertThat("AuthenticationFailed rest request: not filtered out by the missing roles filter", logOutput.size(), is(0));
        } else {
            assertThat("AuthenticationFailed rest request: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed("_realm", authToken, "_action", message);
        if (filterMissingRoles) {
            assertThat("AuthenticationFailed realm message: not filtered out by the missing roles filter", logOutput.size(), is(0));
        } else {
            assertThat("AuthenticationFailed realm message: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed("_realm", authToken, getRestRequest());
        if (filterMissingRoles) {
            assertThat("AuthenticationFailed realm rest request: not filtered out by the missing roles filter", logOutput.size(), is(0));
        } else {
            assertThat("AuthenticationFailed realm rest request: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        // accessGranted
        auditTrail.accessGranted(user, "_action", message, unfilteredRoles);
        assertThat("AccessGranted message: unfiltered roles filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessGranted(user, "_action", message, filteredRoles);
        assertThat("AccessGranted message: filtered roles not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessGranted(SystemUser.INSTANCE, "internal:_action", message, unfilteredRoles);
        assertThat("AccessGranted internal message system user: unfiltered roles filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessGranted(SystemUser.INSTANCE, "internal:_action", message, filteredRoles);
        assertThat("AccessGranted internal message system user: filtered roles not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessGranted(user, "internal:_action", message, unfilteredRoles);
        assertThat("AccessGranted internal message: unfiltered roles filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessGranted(user, "internal:_action", message, filteredRoles);
        assertThat("AccessGranted internal message: filtered roles not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        // accessDenied
        auditTrail.accessDenied(user, "_action", message, unfilteredRoles);
        assertThat("AccessDenied message: unfiltered roles filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessDenied(user, "_action", message, filteredRoles);
        assertThat("AccessDenied message: filtered roles not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessDenied(SystemUser.INSTANCE, "internal:_action", message, unfilteredRoles);
        assertThat("AccessDenied internal message system user: unfiltered roles filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessDenied(SystemUser.INSTANCE, "internal:_action", message, filteredRoles);
        assertThat("AccessDenied internal message system user: filtered roles not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessDenied(user, "internal:_action", message, unfilteredRoles);
        assertThat("AccessDenied internal message: unfiltered roles filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessDenied(user, "internal:_action", message, filteredRoles);
        assertThat("AccessDenied internal message: filtered roles not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        // connection denied
        auditTrail.connectionDenied(InetAddress.getLoopbackAddress(), "default", new SecurityIpFilterRule(false, "_all"));
        if (filterMissingRoles) {
            assertThat("Connection denied: is not filtered out by the missing roles filter", logOutput.size(), is(0));
        } else {
            assertThat("Connection denied: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        // connection granted
        auditTrail.connectionGranted(InetAddress.getLoopbackAddress(), "default", new SecurityIpFilterRule(false, "_all"));
        if (filterMissingRoles) {
            assertThat("Connection granted: is not filtered out by the missing roles filter", logOutput.size(), is(0));
        } else {
            assertThat("Connection granted: is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        // runAsGranted
        auditTrail.runAsGranted(user, "_action", new MockMessage(threadContext), unfilteredRoles);
        assertThat("RunAsGranted message: unfiltered roles filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.runAsGranted(user, "_action", new MockMessage(threadContext), filteredRoles);
        assertThat("RunAsGranted message: filtered roles not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        // runAsDenied
        auditTrail.runAsDenied(user, "_action", new MockMessage(threadContext), unfilteredRoles);
        assertThat("RunAsDenied message: unfiltered roles filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.runAsDenied(user, "_action", new MockMessage(threadContext), filteredRoles);
        assertThat("RunAsDenied message: filtered roles not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.runAsDenied(user, getRestRequest(), unfilteredRoles);
        assertThat("RunAsDenied rest request: unfiltered roles filtered out", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.runAsDenied(user, getRestRequest(), filteredRoles);
        assertThat("RunAsDenied rest request: filtered roles not filtered out", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        // authentication Success
        auditTrail.authenticationSuccess("_realm", user, getRestRequest());
        if (filterMissingRoles) {
            assertThat("AuthenticationSuccess rest request: is not filtered out by the missing roles filter", logOutput.size(), is(0));
        } else {
            assertThat("AuthenticationSuccess rest request: unfiltered realm is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationSuccess("_realm", user, "_action", message);
        if (filterMissingRoles) {
            assertThat("AuthenticationSuccess message: is not filtered out by the missing roles filter", logOutput.size(), is(0));
        } else {
            assertThat("AuthenticationSuccess message: unfiltered realm is filtered out", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();
    }

    public void testIndicesFilter() throws Exception {
        final List<List<String>> allFilteredIndices = new ArrayList<>();
        final Settings.Builder settingsBuilder = Settings.builder().put(settings);
        for (int i = 0; i < randomIntBetween(1, 3); i++) {
            final List<String> filteredIndices = randomNonEmptyListOfFilteredNames();
            allFilteredIndices.add(new ArrayList<>(filteredIndices));
            settingsBuilder.putList("xpack.security.audit.logfile.events.ignore_filters.policy" + i + ".indices", filteredIndices);
        }
        // a filter for a field consisting of an empty string ("") or an empty list([])
        // will match events that lack that field
        final boolean filterMissingIndices = randomBoolean();
        if (filterMissingIndices) {
            if (randomBoolean()) {
                final List<String> filteredIndices = randomNonEmptyListOfFilteredNames();
                // possibly renders list empty
                filteredIndices.remove(0);
                if (filteredIndices.isEmpty() == false) {
                    allFilteredIndices.add(new ArrayList<>(filteredIndices));
                }
                filteredIndices.add("");
                settingsBuilder.putList("xpack.security.audit.logfile.events.ignore_filters.missingPolicy.indices", filteredIndices);
            } else {
                settingsBuilder.putList("xpack.security.audit.logfile.events.ignore_filters.missingPolicy.indices",
                        Collections.emptyList());
            }
        }
        // filtered indices are a subset of the indices of any policy
        final List<String> filterPolicy = randomFrom(allFilteredIndices);
        final String[] filteredIndices = randomListFromLengthBetween(filterPolicy, 1, filterPolicy.size()).toArray(new String[0]);
        // unfiltered index sets either have indices distinct from any other in any
        // policy or are a mix of indices from 2 or more policies
        final List<String> unfilteredPolicy = randomFrom(allFilteredIndices);
        List<String> _unfilteredIndices;
        if (randomBoolean()) {
            _unfilteredIndices = randomListFromLengthBetween(unfilteredPolicy, 0, unfilteredPolicy.size());
            // add indices distinct from any index in any filter policy
            for (int i = 0; i < randomIntBetween(1, 4); i++) {
                _unfilteredIndices.add(UNFILTER_MARKER + randomAlphaOfLengthBetween(1, 4));
            }
        } else {
            _unfilteredIndices = randomListFromLengthBetween(unfilteredPolicy, 1, unfilteredPolicy.size());
            // add indices from other filter policies
            final List<String> otherIndices = randomNonEmptyListOfFilteredNames("other");
            _unfilteredIndices.addAll(randomListFromLengthBetween(otherIndices, 1, otherIndices.size()));
            settingsBuilder.putList("xpack.security.audit.logfile.events.ignore_filters.otherPolicy.indices", otherIndices);
        }
        final String[] unfilteredIndices = _unfilteredIndices.toArray(new String[0]);
        User user;
        if (randomBoolean()) {
            user = new User("user1", new String[] { "r1" }, new User("authUsername", new String[] { "r2" }));
        } else {
            user = new User("user1", new String[] { "r1" });
        }
        final MockToken authToken = new MockToken("token1");
        final TransportMessage noIndexMessage = new MockMessage(threadContext);

        final LoggingAuditTrail auditTrail = new LoggingAuditTrail(settingsBuilder.build(), clusterService, logger, threadContext);
        // anonymous accessDenied
        auditTrail.anonymousAccessDenied("_action", noIndexMessage);
        if (filterMissingIndices) {
            assertThat("Anonymous message no index: not filtered out by the missing indices filter", logOutput.size(), is(0));
        } else {
            assertThat("Anonymous message no index: filtered out by indices filters", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.anonymousAccessDenied("_action", new MockIndicesRequest(threadContext, unfilteredIndices));
        assertThat("Anonymous message unfiltered indices: filtered out by indices filters", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.anonymousAccessDenied("_action", new MockIndicesRequest(threadContext, filteredIndices));
        assertThat("Anonymous message filtered indices: not filtered out by indices filters", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.anonymousAccessDenied(getRestRequest());
        if (filterMissingIndices) {
            assertThat("Anonymous rest request: not filtered out by the missing indices filter", logOutput.size(), is(0));
        } else {
            assertThat("Anonymous rest request: filtered out by indices filters", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        // authenticationFailed
        auditTrail.authenticationFailed(getRestRequest());
        if (filterMissingIndices) {
            assertThat("AuthenticationFailed no token rest request: not filtered out by the missing indices filter", logOutput.size(),
                    is(0));
        } else {
            assertThat("AuthenticationFailed no token rest request: filtered out by indices filters", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed(authToken, "_action", noIndexMessage);
        if (filterMissingIndices) {
            assertThat("AuthenticationFailed token request no index: not filtered out by the missing indices filter", logOutput.size(),
                    is(0));
        } else {
            assertThat("AuthenticationFailed token request no index: filtered out by indices filter", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed(authToken, "_action", new MockIndicesRequest(threadContext, unfilteredIndices));
        assertThat("AuthenticationFailed token request unfiltered indices: filtered out by indices filter", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed(authToken, "_action", new MockIndicesRequest(threadContext, filteredIndices));
        assertThat("AuthenticationFailed token request filtered indices: not filtered out by indices filter", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed("_action", noIndexMessage);
        if (filterMissingIndices) {
            assertThat("AuthenticationFailed no token message no index: not filtered out by the missing indices filter", logOutput.size(),
                    is(0));
        } else {
            assertThat("AuthenticationFailed no token message: filtered out by indices filter", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed("_action", new MockIndicesRequest(threadContext, unfilteredIndices));
        assertThat("AuthenticationFailed no token request unfiltered indices: filtered out by indices filter", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed("_action", new MockIndicesRequest(threadContext, filteredIndices));
        assertThat("AuthenticationFailed no token request filtered indices: not filtered out by indices filter", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed(authToken, getRestRequest());
        if (filterMissingIndices) {
            assertThat("AuthenticationFailed rest request: not filtered out by the missing indices filter", logOutput.size(), is(0));
        } else {
            assertThat("AuthenticationFailed rest request: filtered out by indices filter", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed("_realm", authToken, "_action", noIndexMessage);
        if (filterMissingIndices) {
            assertThat("AuthenticationFailed realm message no index: not filtered out by the missing indices filter", logOutput.size(),
                    is(0));
        } else {
            assertThat("AuthenticationFailed realm message no index: filtered out by indices filter", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed("_realm", authToken, "_action", new MockIndicesRequest(threadContext, unfilteredIndices));
        assertThat("AuthenticationFailed realm message unfiltered indices: filtered out by indices filter", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed("_realm", authToken, "_action", new MockIndicesRequest(threadContext, filteredIndices));
        assertThat("AuthenticationFailed realm message filtered indices: not filtered out by indices filter", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationFailed("_realm", authToken, getRestRequest());
        if (filterMissingIndices) {
            assertThat("AuthenticationFailed realm rest request: not filtered out by the missing indices filter", logOutput.size(), is(0));
        } else {
            assertThat("AuthenticationFailed realm rest request: filtered out by indices filter", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        // accessGranted
        auditTrail.accessGranted(user, "_action", noIndexMessage, new String[] { "role1" });
        if (filterMissingIndices) {
            assertThat("AccessGranted message no index: not filtered out by the missing indices filter", logOutput.size(), is(0));
        } else {
            assertThat("AccessGranted message no index: filtered out by indices filter", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessGranted(user, "_action", new MockIndicesRequest(threadContext, unfilteredIndices), new String[] { "role1" });
        assertThat("AccessGranted message unfiltered indices: filtered out by indices filter", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessGranted(user, "_action", new MockIndicesRequest(threadContext, filteredIndices), new String[] { "role1" });
        assertThat("AccessGranted message filtered indices: not filtered out by indices filter", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessGranted(SystemUser.INSTANCE, "internal:_action", noIndexMessage, new String[] { "role1" });
        if (filterMissingIndices) {
            assertThat("AccessGranted message system user no index: not filtered out by the missing indices filter", logOutput.size(),
                    is(0));
        } else {
            assertThat("AccessGranted message system user no index: filtered out by indices filter", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessGranted(SystemUser.INSTANCE, "internal:_action", new MockIndicesRequest(threadContext, unfilteredIndices),
                new String[] { "role1" });
        assertThat("AccessGranted message system user unfiltered indices: filtered out by indices filter", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessGranted(SystemUser.INSTANCE, "internal:_action", new MockIndicesRequest(threadContext, filteredIndices),
                new String[] { "role1" });
        assertThat("AccessGranted message system user filtered indices: not filtered out by indices filter", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        // accessDenied
        auditTrail.accessDenied(user, "_action", noIndexMessage, new String[] { "role1" });
        if (filterMissingIndices) {
            assertThat("AccessDenied message no index: not filtered out by the missing indices filter", logOutput.size(), is(0));
        } else {
            assertThat("AccessDenied message no index: filtered out by indices filter", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessDenied(user, "_action", new MockIndicesRequest(threadContext, unfilteredIndices), new String[] { "role1" });
        assertThat("AccessDenied message unfiltered indices: filtered out by indices filter", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessDenied(user, "_action", new MockIndicesRequest(threadContext, filteredIndices), new String[] { "role1" });
        assertThat("AccessDenied message filtered indices: not filtered out by indices filter", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessDenied(SystemUser.INSTANCE, "internal:_action", noIndexMessage, new String[] { "role1" });
        if (filterMissingIndices) {
            assertThat("AccessDenied message system user no index: not filtered out by the missing indices filter", logOutput.size(),
                    is(0));
        } else {
            assertThat("AccessDenied message system user no index: filtered out by indices filter", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessDenied(SystemUser.INSTANCE, "internal:_action", new MockIndicesRequest(threadContext, unfilteredIndices),
                new String[] { "role1" });
        assertThat("AccessDenied message system user unfiltered indices: filtered out by indices filter", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.accessDenied(SystemUser.INSTANCE, "internal:_action", new MockIndicesRequest(threadContext, filteredIndices),
                new String[] { "role1" });
        assertThat("AccessGranted message system user filtered indices: not filtered out by indices filter", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        // connection denied
        auditTrail.connectionDenied(InetAddress.getLoopbackAddress(), "default", new SecurityIpFilterRule(false, "_all"));
        if (filterMissingIndices) {
            assertThat("Connection denied: not filtered out by missing indices filter", logOutput.size(), is(0));
        } else {
            assertThat("Connection denied: filtered out by indices filter", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        // connection granted
        auditTrail.connectionGranted(InetAddress.getLoopbackAddress(), "default", new SecurityIpFilterRule(false, "_all"));
        if (filterMissingIndices) {
            assertThat("Connection granted: not filtered out by missing indices filter", logOutput.size(), is(0));
        } else {
            assertThat("Connection granted: filtered out by indices filter", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        // runAsGranted
        auditTrail.runAsGranted(user, "_action", noIndexMessage, new String[] { "role1" });
        if (filterMissingIndices) {
            assertThat("RunAsGranted message no index: not filtered out by missing indices filter", logOutput.size(), is(0));
        } else {
            assertThat("RunAsGranted message no index: filtered out by indices filter", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.runAsGranted(user, "_action", new MockIndicesRequest(threadContext, unfilteredIndices), new String[] { "role1" });
        assertThat("RunAsGranted message unfiltered indices: filtered out by indices filter", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.runAsGranted(user, "_action", new MockIndicesRequest(threadContext, filteredIndices), new String[] { "role1" });
        assertThat("RunAsGranted message filtered indices: not filtered out by indices filter", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        // runAsDenied
        auditTrail.runAsDenied(user, "_action", noIndexMessage, new String[] { "role1" });
        if (filterMissingIndices) {
            assertThat("RunAsDenied message no index: not filtered out by missing indices filter", logOutput.size(), is(0));
        } else {
            assertThat("RunAsDenied message no index: filtered out by indices filter", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.runAsDenied(user, "_action", new MockIndicesRequest(threadContext, unfilteredIndices), new String[] { "role1" });
        assertThat("RunAsDenied message unfiltered indices: filtered out by indices filter", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.runAsDenied(user, "_action", new MockIndicesRequest(threadContext, filteredIndices), new String[] { "role1" });
        assertThat("RunAsDenied message filtered indices: not filtered out by indices filter", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.runAsDenied(user, getRestRequest(), new String[] { "role1" });
        if (filterMissingIndices) {
            assertThat("RunAsDenied rest request: not filtered out by missing indices filter", logOutput.size(), is(0));
        } else {
            assertThat("RunAsDenied rest request: filtered out by indices filter", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        // authentication Success
        auditTrail.authenticationSuccess("_realm", user, getRestRequest());
        if (filterMissingIndices) {
            assertThat("AuthenticationSuccess rest request: is not filtered out by the missing indices filter", logOutput.size(), is(0));
        } else {
            assertThat("AuthenticationSuccess rest request: filtered out by indices filter", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationSuccess("_realm", user, "_action", noIndexMessage);
        if (filterMissingIndices) {
            assertThat("AuthenticationSuccess message no index: not filtered out by missing indices filter", logOutput.size(), is(0));
        } else {
            assertThat("AuthenticationSuccess message no index: filtered out by indices filter", logOutput.size(), is(1));
        }
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationSuccess("_realm", user, "_action", new MockIndicesRequest(threadContext, unfilteredIndices));
        assertThat("AuthenticationSuccess message unfiltered indices: filtered out by indices filter", logOutput.size(), is(1));
        logOutput.clear();
        threadContext.stashContext();

        auditTrail.authenticationSuccess("_realm", user, "_action", new MockIndicesRequest(threadContext, filteredIndices));
        assertThat("AuthenticationSuccess message filtered indices: not filtered out by indices filter", logOutput.size(), is(0));
        logOutput.clear();
        threadContext.stashContext();
    }

    private <T> List<T> randomListFromLengthBetween(List<T> l, int min, int max) {
        assert min >= 0 && min <= max && max <= l.size();
        final int len = randomIntBetween(min, max);
        final List<T> ans = new ArrayList<>(len);
        while (ans.size() < len) {
            ans.add(randomFrom(l));
        }
        return ans;
    }

    private List<String> randomNonEmptyListOfFilteredNames(String... namePrefix) {
        final List<String> filtered = new ArrayList<>(4);
        for (int i = 0; i < randomIntBetween(1, 4); i++) {
            filtered.add(FILTER_MARKER + Strings.arrayToCommaDelimitedString(namePrefix) + randomAlphaOfLengthBetween(1, 4));
        }
        return filtered;
    }

    private RestRequest getRestRequest() throws IOException {
        final RestContent content = randomFrom(RestContent.values());
        final FakeRestRequest.Builder builder = new Builder(NamedXContentRegistry.EMPTY);
        if (content.hasContent()) {
            builder.withContent(content.content(), XContentType.JSON);
        }
        builder.withPath("_uri");
        final byte address[] = InetAddress.getByName(randomBoolean() ? "127.0.0.1" : "::1").getAddress();
        builder.withRemoteAddress(new InetSocketAddress(InetAddress.getByAddress("_hostname", address), 9200));
        builder.withParams(Collections.emptyMap());
        return builder.build();
    }

    private static class MockToken implements AuthenticationToken {
        private final String principal;

        MockToken(String principal) {
            this.principal = principal;
        }

        @Override
        public String principal() {
            return this.principal;
        }

        @Override
        public Object credentials() {
            fail("it's not allowed to print the credentials of the auth token");
            return null;
        }

        @Override
        public void clearCredentials() {

        }
    }

    static class MockIndicesRequest extends org.elasticsearch.action.MockIndicesRequest {

        MockIndicesRequest(ThreadContext threadContext, String... indices) throws IOException {
            super(IndicesOptions.strictExpandOpenAndForbidClosed(), indices);
            if (randomBoolean()) {
                remoteAddress(buildNewFakeTransportAddress());
            }
            if (randomBoolean()) {
                RemoteHostHeader.putRestRemoteAddress(threadContext, new InetSocketAddress(forge("localhost", "127.0.0.1"), 1234));
            }
        }

        /** creates address without any lookups. hostname can be null, for missing */
        private InetAddress forge(String hostname, String address) throws IOException {
            final byte bytes[] = InetAddress.getByName(address).getAddress();
            return InetAddress.getByAddress(hostname, bytes);
        }

        @Override
        public String toString() {
            return "mock-message";
        }
    }


}