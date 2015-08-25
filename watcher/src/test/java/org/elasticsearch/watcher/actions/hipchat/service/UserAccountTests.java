/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.actions.hipchat.service;

import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsException;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.watcher.support.http.*;
import org.junit.Test;

import java.io.IOException;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;

/**
 *
 */
public class UserAccountTests extends ESTestCase {

    @Test
    public void testSettings() throws Exception {
        String accountName = "_name";

        Settings.Builder sb = Settings.builder();

        String authToken = randomAsciiOfLength(50);
        sb.put(UserAccount.AUTH_TOKEN_SETTING, authToken);

        String host = HipChatServer.DEFAULT.host();
        if (randomBoolean()) {
            host = randomAsciiOfLength(10);
            sb.put(HipChatServer.HOST_SETTING, host);
        }
        int port = HipChatServer.DEFAULT.port();
        if (randomBoolean()) {
            port = randomIntBetween(300, 400);
            sb.put(HipChatServer.PORT_SETTING, port);
        }

        String[] defaultRooms = null;
        if (randomBoolean()) {
            defaultRooms = new String[] { "_r1", "_r2" };
            sb.put(HipChatAccount.DEFAULT_ROOM_SETTING, "_r1,_r2");
        }
        String[] defaultUsers = null;
        if (randomBoolean()) {
            defaultUsers = new String[] { "_u1", "_u2" };
            sb.put(HipChatAccount.DEFAULT_USER_SETTING, "_u1,_u2");
        }
        HipChatMessage.Format defaultFormat = null;
        if (randomBoolean()) {
            defaultFormat = randomFrom(HipChatMessage.Format.values());
            sb.put(HipChatAccount.DEFAULT_FORMAT_SETTING, defaultFormat);
        }
        HipChatMessage.Color defaultColor = null;
        if (randomBoolean()) {
            defaultColor = randomFrom(HipChatMessage.Color.values());
            sb.put(HipChatAccount.DEFAULT_COLOR_SETTING, defaultColor);
        }
        Boolean defaultNotify = null;
        if (randomBoolean()) {
            defaultNotify = randomBoolean();
            sb.put(HipChatAccount.DEFAULT_NOTIFY_SETTING, defaultNotify);
        }
        Settings settings = sb.build();

        UserAccount account = new UserAccount(accountName, settings, HipChatServer.DEFAULT, mock(HttpClient.class), mock(ESLogger.class));

        assertThat(account.profile, is(HipChatAccount.Profile.USER));
        assertThat(account.name, equalTo(accountName));
        assertThat(account.server.host(), is(host));
        assertThat(account.server.port(), is(port));
        assertThat(account.authToken, is(authToken));
        if (defaultRooms != null) {
            assertThat(account.defaults.rooms, arrayContaining(defaultRooms));
        } else {
            assertThat(account.defaults.rooms, nullValue());
        }
        if (defaultUsers != null) {
            assertThat(account.defaults.users, arrayContaining(defaultUsers));
        } else {
            assertThat(account.defaults.users, nullValue());
        }
        assertThat(account.defaults.format, is(defaultFormat));
        assertThat(account.defaults.color, is(defaultColor));
        assertThat(account.defaults.notify, is(defaultNotify));
    }

    @Test(expected = SettingsException.class)
    public void testSettings_NoAuthToken() throws Exception {
        Settings.Builder sb = Settings.builder();
        new UserAccount("_name", sb.build(), HipChatServer.DEFAULT, mock(HttpClient.class), mock(ESLogger.class));
    }

    @Test
    public void testSend() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        UserAccount account = new UserAccount("_name", Settings.builder()
                .put("host", "_host")
                .put("port", "443")
                .put("auth_token", "_token")
                .build(), HipChatServer.DEFAULT, httpClient, mock(ESLogger.class));

        HipChatMessage.Format format = randomFrom(HipChatMessage.Format.values());
        HipChatMessage.Color color = randomFrom(HipChatMessage.Color.values());
        Boolean notify = randomBoolean();
        final HipChatMessage message = new HipChatMessage("_body", new String[] { "_r1", "_r2" }, new String[] { "_u1", "_u2" }, null, format, color, notify);

