/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.example.realm;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.xpack.security.authc.support.CharArrays;
import org.elasticsearch.xpack.security.user.User;
import org.elasticsearch.xpack.security.authc.AuthenticationToken;
import org.elasticsearch.xpack.security.authc.Realm;
import org.elasticsearch.xpack.security.authc.RealmConfig;
import org.elasticsearch.xpack.security.authc.support.UsernamePasswordToken;

public class CustomRealm extends Realm {

    public static final String TYPE = "custom";

    public static final String USER_HEADER = "User";
    public static final String PW_HEADER = "Password";

    public static final String KNOWN_USER = "custom_user";
    public static final SecureString KNOWN_PW = new SecureString("changeme".toCharArray());
    static final String[] ROLES = new String[] { "superuser" };

    public CustomRealm(RealmConfig config) {
        super(TYPE, config);
    }

    @Override
    public boolean supports(AuthenticationToken token) {
        return token instanceof UsernamePasswordToken;
    }

    @Override
    public UsernamePasswordToken token(ThreadContext threadContext) {
        String user = threadContext.getHeader(USER_HEADER);
        if (user != null) {
            String password = threadContext.getHeader(PW_HEADER);
            if (password != null) {
                return new UsernamePasswordToken(user, new SecureString(password.toCharArray()));
            }
        }
        return null;
    }

    @Override
    public void authenticate(AuthenticationToken authToken, ActionListener<User> listener) {
        UsernamePasswordToken token = (UsernamePasswordToken)authToken;
        final String actualUser = token.principal();
        if (KNOWN_USER.equals(actualUser) && CharArrays.constantTimeEquals(token.credentials().getChars(), KNOWN_PW.getChars())) {
            listener.onResponse(new User(actualUser, ROLES));
        } else {
            listener.onResponse(null);
        }
    }

    @Override
    public void lookupUser(String username, ActionListener<User> listener) {
        listener.onResponse(null);
    }
}
