/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authc.ldap.support;

import com.unboundid.ldap.sdk.FailoverServerSet;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.RoundRobinDNSServerSet;
import com.unboundid.ldap.sdk.RoundRobinServerSet;
import com.unboundid.ldap.sdk.ServerSet;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.network.InetAddresses;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;

import javax.net.SocketFactory;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Enumeration representing the various supported {@link ServerSet} types that can be used with out built in realms.
 */
public enum LdapLoadBalancing {

    FAILOVER() {
        @Override
        ServerSet buildServerSet(String[] addresses, int[] ports, Settings settings, @Nullable SocketFactory socketFactory,
                                 @Nullable LDAPConnectionOptions options) {
            FailoverServerSet serverSet = new FailoverServerSet(addresses, ports, socketFactory, options);
            serverSet.setReOrderOnFailover(true);
            return serverSet;
        }
    },

    ROUND_ROBIN() {
        @Override
        ServerSet buildServerSet(String[] addresses, int[] ports, Settings settings, @Nullable SocketFactory socketFactory,
                                 @Nullable LDAPConnectionOptions options) {
            return new RoundRobinServerSet(addresses, ports, socketFactory, options);
        }
    },

    DNS_ROUND_ROBIN() {
        @Override
        ServerSet buildServerSet(String[] addresses, int[] ports, Settings settings, @Nullable SocketFactory socketFactory,
                                 @Nullable LDAPConnectionOptions options) {
            if (addresses.length != 1) {
                throw new IllegalArgumentException(toString() + " can only be used with a single url");
            }
            if (InetAddresses.isInetAddress(addresses[0])) {
                throw new IllegalArgumentException(toString() + " can only be used with a DNS name");
            }
            TimeValue dnsTtl = settings.getAsTime(CACHE_TTL_SETTING, CACHE_TTL_DEFAULT);
            return new RoundRobinDNSServerSet(addresses[0], ports[0],
                    RoundRobinDNSServerSet.AddressSelectionMode.ROUND_ROBIN, dnsTtl.millis(), null, socketFactory, options);
        }
    },

    DNS_FAILOVER() {
        @Override
        ServerSet buildServerSet(String[] addresses, int[] ports, Settings settings, @Nullable SocketFactory socketFactory,
                                 @Nullable LDAPConnectionOptions options) {
            if (addresses.length != 1) {
                throw new IllegalArgumentException(toString() + " can only be used with a single url");
            }
            if (InetAddresses.isInetAddress(addresses[0])) {
                throw new IllegalArgumentException(toString() + " can only be used with a DNS name");
            }
            TimeValue dnsTtl = settings.getAsTime(CACHE_TTL_SETTING, CACHE_TTL_DEFAULT);
            return new RoundRobinDNSServerSet(addresses[0], ports[0],
                    RoundRobinDNSServerSet.AddressSelectionMode.FAILOVER, dnsTtl.millis(), null, socketFactory, options);
        }
    };

    public static final String LOAD_BALANCE_SETTINGS = "load_balance";
    public static final String LOAD_BALANCE_TYPE_SETTING = "type";
    public static final String LOAD_BALANCE_TYPE_DEFAULT = LdapLoadBalancing.FAILOVER.toString();
    public static final String CACHE_TTL_SETTING = "cache_ttl";
    public static final TimeValue CACHE_TTL_DEFAULT = TimeValue.timeValueHours(1L);

    abstract ServerSet buildServerSet(String[] addresses, int[] ports, Settings settings, @Nullable SocketFactory socketFactory,
                                      @Nullable LDAPConnectionOptions options);

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static LdapLoadBalancing resolve(Settings settings) {
        Settings loadBalanceSettings = settings.getAsSettings(LOAD_BALANCE_SETTINGS);
        String type = loadBalanceSettings.get(LOAD_BALANCE_TYPE_SETTING, LOAD_BALANCE_TYPE_DEFAULT);
        try {
            return valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ilae) {
            throw new IllegalArgumentException("unknown load balance type [" + type + "]", ilae);
        }
    }

    public static ServerSet serverSet(String[] addresses, int[] ports, Settings settings, @Nullable SocketFactory socketFactory,
                                      @Nullable LDAPConnectionOptions options) {
        LdapLoadBalancing loadBalancing = resolve(settings);
        Settings loadBalanceSettings = settings.getAsSettings(LOAD_BALANCE_SETTINGS);
        return loadBalancing.buildServerSet(addresses, ports, loadBalanceSettings, socketFactory, options);
    }

    public static Set<Setting<?>> getSettings() {
        Set<Setting<?>> settings = new HashSet<>();
        settings.add(Setting.simpleString(LOAD_BALANCE_SETTINGS + "." + LOAD_BALANCE_TYPE_SETTING, Setting.Property.NodeScope));
        settings.add(Setting.simpleString(LOAD_BALANCE_SETTINGS + "." + CACHE_TTL_SETTING, Setting.Property.NodeScope));
        return settings;
    }
}
