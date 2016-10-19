/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.notification;

import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsException;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Basic notification service
 */
public abstract class NotificationService<Account> extends AbstractComponent {

    // both are guarded by this
    private Map<String, Account> accounts;
    protected Account defaultAccount;

    public NotificationService(Settings settings) {
        super(settings);
    }

    protected synchronized void setAccountSetting(Settings settings) {
        Tuple<Map<String, Account>, Account> accounts = buildAccounts(settings, this::createAccount);
        this.accounts = Collections.unmodifiableMap(accounts.v1());
        this.defaultAccount = accounts.v2();
    }

    protected abstract Account createAccount(String name, Settings accountSettings);

    public Account getAccount(String name) {
        // note this is not final since we mock it in tests and that causes
        // trouble since final methods can't be mocked...
        final Map<String, Account> accounts;
        final Account defaultAccount;
        synchronized (this) { // must read under sync block otherwise it might be inconsistent
            accounts = this.accounts;
            defaultAccount = this.defaultAccount;
        }
        Account theAccount = accounts.getOrDefault(name, defaultAccount);
        if (theAccount == null) {
            throw new IllegalArgumentException("no account found for name: [" + name + "]");
        }
        return theAccount;
    }

    private <A> Tuple<Map<String, A>, A> buildAccounts(Settings settings, BiFunction<String, Settings, A> accountFactory) {
        Settings accountsSettings = settings.getAsSettings("account");
        Map<String, A> accounts = new HashMap<>();
        for (String name : accountsSettings.names()) {
            Settings accountSettings = accountsSettings.getAsSettings(name);
            A account = accountFactory.apply(name, accountSettings);
            accounts.put(name, account);
        }

        final String defaultAccountName = settings.get("default_account");
        A defaultAccount;
        if (defaultAccountName == null) {
            if (accounts.isEmpty()) {
                defaultAccount = null;
            } else {
                A account = accounts.values().iterator().next();
                defaultAccount = account;

            }
        } else {
            defaultAccount = accounts.get(defaultAccountName);
            if (defaultAccount == null) {
                throw new SettingsException("could not find default account [" + defaultAccountName + "]");
            }
        }
        return new Tuple<>(accounts, defaultAccount);
    }
}
