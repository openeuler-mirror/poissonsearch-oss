/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.audit.logfile;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.network.NetworkAddress;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.transport.LocalTransportAddress;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.transport.TransportMessage;
import org.elasticsearch.xpack.security.audit.AuditUtil;
import org.elasticsearch.xpack.security.authc.AuthenticationToken;
import org.elasticsearch.xpack.security.rest.RemoteHostHeader;
import org.elasticsearch.xpack.security.transport.filter.IPFilter;
import org.elasticsearch.xpack.security.transport.filter.SecurityIpFilterRule;
import org.elasticsearch.xpack.security.user.SystemUser;
import org.elasticsearch.xpack.security.user.User;
import org.junit.Before;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Locale;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class LoggingAuditTrailTests extends ESTestCase {

    private enum RestContent {
        VALID() {
            @Override
            protected boolean hasContent() {
                return true;
            }

            @Override
            protected BytesReference content() {
                return new BytesArray("{ \"key\": \"value\"}");
            }

            @Override
            protected String expectedMessage() {
                return "{ \"key\": \"value\"}";
            }
        },
        INVALID() {
            @Override
            protected boolean hasContent() {
                return true;
            }

            @Override
            protected BytesReference content() {
                return new BytesArray("{ \"key\": \"value\"");
            }

            @Override
            protected String expectedMessage() {
                return "{ \"key\": \"value\"";
            }
        },
        EMPTY() {
            @Override
            protected boolean hasContent() {
                return false;
            }

            @Override
            protected BytesReference content() {
                throw new RuntimeException("should never be called");
            }

            @Override
            protected String expectedMessage() {
                return "";
            }
        };

        protected abstract boolean hasContent();
        protected abstract BytesReference content();
        protected abstract String expectedMessage();
    }

    private String prefix;
    private Settings settings;
    private DiscoveryNode localNode;
    private ClusterService clusterService;
    private ThreadContext threadContext;

    @Before
    public void init() throws Exception {
        settings = Settings.builder()
                .put("xpack.security.audit.logfile.prefix.emit_node_host_address", randomBoolean())
                .put("xpack.security.audit.logfile.prefix.emit_node_host_name", randomBoolean())
                .put("xpack.security.audit.logfile.prefix.emit_node_name", randomBoolean())
                .build();
        localNode = mock(DiscoveryNode.class);
        when(localNode.getHostAddress()).thenReturn(LocalTransportAddress.buildUnique().toString());
        clusterService = mock(ClusterService.class);
        when(clusterService.localNode()).thenReturn(localNode);
        prefix = LoggingAuditTrail.resolvePrefix(settings, localNode);
    }

    public void testAnonymousAccessDeniedTransport() throws Exception {
        for (Level level : Level.values()) {
            threadContext = new ThreadContext(Settings.EMPTY);
            Logger logger = CapturingLogger.newCapturingLogger(level);
            LoggingAuditTrail auditTrail = new LoggingAuditTrail(settings, clusterService, logger, threadContext);
            TransportMessage message = randomBoolean() ? new MockMessage(threadContext) : new MockIndicesRequest(threadContext);
            String origins = LoggingAuditTrail.originAttributes(message, clusterService.localNode(), threadContext);
            auditTrail.anonymousAccessDenied("_action", message);
            switch (level.toString()) {
                case "ERROR":
                    assertEmptyLog(logger);
                    break;
                case "WARN":
                case "INFO":
                    if (message instanceof IndicesRequest) {
                        assertMsg(logger, Level.WARN, prefix + "[transport] [anonymous_access_denied]\t" + origins +
                                ", action=[_action], indices=[" + indices(message) + "]");
                    } else {
                        assertMsg(logger, Level.WARN, prefix + "[transport] [anonymous_access_denied]\t"  + origins + ", action=[_action]");
                    }
                    break;
                case "DEBUG":
                case "TRACE":
                    if (message instanceof IndicesRequest) {
                        assertMsg(logger, Level.DEBUG, prefix + "[transport] [anonymous_access_denied]\t"  + origins +
                                ", action=[_action], indices=[" + indices(message) + "], request=[MockIndicesRequest]");
                    } else {
                        assertMsg(logger, Level.DEBUG, prefix + "[transport] [anonymous_access_denied]\t"  + origins +
                                ", action=[_action], request=[MockMessage]");
                    }
            }
        }
    }

    public void testAnonymousAccessDeniedRest() throws Exception {
        RestRequest request = mock(RestRequest.class);
        InetAddress address = forge("_hostname", randomBoolean() ? "127.0.0.1" : "::1");
        when(request.getRemoteAddress()).thenReturn(new InetSocketAddress(address, 9200));
        when(request.uri()).thenReturn("_uri");
        String expectedMessage = prepareRestContent(request);

        for (Level level : Level.values()) {
            threadContext = new ThreadContext(Settings.EMPTY);
            Logger logger = CapturingLogger.newCapturingLogger(level);
            LoggingAuditTrail auditTrail = new LoggingAuditTrail(settings, clusterService, logger, threadContext);
            auditTrail.anonymousAccessDenied(request);
            switch (level.toString()) {
                case "ERROR":
                    assertEmptyLog(logger);
                    break;
                case "WARN":
                case "INFO":
                    assertMsg(logger, Level.WARN, prefix + "[rest] [anonymous_access_denied]\torigin_address=[" +
                            NetworkAddress.format(address) + "], uri=[_uri]");
                    break;
                case "DEBUG":
                case "TRACE":
                    assertMsg(logger, Level.DEBUG, prefix + "[rest] [anonymous_access_denied]\torigin_address=[" +
                            NetworkAddress.format(address) + "], uri=[_uri], request_body=[" + expectedMessage + "]");
            }
        }
    }

    public void testAuthenticationFailed() throws Exception {
        for (Level level : Level.values()) {
            threadContext = new ThreadContext(Settings.EMPTY);
            Logger logger = CapturingLogger.newCapturingLogger(level);
            LoggingAuditTrail auditTrail = new LoggingAuditTrail(settings, clusterService, logger, threadContext);
            TransportMessage message = randomBoolean() ? new MockMessage(threadContext) : new MockIndicesRequest(threadContext);
            String origins = LoggingAuditTrail.originAttributes(message, localNode, threadContext);;
            auditTrail.authenticationFailed(new MockToken(), "_action", message);
            switch (level.toString()) {
                case "ERROR":
                case "WARN":
                case "INFO":
                    if (message instanceof IndicesRequest) {
                        assertMsg(logger, Level.ERROR, prefix + "[transport] [authentication_failed]\t" + origins +
                                ", principal=[_principal], action=[_action], indices=[" + indices(message) + "]");
                    } else {
                        assertMsg(logger, Level.ERROR, prefix + "[transport] [authentication_failed]\t" + origins +
                                ", principal=[_principal], action=[_action]");
                    }
                    break;
                case "DEBUG":
                case "TRACE":
                    if (message instanceof IndicesRequest) {
                        assertMsg(logger, Level.DEBUG, prefix + "[transport] [authentication_failed]\t" + origins +
                                ", principal=[_principal], action=[_action], indices=[" + indices(message) +
                                "], request=[MockIndicesRequest]");
                    } else {
                        assertMsg(logger, Level.DEBUG, prefix + "[transport] [authentication_failed]\t" + origins +
                                ", principal=[_principal], action=[_action], request=[MockMessage]");
                    }
            }
        }
    }

    public void testAuthenticationFailedNoToken() throws Exception {
        for (Level level : Level.values()) {
            threadContext = new ThreadContext(Settings.EMPTY);
            Logger logger = CapturingLogger.newCapturingLogger(level);
            LoggingAuditTrail auditTrail = new LoggingAuditTrail(settings, clusterService, logger, threadContext);
            TransportMessage message = randomBoolean() ? new MockMessage(threadContext) : new MockIndicesRequest(threadContext);
            String origins = LoggingAuditTrail.originAttributes(message, localNode, threadContext);;
            auditTrail.authenticationFailed("_action", message);
            switch (level.toString()) {
                case "ERROR":
                case "WARN":
                case "INFO":
                    if (message instanceof IndicesRequest) {
                        assertMsg(logger, Level.ERROR, prefix + "[transport] [authentication_failed]\t" + origins +
                                ", action=[_action], indices=[" + indices(message) + "]");
                    } else {
                        assertMsg(logger, Level.ERROR, prefix + "[transport] [authentication_failed]\t" + origins +
                                ", action=[_action]");
                    }
                    break;
                case "DEBUG":
                case "TRACE":
                    if (message instanceof IndicesRequest) {
                        assertMsg(logger, Level.DEBUG, prefix + "[transport] [authentication_failed]\t" + origins +
                                ", action=[_action], indices=[" + indices(message) + "], request=[MockIndicesRequest]");
                    } else {
                        assertMsg(logger, Level.DEBUG, prefix + "[transport] [authentication_failed]\t" + origins +
                                ", action=[_action], request=[MockMessage]");
                    }
            }
        }
    }

    public void testAuthenticationFailedRest() throws Exception {
        for (Level level : Level.values()) {
            threadContext = new ThreadContext(Settings.EMPTY);
            RestRequest request = mock(RestRequest.class);
            InetAddress address = forge("_hostname", randomBoolean() ? "127.0.0.1" : "::1");
            when(request.getRemoteAddress()).thenReturn(new InetSocketAddress(address, 9200));
            when(request.uri()).thenReturn("_uri");
            String expectedMessage = prepareRestContent(request);
            Logger logger = CapturingLogger.newCapturingLogger(level);
            LoggingAuditTrail auditTrail = new LoggingAuditTrail(settings, clusterService, logger, threadContext);
            auditTrail.authenticationFailed(new MockToken(), request);
            switch (level.toString()) {
                case "ERROR":
                case "WARN":
                case "INFO":
                    assertMsg(logger, Level.ERROR, prefix + "[rest] [authentication_failed]\torigin_address=[" +
                            NetworkAddress.format(address) + "], principal=[_principal], uri=[_uri]");
                    break;
                case "DEBUG":
                case "TRACE":
                    assertMsg(logger, Level.DEBUG, prefix + "[rest] [authentication_failed]\torigin_address=[" +
                            NetworkAddress.format(address) + "], principal=[_principal], uri=[_uri], request_body=[" +
                            expectedMessage + "]");
            }
        }
    }

    public void testAuthenticationFailedRestNoToken() throws Exception {
        for (Level level : Level.values()) {
            threadContext = new ThreadContext(Settings.EMPTY);
            RestRequest request = mock(RestRequest.class);
            InetAddress address = forge("_hostname", randomBoolean() ? "127.0.0.1" : "::1");
            when(request.getRemoteAddress()).thenReturn(new InetSocketAddress(address, 9200));
            when(request.uri()).thenReturn("_uri");
            String expectedMessage = prepareRestContent(request);
            Logger logger = CapturingLogger.newCapturingLogger(level);
            LoggingAuditTrail auditTrail = new LoggingAuditTrail(settings, clusterService, logger, threadContext);
            auditTrail.authenticationFailed(request);
            switch (level.toString()) {
                case "ERROR":
                case "WARN":
                case "INFO":
                    assertMsg(logger, Level.ERROR, prefix + "[rest] [authentication_failed]\torigin_address=[" +
                            NetworkAddress.format(address) + "], uri=[_uri]");
                    break;
                case "DEBUG":
                case "TRACE":
                    assertMsg(logger, Level.DEBUG, prefix + "[rest] [authentication_failed]\torigin_address=[" +
                            NetworkAddress.format(address) + "], uri=[_uri], request_body=[" + expectedMessage + "]");
            }
        }
    }

    public void testAuthenticationFailedRealm() throws Exception {
        for (Level level : Level.values()) {
            threadContext = new ThreadContext(Settings.EMPTY);
            Logger logger = CapturingLogger.newCapturingLogger(level);
            LoggingAuditTrail auditTrail = new LoggingAuditTrail(settings, clusterService, logger, threadContext);
            TransportMessage message = randomBoolean() ? new MockMessage(threadContext) : new MockIndicesRequest(threadContext);
            String origins = LoggingAuditTrail.originAttributes(message, localNode, threadContext);;
            auditTrail.authenticationFailed("_realm", new MockToken(), "_action", message);
            switch (level.toString()) {
                case "ERROR":
                case "WARN":
                case "INFO":
                case "DEBUG":
                    assertEmptyLog(logger);
                    break;
                case "TRACE":
                    if (message instanceof IndicesRequest) {
                        assertMsg(logger, Level.TRACE, prefix + "[transport] [authentication_failed]\trealm=[_realm], " + origins +
                                ", principal=[_principal], action=[_action], indices=[" + indices(message) + "], " +
                                "request=[MockIndicesRequest]");
                    } else {
                        assertMsg(logger, Level.TRACE, prefix + "[transport] [authentication_failed]\trealm=[_realm], " + origins +
                                ", principal=[_principal], action=[_action], request=[MockMessage]");
                    }
            }
        }
    }

    public void testAuthenticationFailedRealmRest() throws Exception {
        for (Level level : Level.values()) {
            threadContext = new ThreadContext(Settings.EMPTY);
            RestRequest request = mock(RestRequest.class);
            InetAddress address = forge("_hostname", randomBoolean() ? "127.0.0.1" : "::1");
            when(request.getRemoteAddress()).thenReturn(new InetSocketAddress(address, 9200));
            when(request.uri()).thenReturn("_uri");
            String expectedMessage = prepareRestContent(request);
            Logger logger = CapturingLogger.newCapturingLogger(level);
            LoggingAuditTrail auditTrail = new LoggingAuditTrail(settings, clusterService, logger, threadContext);
            auditTrail.authenticationFailed("_realm", new MockToken(), request);
            switch (level.toString()) {
                case "ERROR":
                case "WARN":
                case "INFO":
                case "DEBUG":
                    assertEmptyLog(logger);
                    break;
                case "TRACE":
                    assertMsg(logger, Level.TRACE, prefix + "[rest] [authentication_failed]\trealm=[_realm], origin_address=[" +
                            NetworkAddress.format(address) + "], principal=[_principal], uri=[_uri], request_body=[" +
                            expectedMessage + "]");
            }
        }
    }

    public void testAccessGranted() throws Exception {
        for (Level level : Level.values()) {
            threadContext = new ThreadContext(Settings.EMPTY);
            Logger logger = CapturingLogger.newCapturingLogger(level);
            LoggingAuditTrail auditTrail = new LoggingAuditTrail(settings, clusterService, logger, threadContext);
            TransportMessage message = randomBoolean() ? new MockMessage(threadContext) : new MockIndicesRequest(threadContext);
            String origins = LoggingAuditTrail.originAttributes(message, localNode, threadContext);
            boolean runAs = randomBoolean();
            User user;
            if (runAs) {
                user = new User("_username", new String[]{"r1"},
                        new User("running as", new String[] {"r2"}));
            } else {
                user = new User("_username", new String[]{"r1"});
            }
            String userInfo = runAs ? "principal=[running as], run_by_principal=[_username]" : "principal=[_username]";
            auditTrail.accessGranted(user, "_action", message);
            switch (level.toString()) {
                case "ERROR":
                case "WARN":
                    assertEmptyLog(logger);
                    break;
                case "INFO":
                    if (message instanceof IndicesRequest) {
                        assertMsg(logger, Level.INFO, prefix + "[transport] [access_granted]\t" + origins + ", " + userInfo +
                                ", action=[_action], indices=[" + indices(message) + "]");
                    } else {
                        assertMsg(logger, Level.INFO, prefix + "[transport] [access_granted]\t" + origins + ", " + userInfo +
                                ", action=[_action]");
                    }
                    break;
                case "DEBUG":
                case "TRACE":
                    if (message instanceof IndicesRequest) {
                        assertMsg(logger, Level.DEBUG, prefix + "[transport] [access_granted]\t" + origins + ", " + userInfo +
                                ", action=[_action], indices=[" + indices(message) + "], request=[MockIndicesRequest]");
                    } else {
                        assertMsg(logger, Level.DEBUG, prefix + "[transport] [access_granted]\t" + origins + ", " + userInfo +
                                ", action=[_action], request=[MockMessage]");
                    }
            }
        }
    }

    public void testAccessGrantedInternalSystemAction() throws Exception {
        for (Level level : Level.values()) {
            threadContext = new ThreadContext(Settings.EMPTY);
            Logger logger = CapturingLogger.newCapturingLogger(level);
            LoggingAuditTrail auditTrail = new LoggingAuditTrail(settings, clusterService, logger, threadContext);
            TransportMessage message = randomBoolean() ? new MockMessage(threadContext) : new MockIndicesRequest(threadContext);
            String origins = LoggingAuditTrail.originAttributes(message, localNode, threadContext);
            auditTrail.accessGranted(SystemUser.INSTANCE, "internal:_action", message);
            switch (level.toString()) {
                case "ERROR":
                case "WARN":
                case "INFO":
                case "DEBUG":
                    assertEmptyLog(logger);
                    break;
                case "TRACE":
                    if (message instanceof IndicesRequest) {
                        assertMsg(logger, Level.TRACE, prefix + "[transport] [access_granted]\t" + origins + ", principal=[" +
                                SystemUser.INSTANCE.principal()
                                + "], action=[internal:_action], indices=[" + indices(message) + "], request=[MockIndicesRequest]");
                    } else {
                        assertMsg(logger, Level.TRACE, prefix + "[transport] [access_granted]\t" + origins + ", principal=[" +
                                SystemUser.INSTANCE.principal() + "], action=[internal:_action], request=[MockMessage]");
                    }
            }
        }
    }

    public void testAccessGrantedInternalSystemActionNonSystemUser() throws Exception {
        for (Level level : Level.values()) {
            threadContext = new ThreadContext(Settings.EMPTY);
            Logger logger = CapturingLogger.newCapturingLogger(level);
            LoggingAuditTrail auditTrail = new LoggingAuditTrail(settings, clusterService, logger, threadContext);
            TransportMessage message = randomBoolean() ? new MockMessage(threadContext) : new MockIndicesRequest(threadContext);
            String origins = LoggingAuditTrail.originAttributes(message, localNode, threadContext);
            boolean runAs = randomBoolean();
            User user;
            if (runAs) {
                user = new User("_username", new String[]{"r1"},
                        new User("running as", new String[] {"r2"}));
            } else {
                user = new User("_username", new String[]{"r1"});
            }
            String userInfo = runAs ? "principal=[running as], run_by_principal=[_username]" : "principal=[_username]";
            auditTrail.accessGranted(user, "internal:_action", message);
            switch (level.toString()) {
                case "ERROR":
                case "WARN":
                    assertEmptyLog(logger);
                    break;
                case "INFO":
                    if (message instanceof IndicesRequest) {
                        assertMsg(logger, Level.INFO, prefix + "[transport] [access_granted]\t" + origins + ", " + userInfo +
                                ", action=[internal:_action], indices=[" + indices(message) + "]");
                    } else {
                        assertMsg(logger, Level.INFO, prefix + "[transport] [access_granted]\t" + origins + ", " + userInfo +
                                ", action=[internal:_action]");
                    }
                    break;
                case "DEBUG":
                case "TRACE":
                    if (message instanceof IndicesRequest) {
                        assertMsg(logger, Level.DEBUG, prefix + "[transport] [access_granted]\t" + origins + ", " + userInfo +
                                ", action=[internal:_action], indices=[" + indices(message) + "], request=[MockIndicesRequest]");
                    } else {
                        assertMsg(logger, Level.DEBUG, prefix + "[transport] [access_granted]\t" + origins + ", " + userInfo +
                                ", action=[internal:_action], request=[MockMessage]");
                    }
            }
        }
    }

    public void testAccessDenied() throws Exception {
        for (Level level : Level.values()) {
            threadContext = new ThreadContext(Settings.EMPTY);
            Logger logger = CapturingLogger.newCapturingLogger(level);
            LoggingAuditTrail auditTrail = new LoggingAuditTrail(settings, clusterService, logger, threadContext);
            TransportMessage message = randomBoolean() ? new MockMessage(threadContext) : new MockIndicesRequest(threadContext);
            String origins = LoggingAuditTrail.originAttributes(message, localNode, threadContext);
            boolean runAs = randomBoolean();
            User user;
            if (runAs) {
                user = new User("_username", new String[]{"r1"},
                        new User("running as", new String[] {"r2"}));
            } else {
                user = new User("_username", new String[]{"r1"});
            }
            String userInfo = runAs ? "principal=[running as], run_by_principal=[_username]" : "principal=[_username]";
            auditTrail.accessDenied(user, "_action", message);
            switch (level.toString()) {
                case "ERROR":
                case "WARN":
                case "INFO":
                    if (message instanceof IndicesRequest) {
                        assertMsg(logger, Level.ERROR, prefix + "[transport] [access_denied]\t" + origins + ", " + userInfo +
                                ", action=[_action], indices=[" + indices(message) + "]");
                    } else {
                        assertMsg(logger, Level.ERROR, prefix + "[transport] [access_denied]\t"  + origins + ", " + userInfo +
                                ", action=[_action]");
                    }
                    break;
                case "DEBUG":
                case "TRACE":
                    if (message instanceof IndicesRequest) {
                        assertMsg(logger, Level.DEBUG, prefix + "[transport] [access_denied]\t" + origins + ", " + userInfo +
                                ", action=[_action], indices=[" + indices(message) + "], request=[MockIndicesRequest]");
                    } else {
                        assertMsg(logger, Level.DEBUG, prefix + "[transport] [access_denied]\t" + origins + ", " + userInfo +
                                ", action=[_action], request=[MockMessage]");
                    }
            }
        }
    }

    public void testTamperedRequestRest() throws Exception {
        RestRequest request = mock(RestRequest.class);
        InetAddress address = forge("_hostname", randomBoolean() ? "127.0.0.1" : "::1");
        when(request.getRemoteAddress()).thenReturn(new InetSocketAddress(address, 9200));
        when(request.uri()).thenReturn("_uri");
        String expectedMessage = prepareRestContent(request);

        for (Level level : Level.values()) {
            threadContext = new ThreadContext(Settings.EMPTY);
            Logger logger = CapturingLogger.newCapturingLogger(level);
            LoggingAuditTrail auditTrail = new LoggingAuditTrail(settings, clusterService, logger, threadContext);
            auditTrail.tamperedRequest(request);
            switch (level.toString()) {
                case "ERROR":
                case "WARN":
                case "INFO":
                    assertMsg(logger, Level.ERROR, prefix + "[rest] [tampered_request]\torigin_address=[" +
                            NetworkAddress.format(address) + "], uri=[_uri]");
                    break;
                case "DEBUG":
                case "TRACE":
                    assertMsg(logger, Level.DEBUG, prefix + "[rest] [tampered_request]\torigin_address=[" +
                            NetworkAddress.format(address) + "], uri=[_uri], request_body=[" + expectedMessage + "]");
            }
        }
    }

    public void testTamperedRequest() throws Exception {
        String action = "_action";
        for (Level level : Level.values()) {
            threadContext = new ThreadContext(Settings.EMPTY);
            TransportMessage message = randomBoolean() ? new MockMessage(threadContext) : new MockIndicesRequest(threadContext);
            String origins = LoggingAuditTrail.originAttributes(message, localNode, threadContext);
            Logger logger = CapturingLogger.newCapturingLogger(level);
            LoggingAuditTrail auditTrail = new LoggingAuditTrail(settings, clusterService, logger, threadContext);
            auditTrail.tamperedRequest(action, message);
            switch (level.toString()) {
                case "ERROR":
                case "WARN":
                case "INFO":
                    if (message instanceof IndicesRequest) {
                        assertMsg(logger, Level.ERROR, prefix + "[transport] [tampered_request]\t" + origins +
                                ", action=[_action], indices=[" + indices(message) + "]");
                    } else {
                        assertMsg(logger, Level.ERROR, prefix + "[transport] [tampered_request]\t"  + origins + ", action=[_action]");
                    }
                    break;
                case "DEBUG":
                case "TRACE":
                    if (message instanceof IndicesRequest) {
                        assertMsg(logger, Level.DEBUG, prefix + "[transport] [tampered_request]\t" + origins +
                                ", action=[_action], indices=[" + indices(message) + "], request=[MockIndicesRequest]");
                    } else {
                        assertMsg(logger, Level.DEBUG, prefix + "[transport] [tampered_request]\t" + origins +
                                ", action=[_action], request=[MockMessage]");
                    }
            }
        }
    }

    public void testTamperedRequestWithUser() throws Exception {
        String action = "_action";
        final boolean runAs = randomBoolean();
        User user;
        if (runAs) {
            user = new User("_username", new String[]{"r1"}, new User("running as", new String[] {"r2"}));
        } else {
            user = new User("_username", new String[]{"r1"});
        }
        String userInfo = runAs ? "principal=[running as], run_by_principal=[_username]" : "principal=[_username]";
        for (Level level : Level.values()) {
            threadContext = new ThreadContext(Settings.EMPTY);
            TransportMessage message = randomBoolean() ? new MockMessage(threadContext) : new MockIndicesRequest(threadContext);
            String origins = LoggingAuditTrail.originAttributes(message, localNode, threadContext);
            Logger logger = CapturingLogger.newCapturingLogger(level);
            LoggingAuditTrail auditTrail = new LoggingAuditTrail(settings, clusterService, logger, threadContext);
            auditTrail.tamperedRequest(user, action, message);
            switch (level.toString()) {
                case "ERROR":
                case "WARN":
                case "INFO":
                    if (message instanceof IndicesRequest) {
                        assertMsg(logger, Level.ERROR, prefix + "[transport] [tampered_request]\t" + origins + ", " + userInfo +
                                ", action=[_action], indices=[" + indices(message) + "]");
                    } else {
                        assertMsg(logger, Level.ERROR, prefix + "[transport] [tampered_request]\t"  + origins + ", " + userInfo +
                                ", action=[_action]");
                    }
                    break;
                case "DEBUG":
                case "TRACE":
                    if (message instanceof IndicesRequest) {
                        assertMsg(logger, Level.DEBUG, prefix + "[transport] [tampered_request]\t" + origins + ", " + userInfo +
                                ", action=[_action], indices=[" + indices(message) + "], request=[MockIndicesRequest]");
                    } else {
                        assertMsg(logger, Level.DEBUG, prefix + "[transport] [tampered_request]\t" + origins + ", " + userInfo +
                                ", action=[_action], request=[MockMessage]");
                    }
            }
        }
    }

    public void testConnectionDenied() throws Exception {
        for (Level level : Level.values()) {
            threadContext = new ThreadContext(Settings.EMPTY);
            Logger logger = CapturingLogger.newCapturingLogger(level);
            LoggingAuditTrail auditTrail = new LoggingAuditTrail(settings, clusterService, logger, threadContext);
            InetAddress inetAddress = InetAddress.getLoopbackAddress();
            SecurityIpFilterRule rule = new SecurityIpFilterRule(false, "_all");
            auditTrail.connectionDenied(inetAddress, "default", rule);
            switch (level.toString()) {
                case "ERROR":
                    assertMsg(logger, Level.ERROR, String.format(Locale.ROOT, prefix +
                            "[ip_filter] [connection_denied]\torigin_address=[%s], transport_profile=[%s], rule=[deny %s]",
                            NetworkAddress.format(inetAddress), "default", "_all"));
                    break;
                case "WARN":
                case "INFO":
                case "DEBUG":
                case "TRACE":
            }
        }
    }

    public void testConnectionGranted() throws Exception {
        for (Level level : Level.values()) {
            threadContext = new ThreadContext(Settings.EMPTY);
            Logger logger = CapturingLogger.newCapturingLogger(level);
            LoggingAuditTrail auditTrail = new LoggingAuditTrail(settings, clusterService, logger, threadContext);
            InetAddress inetAddress = InetAddress.getLoopbackAddress();
            SecurityIpFilterRule rule = IPFilter.DEFAULT_PROFILE_ACCEPT_ALL;
            auditTrail.connectionGranted(inetAddress, "default", rule);
            switch (level.toString()) {
                case "ERROR":
                case "WARN":
                case "INFO":
                case "DEBUG":
                    assertEmptyLog(logger);
                    break;
                case "TRACE":
                    assertMsg(logger, Level.TRACE, String.format(Locale.ROOT, prefix + "[ip_filter] " +
                            "[connection_granted]\torigin_address=[%s], transport_profile=[default], rule=[allow default:accept_all]",
                            NetworkAddress.format(inetAddress)));
            }
        }
    }

    public void testRunAsGranted() throws Exception {
        for (Level level : Level.values()) {
            threadContext = new ThreadContext(Settings.EMPTY);
            Logger logger = CapturingLogger.newCapturingLogger(level);
            LoggingAuditTrail auditTrail = new LoggingAuditTrail(settings, clusterService, logger, threadContext);
            TransportMessage message = new MockMessage(threadContext);
            String origins = LoggingAuditTrail.originAttributes(message, localNode, threadContext);
            User user = new User("_username", new String[]{"r1"}, new User("running as", new String[] {"r2"}));
            auditTrail.runAsGranted(user, "_action", message);
            switch (level.toString()) {
                case "ERROR":
                case "WARN":
                    assertEmptyLog(logger);
                    break;
                case "INFO":
                        assertMsg(logger, Level.INFO, prefix + "[transport] [run_as_granted]\t" + origins +
                                ", principal=[_username], run_as_principal=[running as], action=[_action]");
                    break;
                case "DEBUG":
                case "TRACE":
                        assertMsg(logger, Level.DEBUG, prefix + "[transport] [run_as_granted]\t" + origins +
                                ", principal=[_username], run_as_principal=[running as], action=[_action], request=[MockMessage]");
            }
        }
    }

    public void testRunAsDenied() throws Exception {
        for (Level level : Level.values()) {
            threadContext = new ThreadContext(Settings.EMPTY);
            Logger logger = CapturingLogger.newCapturingLogger(level);
            LoggingAuditTrail auditTrail = new LoggingAuditTrail(settings, clusterService, logger, threadContext);
            TransportMessage message = new MockMessage(threadContext);
            String origins = LoggingAuditTrail.originAttributes(message, localNode, threadContext);
            User user = new User("_username", new String[]{"r1"}, new User("running as", new String[] {"r2"}));
            auditTrail.runAsDenied(user, "_action", message);
            switch (level.toString()) {
                case "ERROR":
                case "WARN":
                    assertEmptyLog(logger);
                    break;
                case "INFO":
                    assertMsg(logger, Level.INFO, prefix + "[transport] [run_as_denied]\t" + origins +
                            ", principal=[_username], run_as_principal=[running as], action=[_action]");
                    break;
                case "DEBUG":
                case "TRACE":
                    assertMsg(logger, Level.DEBUG, prefix + "[transport] [run_as_denied]\t" + origins +
                            ", principal=[_username], run_as_principal=[running as], action=[_action], request=[MockMessage]");
            }
        }
    }

    public void testOriginAttributes() throws Exception {
        threadContext = new ThreadContext(Settings.EMPTY);
        MockMessage message = new MockMessage(threadContext);
        String text = LoggingAuditTrail.originAttributes(message, localNode, threadContext);;
        InetSocketAddress restAddress = RemoteHostHeader.restRemoteAddress(threadContext);
        if (restAddress != null) {
            assertThat(text, equalTo("origin_type=[rest], origin_address=[" +
                    NetworkAddress.format(restAddress.getAddress()) + "]"));
            return;
        }
        TransportAddress address = message.remoteAddress();
        if (address == null) {
            assertThat(text, equalTo("origin_type=[local_node], origin_address=[" + localNode.getHostAddress() + "]"));
            return;
        }

        if (address instanceof InetSocketTransportAddress) {
            assertThat(text, equalTo("origin_type=[transport], origin_address=[" +
                    NetworkAddress.format(((InetSocketTransportAddress) address).address().getAddress()) + "]"));
        } else {
            assertThat(text, equalTo("origin_type=[transport], origin_address=[" + address + "]"));
        }
    }

    private void assertMsg(Logger logger, Level level, String message) {
        List<String> output = CapturingLogger.output(logger.getName(), level);
        assertThat(output.size(), is(1));
        assertThat(output.get(0), equalTo(message));
    }

    private void assertEmptyLog(Logger logger) {
        assertThat(CapturingLogger.isEmpty(logger.getName()), is(true));
    }

    private String prepareRestContent(RestRequest mock) {
        RestContent content = randomFrom(RestContent.values());
        when(mock.hasContent()).thenReturn(content.hasContent());
        if (content.hasContent()) {
            when(mock.content()).thenReturn(content.content());
        }
        return content.expectedMessage();
    }

    /** creates address without any lookups. hostname can be null, for missing */
    private static InetAddress forge(String hostname, String address) throws IOException {
        byte bytes[] = InetAddress.getByName(address).getAddress();
        return InetAddress.getByAddress(hostname, bytes);
    }

    private static String indices(TransportMessage message) {
        return Strings.collectionToCommaDelimitedString(AuditUtil.indices(message));
    }

    private static class MockMessage extends TransportMessage {

        private MockMessage(ThreadContext threadContext) throws IOException {
            if (randomBoolean()) {
                if (randomBoolean()) {
                    remoteAddress(new LocalTransportAddress("local_host"));
                } else {
                    remoteAddress(new InetSocketTransportAddress(InetAddress.getLoopbackAddress(), 1234));
                }
            }
            if (randomBoolean()) {
                RemoteHostHeader.putRestRemoteAddress(threadContext, new InetSocketAddress(forge("localhost", "127.0.0.1"), 1234));
            }
        }
    }

    private static class MockIndicesRequest extends TransportMessage implements IndicesRequest {

        private MockIndicesRequest(ThreadContext threadContext) throws IOException {
            if (randomBoolean()) {
                remoteAddress(new LocalTransportAddress("_host"));
            }
            if (randomBoolean()) {
                RemoteHostHeader.putRestRemoteAddress(threadContext, new InetSocketAddress(forge("localhost", "127.0.0.1"), 1234));
            }
        }

        @Override
        public String[] indices() {
            return new String[] { "idx1", "idx2" };
        }

        @Override
        public IndicesOptions indicesOptions() {
            return IndicesOptions.strictExpandOpenAndForbidClosed();
        }

        @Override
        public String toString() {
            return "mock-message";
        }
    }

    private static class MockToken implements AuthenticationToken {
        @Override
        public String principal() {
            return "_principal";
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

}