        HttpRequest reqR1 = HttpRequest.builder("_host", 443)
                .method(HttpMethod.POST)
                .scheme(Scheme.HTTPS)
                .path("/v2/room/_r1/notification")
                .setHeader("Content-Type", "application/json")
                .setHeader("Authorization", "Bearer _token")
                .body(XContentHelper.toString(new ToXContent() {
                    @Override
                    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
                        builder.field("message", message.body);
                        if (message.format != null) {
                            builder.field("message_format", message.format.value());
                        }
                        if (message.notify != null) {
                            builder.field("notify", message.notify);
                        }
                        if (message.color != null) {
                            builder.field("color", String.valueOf(message.color.value()));
                        }
                        return builder;
                    }
                }))
                .build();

        logger.info("expected (r1): " + jsonBuilder().value(reqR1).bytes().toUtf8());

        HttpResponse resR1 = mock(HttpResponse.class);
        when(resR1.status()).thenReturn(200);
        when(httpClient.execute(reqR1)).thenReturn(resR1);

        HttpRequest reqR2 = HttpRequest.builder("_host", 443)
                .method(HttpMethod.POST)
                .scheme(Scheme.HTTPS)
                .path("/v2/room/_r2/notification")
                .setHeader("Content-Type", "application/json")
                .setHeader("Authorization", "Bearer _token")
                .body(XContentHelper.toString(new ToXContent() {
                    @Override
                    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
                        builder.field("message", message.body);
                        if (message.format != null) {
                            builder.field("message_format", message.format.value());
                        }
                        if (message.notify != null) {
                            builder.field("notify", message.notify);
                        }
                        if (message.color != null) {
                            builder.field("color", String.valueOf(message.color.value()));
                        }
                        return builder;
                    }
                }))
                .build();

        logger.info("expected (r2): " + jsonBuilder().value(reqR1).bytes().toUtf8());

        HttpResponse resR2 = mock(HttpResponse.class);
        when(resR2.status()).thenReturn(200);
        when(httpClient.execute(reqR2)).thenReturn(resR2);

        HttpRequest reqU1 = HttpRequest.builder("_host", 443)
                .method(HttpMethod.POST)
                .scheme(Scheme.HTTPS)
                .path("/v2/user/_u1/message")
                .setHeader("Content-Type", "application/json")
                .setHeader("Authorization", "Bearer _token")
                .body(XContentHelper.toString(new ToXContent() {
                    @Override
                    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
                        builder.field("message", message.body);
                        if (message.format != null) {
                            builder.field("message_format", message.format.value());
                        }
                        if (message.notify != null) {
                            builder.field("notify", message.notify);
                        }
                        return builder;
                    }
                }))
                .build();

        logger.info("expected (u1): " + jsonBuilder().value(reqU1).bytes().toUtf8());

        HttpResponse resU1 = mock(HttpResponse.class);
        when(resU1.status()).thenReturn(200);
        when(httpClient.execute(reqU1)).thenReturn(resU1);

        HttpRequest reqU2 = HttpRequest.builder("_host", 443)
                .method(HttpMethod.POST)
                .scheme(Scheme.HTTPS)
                .path("/v2/user/_u2/message")
                .setHeader("Content-Type", "application/json")
                .setHeader("Authorization", "Bearer _token")
                .body(XContentHelper.toString(new ToXContent() {
                    @Override
                    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
                        builder.field("message", message.body);
                        if (message.format != null) {
                            builder.field("message_format", message.format.value());
                        }
                        if (message.notify != null) {
                            builder.field("notify", message.notify);
                        }
                        return builder;
                    }
                }))
                .build();

        logger.info("expected (u2): " + jsonBuilder().value(reqU2).bytes().toUtf8());

        HttpResponse resU2 = mock(HttpResponse.class);
        when(resU2.status()).thenReturn(200);
        when(httpClient.execute(reqU2)).thenReturn(resU2);

        account.send(message);

        verify(httpClient).execute(reqR1);
        verify(httpClient).execute(reqR2);
        verify(httpClient).execute(reqU2);
        verify(httpClient).execute(reqU2);
    }
}
