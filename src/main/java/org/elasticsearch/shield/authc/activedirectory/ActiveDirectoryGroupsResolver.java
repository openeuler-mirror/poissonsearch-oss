/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc.activedirectory;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import com.unboundid.ldap.sdk.*;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.shield.authc.AuthenticationException;
import org.elasticsearch.shield.authc.ldap.support.LdapSearchScope;
import org.elasticsearch.shield.authc.ldap.support.LdapSession.GroupsResolver;

import java.util.ArrayList;
import java.util.List;

import static org.elasticsearch.shield.authc.ldap.support.LdapUtils.*;

/**
 *
 */
public class ActiveDirectoryGroupsResolver implements GroupsResolver {

    private final String baseDn;
    private final LdapSearchScope scope;

    public ActiveDirectoryGroupsResolver(Settings settings, String baseDnDefault) {
        this.baseDn = settings.get("base_dn", baseDnDefault);
        this.scope = LdapSearchScope.resolve(settings.get("scope"), LdapSearchScope.SUB_TREE);
    }

    public List<String> resolve(LDAPInterface connection, String userDn, TimeValue timeout, ESLogger logger) {
        Filter groupSearchFilter = buildGroupQuery(connection, userDn, timeout, logger);
        logger.debug("group SID to DN search filter: [{}]", groupSearchFilter);

        SearchRequest searchRequest = new SearchRequest(baseDn, scope.scope(), groupSearchFilter, Strings.EMPTY_ARRAY);
        searchRequest.setTimeLimitSeconds(Ints.checkedCast(timeout.seconds()));
        SearchResult results;
        try {
            results = search(connection, searchRequest, logger);
        } catch (LDAPException e) {
            // TODO parameterize
            throw new AuthenticationException("failed to fetch AD groups for DN [" + userDn + "]", e);
        }

        ImmutableList.Builder<String> groups = ImmutableList.builder();
        for (SearchResultEntry entry : results.getSearchEntries()) {
            groups.add(entry.getDN());
        }
        List<String> groupList = groups.build();
        if (logger.isDebugEnabled()) {
            logger.debug("found these groups [{}] for userDN [{}]", groupList, userDn);
        }
        return groupList;
    }

    static Filter buildGroupQuery(LDAPInterface connection, String userDn, TimeValue timeout, ESLogger logger) {
        try {
            SearchRequest request = new SearchRequest(userDn, SearchScope.BASE, OBJECT_CLASS_PRESENCE_FILTER, "tokenGroups");
            request.setTimeLimitSeconds(Ints.checkedCast(timeout.seconds()));
            SearchResultEntry entry = searchForEntry(connection, request, logger);
            Attribute attribute = entry.getAttribute("tokenGroups");
            byte[][] tokenGroupSIDBytes = attribute.getValueByteArrays();
            List<Filter> orFilters = new ArrayList<>(tokenGroupSIDBytes.length);
            for (byte[] SID : tokenGroupSIDBytes) {
                orFilters.add(Filter.createEqualityFilter("objectSid", binarySidToStringSid(SID)));
            }
            return Filter.createORFilter(orFilters);
        } catch (LDAPException e) {
            throw new AuthenticationException("failed to fetch AD groups for DN [" + userDn + "]", e);
        }
    }

    /**
     * To better understand what the sid is and how its string representation looks like, see
     * http://blogs.msdn.com/b/alextch/archive/2007/06/18/sample-java-application-that-retrieves-group-membership-of-an-active-directory-user-account.aspx
     *
     * @param SID byte encoded security ID
     */
    static public String binarySidToStringSid(byte[] SID) {
        String strSID;

        //convert the SID into string format

        long version;
        long authority;
        long count;
        long rid;

        strSID = "S";
        version = SID[0];
        strSID = strSID + "-" + Long.toString(version);
        authority = SID[4];

        for (int i = 0; i < 4; i++) {
            authority <<= 8;
            authority += SID[4 + i] & 0xFF;
        }

        strSID = strSID + "-" + Long.toString(authority);
        count = SID[2];
        count <<= 8;
        count += SID[1] & 0xFF;
        for (int j = 0; j < count; j++) {
            rid = SID[11 + (j * 4)] & 0xFF;
            for (int k = 1; k < 4; k++) {
                rid <<= 8;
                rid += SID[11 - k + (j * 4)] & 0xFF;
            }
            strSID = strSID + "-" + Long.toString(rid);
        }
        return strSID;
    }

}
