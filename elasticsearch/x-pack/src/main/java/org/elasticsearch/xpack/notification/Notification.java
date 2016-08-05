/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.notification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.elasticsearch.client.Client;
import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.xpack.notification.email.EmailService;
import org.elasticsearch.xpack.notification.hipchat.HipChatService;
import org.elasticsearch.xpack.notification.pagerduty.PagerDutyAccount;
import org.elasticsearch.xpack.notification.pagerduty.PagerDutyService;
import org.elasticsearch.xpack.notification.slack.SlackService;

public class Notification {

    private final boolean transportClient;

    public Notification(Settings settings) {
        this.transportClient = "transport".equals(settings.get(Client.CLIENT_TYPE_SETTING_S.getKey()));
    }

    public List<Setting<?>> getSettings() {
        return Arrays.asList(SlackService.SLACK_ACCOUNT_SETTING,
        EmailService.EMAIL_ACCOUNT_SETTING,
        HipChatService.HIPCHAT_ACCOUNT_SETTING,
        PagerDutyService.PAGERDUTY_ACCOUNT_SETTING);
    }

    public List<String> getSettingsFilter() {
        ArrayList<String> settingsFilter = new ArrayList<>();
        settingsFilter.add("xpack.notification.email.account.*.smtp.password");
        settingsFilter.add("xpack.notification.slack.account.*.url");
        settingsFilter.add("xpack.notification.pagerduty.account.*.url");
        settingsFilter.add("xpack.notification.pagerduty." + PagerDutyAccount.SERVICE_KEY_SETTING);
        settingsFilter.add("xpack.notification.pagerduty.account.*." + PagerDutyAccount.SERVICE_KEY_SETTING);
        settingsFilter.add("xpack.notification.hipchat.account.*.auth_token");
        return settingsFilter;
    }

    public Collection<? extends Module> nodeModules() {
        if (transportClient) {
            return Collections.emptyList();
        }
        List<Module> modules = new ArrayList<>();
        modules.add(new NotificationModule());
        return modules;
    }
}
