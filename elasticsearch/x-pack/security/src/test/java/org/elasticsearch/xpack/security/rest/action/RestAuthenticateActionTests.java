/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.rest.action;

import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.xpack.security.authc.support.SecuredString;
import org.elasticsearch.xpack.security.authz.InternalAuthorizationService;
import org.elasticsearch.xpack.security.user.AnonymousUser;
import org.elasticsearch.test.SecurityIntegTestCase;
import org.elasticsearch.test.SecuritySettingsSource;
import org.elasticsearch.test.rest.ObjectPath;
import org.junit.BeforeClass;

import java.util.Collections;
import java.util.List;

import static org.elasticsearch.xpack.security.authc.support.UsernamePasswordToken.basicAuthHeaderValue;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class RestAuthenticateActionTests extends SecurityIntegTestCase {

    private static boolean anonymousEnabled;

    @BeforeClass
    public static void maybeEnableAnonymous() {
        anonymousEnabled = randomBoolean();
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        Settings.Builder builder = Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put(NetworkModule.HTTP_ENABLED.getKey(), true);

        if (anonymousEnabled) {
            builder.put(AnonymousUser.USERNAME_SETTING.getKey(), "anon")
                   .putArray(AnonymousUser.ROLES_SETTING.getKey(), SecuritySettingsSource.DEFAULT_ROLE, "foo")
                   .put(InternalAuthorizationService.ANONYMOUS_AUTHORIZATION_EXCEPTION_SETTING.getKey(), false);
        }
        return builder.build();
    }

    public void testAuthenticateApi() throws Exception {
        try (Response response = getRestClient().performRequest(
                "GET", "/_xpack/security/_authenticate", Collections.emptyMap(), null,
                new BasicHeader("Authorization", basicAuthHeaderValue(SecuritySettingsSource.DEFAULT_USER_NAME,
                        new SecuredString(SecuritySettingsSource.DEFAULT_PASSWORD.toCharArray()))))) {
            assertThat(response.getStatusLine().getStatusCode(), is(200));
            ObjectPath objectPath = ObjectPath.createFromXContent(XContentFactory.xContent(XContentType.JSON),
                    EntityUtils.toString(response.getEntity()));
            assertThat(objectPath.evaluate("username").toString(), equalTo(SecuritySettingsSource.DEFAULT_USER_NAME));
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) objectPath.evaluate("roles");
            assertThat(roles.size(), is(1));
            assertThat(roles, contains(SecuritySettingsSource.DEFAULT_ROLE));
        }
    }

    public void testAuthenticateApiWithoutAuthentication() throws Exception {
        try (Response response = getRestClient().performRequest("GET", "/_xpack/security/_authenticate",
                Collections.emptyMap(), null)) {
            if (anonymousEnabled) {
                assertThat(response.getStatusLine().getStatusCode(), is(200));
                ObjectPath objectPath = ObjectPath.createFromXContent(XContentFactory.xContent(XContentType.JSON),
                        EntityUtils.toString(response.getEntity()));
                assertThat(objectPath.evaluate("username").toString(), equalTo("anon"));
                @SuppressWarnings("unchecked")
                List<String> roles = (List<String>) objectPath.evaluate("roles");
                assertThat(roles.size(), is(2));
                assertThat(roles, contains(SecuritySettingsSource.DEFAULT_ROLE, "foo"));
            } else {
                fail("request should have failed");
            }
        } catch(ResponseException e) {
            if (anonymousEnabled) {
                fail("request should have succeeded");
            } else {
                assertThat(e.getResponse().getStatusLine().getStatusCode(), is(401));
            }
        }
    }
}
