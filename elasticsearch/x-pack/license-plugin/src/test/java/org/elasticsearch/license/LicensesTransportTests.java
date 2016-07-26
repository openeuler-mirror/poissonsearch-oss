/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license;

import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.license.core.License;
import org.elasticsearch.xpack.monitoring.Monitoring;
import org.elasticsearch.node.Node;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.xpack.security.Security;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.elasticsearch.xpack.XPackPlugin;
import org.elasticsearch.xpack.watcher.Watcher;

import java.util.Collection;
import java.util.Collections;

import static org.elasticsearch.license.TestUtils.generateExpiredLicense;
import static org.elasticsearch.license.TestUtils.generateSignedLicense;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;

public class LicensesTransportTests extends ESSingleNodeTestCase {

    @Override
    protected boolean resetNodeAfterTest() {
        return true;
    }

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Collections.singletonList(XPackPlugin.class);
    }

    @Override
    protected Settings nodeSettings() {
        Settings.Builder newSettings = Settings.builder();
        newSettings.put(XPackPlugin.featureEnabledSetting(Security.NAME), false);
        newSettings.put(XPackPlugin.featureEnabledSetting(Monitoring.NAME), false);
        newSettings.put(XPackPlugin.featureEnabledSetting(Watcher.NAME), false);
        newSettings.put(Node.NODE_DATA_SETTING.getKey(), true);
        return newSettings.build();
    }

    public void testEmptyGetLicense() throws Exception {
        // trail license is added async, we should wait for it
        assertBusy(() -> {
            try {
                final ActionFuture<GetLicenseResponse> getLicenseFuture =
                        new GetLicenseRequestBuilder(client().admin().cluster(), GetLicenseAction.INSTANCE).execute();
                final GetLicenseResponse getLicenseResponse;
                getLicenseResponse = getLicenseFuture.get();
                assertNotNull(getLicenseResponse.license());
                assertThat(getLicenseResponse.license().operationMode(), equalTo(License.OperationMode.TRIAL));
            } catch (Exception e) {
                throw new RuntimeException("unexpected exception", e);
            }
        });
    }

    public void testPutLicense() throws Exception {
        License signedLicense = generateSignedLicense(TimeValue.timeValueMinutes(2));

        // put license
        PutLicenseRequestBuilder putLicenseRequestBuilder =
                new PutLicenseRequestBuilder(client().admin().cluster(), PutLicenseAction.INSTANCE).setLicense(signedLicense)
                        .setAcknowledge(true);
        PutLicenseResponse putLicenseResponse = putLicenseRequestBuilder.get();
        assertThat(putLicenseResponse.isAcknowledged(), equalTo(true));
        assertThat(putLicenseResponse.status(), equalTo(LicensesStatus.VALID));

        // get and check license
        GetLicenseResponse getLicenseResponse = new GetLicenseRequestBuilder(client().admin().cluster(), GetLicenseAction.INSTANCE).get();
        assertThat(getLicenseResponse.license(), equalTo(signedLicense));
    }

    public void testPutLicenseFromString() throws Exception {
        License signedLicense = generateSignedLicense(TimeValue.timeValueMinutes(2));
        String licenseString = TestUtils.dumpLicense(signedLicense);

        // put license source
        PutLicenseRequestBuilder putLicenseRequestBuilder =
                new PutLicenseRequestBuilder(client().admin().cluster(), PutLicenseAction.INSTANCE).setLicense(licenseString)
                        .setAcknowledge(true);
        PutLicenseResponse putLicenseResponse = putLicenseRequestBuilder.get();
        assertThat(putLicenseResponse.isAcknowledged(), equalTo(true));
        assertThat(putLicenseResponse.status(), equalTo(LicensesStatus.VALID));

        // get and check license
        GetLicenseResponse getLicenseResponse = new GetLicenseRequestBuilder(client().admin().cluster(), GetLicenseAction.INSTANCE).get();
        assertThat(getLicenseResponse.license(), equalTo(signedLicense));
    }

    public void testPutInvalidLicense() throws Exception {
        License signedLicense = generateSignedLicense(TimeValue.timeValueMinutes(2));

        // modify content of signed license
        License tamperedLicense = License.builder()
                .fromLicenseSpec(signedLicense, signedLicense.signature())
                .expiryDate(signedLicense.expiryDate() + 10 * 24 * 60 * 60 * 1000L)
                .validate()
                .build();

        PutLicenseRequestBuilder builder = new PutLicenseRequestBuilder(client().admin().cluster(), PutLicenseAction.INSTANCE);
        builder.setLicense(tamperedLicense);

        // try to put license (should be invalid)
        final PutLicenseResponse putLicenseResponse = builder.get();
        assertThat(putLicenseResponse.status(), equalTo(LicensesStatus.INVALID));

        // try to get invalid license
        GetLicenseResponse getLicenseResponse = new GetLicenseRequestBuilder(client().admin().cluster(), GetLicenseAction.INSTANCE).get();
        assertThat(getLicenseResponse.license(), not(tamperedLicense));
    }

    public void testPutExpiredLicense() throws Exception {
        License expiredLicense = generateExpiredLicense();
        PutLicenseRequestBuilder builder = new PutLicenseRequestBuilder(client().admin().cluster(), PutLicenseAction.INSTANCE);
        builder.setLicense(expiredLicense);
        PutLicenseResponse putLicenseResponse = builder.get();
        assertThat(putLicenseResponse.status(), equalTo(LicensesStatus.EXPIRED));
        // get license should not return the expired license
        GetLicenseResponse getLicenseResponse = new GetLicenseRequestBuilder(client().admin().cluster(), GetLicenseAction.INSTANCE).get();
        assertThat(getLicenseResponse.license(), not(expiredLicense));
    }

    public void testPutLicensesSimple() throws Exception {
        License basicSignedLicense = generateSignedLicense("basic", TimeValue.timeValueMinutes(5));
        PutLicenseRequestBuilder putLicenseRequestBuilder =
                new PutLicenseRequestBuilder(client().admin().cluster(), PutLicenseAction.INSTANCE).setLicense(basicSignedLicense)
                        .setAcknowledge(true);
        PutLicenseResponse putLicenseResponse = putLicenseRequestBuilder.get();
        assertThat(putLicenseResponse.status(), equalTo(LicensesStatus.VALID));
        GetLicenseResponse getLicenseResponse = new GetLicenseRequestBuilder(client().admin().cluster(), GetLicenseAction.INSTANCE).get();
        assertThat(getLicenseResponse.license(), equalTo(basicSignedLicense));

        License platinumSignedLicense = generateSignedLicense("platinum", TimeValue.timeValueMinutes(2));
        putLicenseRequestBuilder.setLicense(platinumSignedLicense);
        putLicenseResponse = putLicenseRequestBuilder.get();
        assertThat(putLicenseResponse.isAcknowledged(), equalTo(true));
        assertThat(putLicenseResponse.status(), equalTo(LicensesStatus.VALID));
        getLicenseResponse = new GetLicenseRequestBuilder(client().admin().cluster(), GetLicenseAction.INSTANCE).get();
        assertThat(getLicenseResponse.license(), equalTo(platinumSignedLicense));
    }

    public void testRemoveLicensesSimple() throws Exception {
        License goldLicense = generateSignedLicense("gold", TimeValue.timeValueMinutes(5));
        PutLicenseRequestBuilder putLicenseRequestBuilder =
                new PutLicenseRequestBuilder(client().admin().cluster(), PutLicenseAction.INSTANCE).setLicense(goldLicense)
                        .setAcknowledge(true);
        PutLicenseResponse putLicenseResponse = putLicenseRequestBuilder.get();
        assertThat(putLicenseResponse.isAcknowledged(), equalTo(true));
        assertThat(putLicenseResponse.status(), equalTo(LicensesStatus.VALID));
        GetLicenseResponse getLicenseResponse = new GetLicenseRequestBuilder(client().admin().cluster(), GetLicenseAction.INSTANCE).get();
        assertThat(getLicenseResponse.license(), equalTo(goldLicense));
        // delete all licenses
        DeleteLicenseRequestBuilder deleteLicenseRequestBuilder =
                new DeleteLicenseRequestBuilder(client().admin().cluster(), DeleteLicenseAction.INSTANCE);
        DeleteLicenseResponse deleteLicenseResponse = deleteLicenseRequestBuilder.get();
        assertThat(deleteLicenseResponse.isAcknowledged(), equalTo(true));
        // get licenses (expected no licenses)
        getLicenseResponse = new GetLicenseRequestBuilder(client().admin().cluster(), GetLicenseAction.INSTANCE).get();
        assertNull(getLicenseResponse.license());
    }
}