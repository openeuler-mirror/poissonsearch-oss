/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc.ldap.support;

import com.unboundid.ldap.sdk.*;

import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.shield.authc.ldap.ShieldLdapException;

import javax.naming.ldap.Rdn;

import java.text.MessageFormat;
import java.util.Locale;

public final class LdapUtils {

    public static final Filter OBJECT_CLASS_PRESENCE_FILTER = Filter.createPresenceFilter("objectClass");

    private LdapUtils() {
    }

    public static DN dn(String dn) {
        try {
            return new DN(dn);
        } catch (LDAPException e) {
            throw new ShieldLdapException("invalid DN [" + dn + "]", e);
        }
    }

    public static String relativeName(DN dn) {
        return dn.getRDNString().split("=")[1].trim();
    }

    public static String escapedRDNValue(String rdn) {
        // We can't use UnboundID RDN here because it expects attribute=value, not just value
        return Rdn.escapeValue(rdn);
    }

    /**
     * This method performs a LDAPConnection.search(...) operation while handling referral exceptions. This is necessary
     * to maintain backwards compatibility
     * @param ldap
     * @param searchRequest
     * @param logger
     * @return
     * @throws LDAPException
     */
    public static SearchResult search(LDAPInterface ldap, SearchRequest searchRequest, ESLogger logger) throws LDAPException {
        SearchResult results;
        try {
            results = ldap.search(searchRequest);
        } catch (LDAPSearchException e) {
            if (e.getResultCode().equals(ResultCode.REFERRAL) && e.getSearchResult() != null) {
                if (logger.isDebugEnabled()){
                    logger.debug("a referral could not be followed for request [{}] so some results may not have been retrieved", e, searchRequest);
                }
                results = e.getSearchResult();
            } else {
                throw e;
            }
        }
        return results;
    }

    /**
     * This method performs a LDAPConnection.searchForEntry(...) operation while handling referral exceptions. This is necessary
     * to maintain backwards compatibility
     * @param ldap
     * @param searchRequest
     * @param logger
     * @return
     * @throws LDAPException
     */
    public static SearchResultEntry searchForEntry(LDAPInterface ldap, SearchRequest searchRequest, ESLogger logger) throws LDAPException {
        SearchResultEntry entry;
        try {
            entry = ldap.searchForEntry(searchRequest);
        } catch (LDAPSearchException e) {
            if (e.getResultCode().equals(ResultCode.REFERRAL) && e.getSearchResult() != null && e.getSearchResult().getEntryCount() > 0) {
                if (logger.isDebugEnabled()){
                    logger.debug("a referral could not be followed for request [{}] so some results may not have been retrieved", e, searchRequest);
                }
                entry = e.getSearchResult().getSearchEntries().get(0);
            } else {
                throw e;
            }
        }
        return entry;
    }

    public static Filter createFilter(String filterTemplate, String... arguments) throws LDAPException {
        return Filter.create(new MessageFormat(filterTemplate, Locale.ROOT).format((Object[]) encodeFilterValues(arguments), new StringBuffer(), null).toString());
    }

    static String[] encodeFilterValues(String... arguments) {
        for(int i = 0; i < arguments.length; i++) {
            arguments[i] = Filter.encodeValue(arguments[i]);
        }
        return arguments;
    }
}
