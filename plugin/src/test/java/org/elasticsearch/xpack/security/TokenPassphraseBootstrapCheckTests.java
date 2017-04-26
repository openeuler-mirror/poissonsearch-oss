/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security;

import org.elasticsearch.common.settings.MockSecureSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.XPackSettings;
import org.elasticsearch.xpack.security.TokenPassphraseBootstrapCheck;
import org.elasticsearch.xpack.security.authc.TokenService;

import static org.elasticsearch.xpack.security.TokenPassphraseBootstrapCheck.MINIMUM_PASSPHRASE_LENGTH;

public class TokenPassphraseBootstrapCheckTests extends ESTestCase {

    public void testTokenPassphraseCheck() throws Exception {
        assertTrue(new TokenPassphraseBootstrapCheck(Settings.EMPTY).check());
        MockSecureSettings secureSettings = new MockSecureSettings();
        Settings settings = Settings.builder().setSecureSettings(secureSettings).build();
        assertTrue(new TokenPassphraseBootstrapCheck(settings).check());

        secureSettings.setString(TokenService.TOKEN_PASSPHRASE.getKey(), randomAlphaOfLengthBetween(MINIMUM_PASSPHRASE_LENGTH, 30));
        assertFalse(new TokenPassphraseBootstrapCheck(settings).check());

        secureSettings.setString(TokenService.TOKEN_PASSPHRASE.getKey(), TokenService.DEFAULT_PASSPHRASE);
        assertTrue(new TokenPassphraseBootstrapCheck(settings).check());

        secureSettings.setString(TokenService.TOKEN_PASSPHRASE.getKey(), randomAlphaOfLengthBetween(1, MINIMUM_PASSPHRASE_LENGTH - 1));
        assertTrue(new TokenPassphraseBootstrapCheck(settings).check());
    }

    public void testTokenPassphraseCheckServiceDisabled() throws Exception {
        Settings settings = Settings.builder().put(XPackSettings.TOKEN_SERVICE_ENABLED_SETTING.getKey(), false).build();
        assertFalse(new TokenPassphraseBootstrapCheck(settings).check());
        MockSecureSettings secureSettings = new MockSecureSettings();
        settings = Settings.builder().put(settings).setSecureSettings(secureSettings).build();
        assertFalse(new TokenPassphraseBootstrapCheck(settings).check());

        secureSettings.setString(TokenService.TOKEN_PASSPHRASE.getKey(), randomAlphaOfLengthBetween(1, 30));
        assertFalse(new TokenPassphraseBootstrapCheck(settings).check());

        secureSettings.setString(TokenService.TOKEN_PASSPHRASE.getKey(), TokenService.DEFAULT_PASSPHRASE);
        assertFalse(new TokenPassphraseBootstrapCheck(settings).check());
    }
}
