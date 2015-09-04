/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.audit.logfile;

import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.network.NetworkAddress;
import org.elasticsearch.common.network.NetworkUtils;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.shield.User;
import org.elasticsearch.shield.audit.AuditTrail;
import org.elasticsearch.shield.authc.AuthenticationToken;
import org.elasticsearch.shield.authz.Privilege;
import org.elasticsearch.shield.rest.RemoteHostHeader;
import org.elasticsearch.shield.transport.filter.ShieldIpFilterRule;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.transport.TransportMessage;
import org.elasticsearch.transport.TransportRequest;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static org.elasticsearch.common.Strings.arrayToCommaDelimitedString;
import static org.elasticsearch.shield.audit.AuditUtil.indices;
import static org.elasticsearch.shield.audit.AuditUtil.restRequestContent;

/**
 *
 */
public class LoggingAuditTrail implements AuditTrail {

    public static final String NAME = "logfile";

    private final String prefix;
    private final ESLogger logger;
    private final Transport transport;

    @Override
    public String name() {
        return NAME;
    }

    @Inject
    public LoggingAuditTrail(Settings settings, Transport transport) {
        this(resolvePrefix(settings, transport), transport, Loggers.getLogger(LoggingAuditTrail.class));
    }

    LoggingAuditTrail(Settings settings, Transport transport, ESLogger logger) {
        this(resolvePrefix(settings, transport), transport, logger);
    }

    LoggingAuditTrail(String prefix, Transport transport, ESLogger logger) {
        this.logger = logger;
        this.prefix = prefix;
        this.transport = transport;
    }

