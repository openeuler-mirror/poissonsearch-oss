/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.actions.hipchat.service;

import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.watcher.shield.WatcherSettingsFilter;
import org.elasticsearch.watcher.support.http.HttpClient;

/**
 *
 */
public class InternalHipChatService extends AbstractLifecycleComponent<InternalHipChatService> implements HipChatService {

    private final HttpClient httpClient;
    private volatile HipChatAccounts accounts;
    public static final Setting<Settings> HIPCHAT_ACCOUNT_SETTING = Setting.groupSetting("watcher.actions.hipchat.service.", true, Setting.Scope.CLUSTER);

    @Inject
    public InternalHipChatService(Settings settings, HttpClient httpClient, ClusterSettings clusterSettings, WatcherSettingsFilter settingsFilter) {
        super(settings);
        this.httpClient = httpClient;
        settingsFilter.filterOut("watcher.actions.hipchat.service.account.*.auth_token");
        clusterSettings.addSettingsUpdateConsumer(HIPCHAT_ACCOUNT_SETTING, this::setHipchatAccountSetting);
    }

    @Override
    protected void doStart() {
        setHipchatAccountSetting(HIPCHAT_ACCOUNT_SETTING.get(settings));
    }

    @Override
    protected void doStop() {
    }

    @Override
    protected void doClose() {
    }

    private void setHipchatAccountSetting(Settings setting) {
        accounts = new HipChatAccounts(setting, httpClient, logger);
    }

    @Override
    public HipChatAccount getDefaultAccount() {
        return accounts.account(null);
    }

    @Override
    public HipChatAccount getAccount(String name) {
        return accounts.account(name);
    }
}
