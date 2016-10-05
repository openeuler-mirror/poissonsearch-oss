/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.notification.slack;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsException;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.xpack.common.http.HttpClient;
import org.elasticsearch.xpack.common.http.HttpProxy;
import org.elasticsearch.xpack.common.http.HttpRequest;
import org.elasticsearch.xpack.common.http.HttpResponse;
import org.elasticsearch.xpack.common.http.Scheme;
import org.elasticsearch.xpack.notification.slack.message.Attachment;
import org.elasticsearch.xpack.notification.slack.message.SlackMessage;
import org.elasticsearch.xpack.notification.slack.message.SlackMessageDefaults;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 *
 */
public class SlackAccount {

    public static final String URL_SETTING = "url";
    public static final String MESSAGE_DEFAULTS_SETTING = "message_defaults";

    final String name;
    final URI url;
    final HttpClient httpClient;
    final Logger logger;
    final SlackMessageDefaults messageDefaults;

    public SlackAccount(String name, Settings settings, Settings defaultSettings, HttpClient httpClient, Logger logger) {
        this.name = name;
        this.url = url(name, settings, defaultSettings);
        this.messageDefaults = new SlackMessageDefaults(settings.getAsSettings(MESSAGE_DEFAULTS_SETTING));
        this.httpClient = httpClient;
        this.logger = logger;
    }

    public SlackMessageDefaults getMessageDefaults() {
        return messageDefaults;
    }

    public SentMessages send(final SlackMessage message, HttpProxy proxy) {

        String[] to = message.getTo();
        if (to == null || to.length == 0) {
            SentMessages.SentMessage sentMessage = send(null, message, proxy);
            return new SentMessages(name, Collections.singletonList(sentMessage));
        }

        List<SentMessages.SentMessage> sentMessages = new ArrayList<>();
        for (String channel : to) {
            sentMessages.add(send(channel, message, proxy));
        }
        return new SentMessages(name, sentMessages);
    }

    public SentMessages.SentMessage send(final String to, final SlackMessage message, final HttpProxy proxy) {
        HttpRequest request = HttpRequest.builder(url.getHost(), url.getPort())
                .path(url.getPath())
                .proxy(proxy)
                .scheme(Scheme.parse(url.getScheme()))
                .jsonBody(new ToXContent() {
                    @Override
                    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
                        if (to != null) {
                            builder.field("channel", to);
                        }
                        if (message.getFrom() != null) {
                            builder.field("username", message.getFrom());
                        }
                        String icon = message.getIcon();
                        if (icon != null) {
                            if (icon.startsWith("http")) {
                                builder.field("icon_url", icon);
                            } else {
                                builder.field("icon_emoji", icon);
                            }
                        }
                        if (message.getText() != null) {
                            builder.field("text", message.getText());
                        }
                        Attachment[] attachments = message.getAttachments();
                        if (attachments != null && attachments.length > 0) {
                            builder.startArray("attachments");
                            for (Attachment attachment : attachments) {
                                attachment.toXContent(builder, params);
                            }
                            builder.endArray();

                        }
                        return builder;
                    }
                })
                .build();

        try {
            HttpResponse response = httpClient.execute(request);
            return SentMessages.SentMessage.responded(to, message, request, response);
        } catch (Exception e) {
            logger.error("failed to execute slack api http request", e);
            return SentMessages.SentMessage.error(to, message, ExceptionsHelper.detailedMessage(e));
        }
    }

    static URI url(String name, Settings settings, Settings defaultSettings) {
        String url = settings.get(URL_SETTING, defaultSettings.get(URL_SETTING, null));
        if (url == null) {
            throw new SettingsException("invalid slack [" + name + "] account settings. missing required [" + URL_SETTING + "] setting");
        }
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new SettingsException("invalid slack [" + name + "] account settings. invalid [" + URL_SETTING + "] setting", e);
        }
    }
}