    @Override
    public void anonymousAccessDenied(String action, TransportMessage<?> message) {
        String indices = indicesString(message);
        if (indices != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("{}[transport] [anonymous_access_denied]\t{}, action=[{}], indices=[{}], request=[{}]", prefix, originAttributes(message, transport), action, indices, message.getClass().getSimpleName());
            } else {
                logger.warn("{}[transport] [anonymous_access_denied]\t{}, action=[{}], indices=[{}]", prefix, originAttributes(message, transport), action, indices);
            }
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("{}[transport] [anonymous_access_denied]\t{}, action=[{}], request=[{}]", prefix, originAttributes(message, transport), action, message.getClass().getSimpleName());
            } else {
                logger.warn("{}[transport] [anonymous_access_denied]\t{}, action=[{}]", prefix, originAttributes(message, transport), action);
            }
        }
    }

    @Override
    public void anonymousAccessDenied(RestRequest request) {
        if (logger.isDebugEnabled()) {
            logger.debug("{}[rest] [anonymous_access_denied]\t{}, uri=[{}], request_body=[{}]", prefix, hostAttributes(request), request.uri(), restRequestContent(request));
        } else {
            logger.warn("{}[rest] [anonymous_access_denied]\t{}, uri=[{}]", prefix, hostAttributes(request), request.uri());
        }
    }

    @Override
    public void authenticationFailed(AuthenticationToken token, String action, TransportMessage<?> message) {
        String indices = indicesString(message);
        if (indices != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("{}[transport] [authentication_failed]\t{}, principal=[{}], action=[{}], indices=[{}], request=[{}]", prefix, originAttributes(message, transport), token.principal(), action, indices, message.getClass().getSimpleName());
            } else {
                logger.error("{}[transport] [authentication_failed]\t{}, principal=[{}], action=[{}], indices=[{}]", prefix, originAttributes(message, transport), token.principal(), action, indices);
            }
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("{}[transport] [authentication_failed]\t{}, principal=[{}], action=[{}], request=[{}]", prefix, originAttributes(message, transport), token.principal(), action, message.getClass().getSimpleName());
            } else {
                logger.error("{}[transport] [authentication_failed]\t{}, principal=[{}], action=[{}]", prefix, originAttributes(message, transport), token.principal(), action);
            }
        }
    }

    @Override
    public void authenticationFailed(RestRequest request) {
        if (logger.isDebugEnabled()) {
            logger.debug("{}[rest] [authentication_failed]\t{}, uri=[{}], request_body=[{}]", prefix, hostAttributes(request), request.uri(), restRequestContent(request));
        } else {
            logger.error("{}[rest] [authentication_failed]\t{}, uri=[{}]", prefix, hostAttributes(request), request.uri());
        }
    }

    @Override
    public void authenticationFailed(String action, TransportMessage<?> message) {
        String indices = indicesString(message);
        if (indices != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("{}[transport] [authentication_failed]\t{}, action=[{}], indices=[{}], request=[{}]", prefix, originAttributes(message, transport), action, indices, message.getClass().getSimpleName());
            } else {
                logger.error("{}[transport] [authentication_failed]\t{}, action=[{}], indices=[{}]", prefix, originAttributes(message, transport), action, indices);
            }
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("{}[transport] [authentication_failed]\t{}, action=[{}], request=[{}]", prefix, originAttributes(message, transport), action, message.getClass().getSimpleName());
            } else {
                logger.error("{}[transport] [authentication_failed]\t{}, action=[{}]", prefix, originAttributes(message, transport), action);
            }
        }
    }

    @Override
    public void authenticationFailed(AuthenticationToken token, RestRequest request) {
        if (logger.isDebugEnabled()) {
            logger.debug("{}[rest] [authentication_failed]\t{}, principal=[{}], uri=[{}], request_body=[{}]", prefix, hostAttributes(request), token.principal(), request.uri(), restRequestContent(request));
        } else {
            logger.error("{}[rest] [authentication_failed]\t{}, principal=[{}], uri=[{}]", prefix, hostAttributes(request), token.principal(), request.uri());
        }
    }

    @Override
    public void authenticationFailed(String realm, AuthenticationToken token, String action, TransportMessage<?> message) {
        if (logger.isTraceEnabled()) {
            String indices = indicesString(message);
            if (indices != null) {
                logger.trace("{}[transport] [authentication_failed]\trealm=[{}], {}, principal=[{}], action=[{}], indices=[{}], request=[{}]", prefix, realm, originAttributes(message, transport), token.principal(), action, indices, message.getClass().getSimpleName());
            } else {
                logger.trace("{}[transport] [authentication_failed]\trealm=[{}], {}, principal=[{}], action=[{}], request=[{}]", prefix, realm, originAttributes(message, transport), token.principal(), action, message.getClass().getSimpleName());
            }
        }
    }

    @Override
    public void authenticationFailed(String realm, AuthenticationToken token, RestRequest request) {
        if (logger.isTraceEnabled()) {
            logger.trace("{}[rest] [authentication_failed]\trealm=[{}], {}, principal=[{}], uri=[{}], request_body=[{}]", prefix, realm, hostAttributes(request), token.principal(), request.uri(), restRequestContent(request));
        }
    }

    @Override
    public void accessGranted(User user, String action, TransportMessage<?> message) {
        String indices = indicesString(message);

        // special treatment for internal system actions - only log on trace
        if (user.isSystem() && Privilege.SYSTEM.predicate().test(action)) {
            if (logger.isTraceEnabled()) {
                if (indices != null) {
                    logger.trace("{}[transport] [access_granted]\t{}, principal=[{}], action=[{}], indices=[{}], request=[{}]", prefix, originAttributes(message, transport), user.principal(), action, indices, message.getClass().getSimpleName());
                } else {
                    logger.trace("{}[transport] [access_granted]\t{}, principal=[{}], action=[{}], request=[{}]", prefix, originAttributes(message, transport), user.principal(), action, message.getClass().getSimpleName());
                }
            }
            return;
        }

        if (indices != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("{}[transport] [access_granted]\t{}, principal=[{}], action=[{}], indices=[{}], request=[{}]", prefix, originAttributes(message, transport), user.principal(), action, indices, message.getClass().getSimpleName());
            } else {
                logger.info("{}[transport] [access_granted]\t{}, principal=[{}], action=[{}], indices=[{}]", prefix, originAttributes(message, transport), user.principal(), action, indices);
            }
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("{}[transport] [access_granted]\t{}, principal=[{}], action=[{}], request=[{}]", prefix, originAttributes(message, transport), user.principal(), action, message.getClass().getSimpleName());
            } else {
                logger.info("{}[transport] [access_granted]\t{}, principal=[{}], action=[{}]", prefix, originAttributes(message, transport), user.principal(), action);
            }
        }
    }

    @Override
    public void accessDenied(User user, String action, TransportMessage<?> message) {
        String indices = indicesString(message);
        if (indices != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("{}[transport] [access_denied]\t{}, principal=[{}], action=[{}], indices=[{}], request=[{}]", prefix, originAttributes(message, transport), user.principal(), action, indices, message.getClass().getSimpleName());
            } else {
                logger.error("{}[transport] [access_denied]\t{}, principal=[{}], action=[{}], indices=[{}]", prefix, originAttributes(message, transport), user.principal(), action, indices);
            }
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("{}[transport] [access_denied]\t{}, principal=[{}], action=[{}], request=[{}]", prefix, originAttributes(message, transport), user.principal(), action, message.getClass().getSimpleName());
            } else {
                logger.error("{}[transport] [access_denied]\t{}, principal=[{}], action=[{}]", prefix, originAttributes(message, transport), user.principal(), action);
            }
        }
    }

    @Override
    public void tamperedRequest(User user, String action, TransportRequest request) {
        String indices = indicesString(request);
        if (indices != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("{}[transport] [tampered_request]\t{}, principal=[{}], action=[{}], indices=[{}], request=[{}]", prefix, request.remoteAddress(), user.principal(), action, indices, request.getClass().getSimpleName());
            } else {
                logger.error("{}[transport] [tampered_request]\t{}, principal=[{}], action=[{}], indices=[{}]", prefix, request.remoteAddress(), user.principal(), action, indices);
            }
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("{}[transport] [tampered_request]\t{}, principal=[{}], action=[{}], request=[{}]", prefix, request.remoteAddress(), user.principal(), action, request.getClass().getSimpleName());
            } else {
                logger.error("{}[transport] [tampered_request]\t{}, principal=[{}], action=[{}]", prefix, request.remoteAddress(), user.principal(), action);
            }
        }
    }

    @Override
    public void connectionGranted(InetAddress inetAddress, String profile, ShieldIpFilterRule rule) {
        if (logger.isTraceEnabled()) {
            logger.trace("{}[ip_filter] [connection_granted]\torigin_address=[{}], transport_profile=[{}], rule=[{}]", prefix, NetworkAddress.formatAddress(inetAddress), profile, rule);
        }
    }

    @Override
    public void connectionDenied(InetAddress inetAddress, String profile, ShieldIpFilterRule rule) {
        logger.error("{}[ip_filter] [connection_denied]\torigin_address=[{}], transport_profile=[{}], rule=[{}]", prefix, NetworkAddress.formatAddress(inetAddress), profile, rule);
    }

    private static String hostAttributes(RestRequest request) {
        String formattedAddress;
        SocketAddress socketAddress = request.getRemoteAddress();
        if (socketAddress instanceof InetSocketAddress) {
            formattedAddress = NetworkAddress.formatAddress(((InetSocketAddress) socketAddress).getAddress());
        } else {
            formattedAddress = socketAddress.toString();
        }
        return "origin_address=[" + formattedAddress + "]";
    }

    static String originAttributes(TransportMessage message, Transport transport) {
        StringBuilder builder = new StringBuilder();

        // first checking if the message originated in a rest call
        InetSocketAddress restAddress = RemoteHostHeader.restRemoteAddress(message);
        if (restAddress != null) {
            builder.append("origin_type=[rest], origin_address=[").append(NetworkAddress.formatAddress(restAddress.getAddress())).append("]");
            return builder.toString();
        }

        // we'll see if was originated in a remote node
        TransportAddress address = message.remoteAddress();
        if (address != null) {
            builder.append("origin_type=[transport], ");
            if (address instanceof InetSocketTransportAddress) {
                builder.append("origin_address=[").append(NetworkAddress.formatAddress(((InetSocketTransportAddress) address).address().getAddress())).append("]");
            } else {
                builder.append("origin_address=[").append(address).append("]");
            }
            return builder.toString();
        }

        // the call was originated locally on this node
        return builder.append("origin_type=[local_node], origin_address=[")
                .append(transport.boundAddress().publishAddress().getAddress())
                .append("]")
                .toString();
    }

    static String resolvePrefix(Settings settings, Transport transport) {
        StringBuilder builder = new StringBuilder();
        if (settings.getAsBoolean("shield.audit.logfile.prefix.emit_node_host_address", false)) {
            String address = transport.boundAddress().publishAddress().getAddress();
            if (address != null) {
                builder.append("[").append(address).append("] ");
            }
        }
        if (settings.getAsBoolean("shield.audit.logfile.prefix.emit_node_host_name", false)) {
            String hostName = transport.boundAddress().publishAddress().getHost();
            if (hostName != null) {
                builder.append("[").append(hostName).append("] ");
            }
        }
        if (settings.getAsBoolean("shield.audit.logfile.prefix.emit_node_name", true)) {
            String name = settings.get("name");
            if (name != null) {
                builder.append("[").append(name).append("] ");
            }
        }
        return builder.toString();
    }

    static String indicesString(TransportMessage<?> message) {
        String[] indices = indices(message);
        return indices == null ? null : arrayToCommaDelimitedString(indices);
    }
}
