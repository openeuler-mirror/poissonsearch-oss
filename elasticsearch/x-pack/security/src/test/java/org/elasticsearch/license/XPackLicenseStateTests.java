/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license;

import java.util.Arrays;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.elasticsearch.license.core.License.OperationMode;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.monitoring.Monitoring;
import org.elasticsearch.xpack.security.Security;
import org.hamcrest.Matchers;

import static org.elasticsearch.license.core.License.OperationMode.MISSING;
import static org.elasticsearch.license.core.License.OperationMode.BASIC;
import static org.elasticsearch.license.core.License.OperationMode.GOLD;
import static org.elasticsearch.license.core.License.OperationMode.PLATINUM;
import static org.elasticsearch.license.core.License.OperationMode.STANDARD;
import static org.elasticsearch.license.core.License.OperationMode.TRIAL;
import static org.hamcrest.Matchers.is;

/**
 * Unit tests for the {@link XPackLicenseState}
 */
public class XPackLicenseStateTests extends ESTestCase {

    /** Creates a license state with the given license type and active state, and checks the given method returns expected. */
    void assertAllowed(OperationMode mode, boolean active, Predicate<XPackLicenseState> predicate, boolean expected) {
        XPackLicenseState licenseState = new XPackLicenseState();
        licenseState.update(mode, active);
        assertEquals(expected, predicate.test(licenseState));
    }

    /**
     * Checks the ack message going from the  {@code from} license type to {@code to} license type.
     * TODO: check the actual messages, not just the number of them! This was copied from previous license tests...
     */
    void assertAckMesssages(String feature, OperationMode from, OperationMode to, int expectedMessages) {
        String[] gotMessages = XPackLicenseState.ACKNOWLEDGMENT_MESSAGES.get(feature).apply(from, to);
        assertEquals(expectedMessages, gotMessages.length);
    }

    static <T> T randomFrom(T[] values, Predicate<T> filter) {
        return randomFrom(Arrays.stream(values).filter(filter).collect(Collectors.toList()));
    }

    static OperationMode randomMode() {
        return randomFrom(OperationMode.values());
    }

    static OperationMode randomTrialStandardGoldOrPlatinumMode() {
        return randomFrom(TRIAL, STANDARD, GOLD, PLATINUM);
    }

    static OperationMode randomTrialOrPlatinumMode() {
        return randomFrom(TRIAL, PLATINUM);
    }

    public void testSecurityDefaults() {
        XPackLicenseState licenseState = new XPackLicenseState();
        assertThat(licenseState.isAuthAllowed(), is(true));
        assertThat(licenseState.isIpFilteringAllowed(), is(true));
        assertThat(licenseState.isAuditingAllowed(), is(true));
        assertThat(licenseState.isStatsAndHealthAllowed(), is(true));
        assertThat(licenseState.isDocumentAndFieldLevelSecurityAllowed(), is(true));
        assertThat(licenseState.allowedRealmType(), Matchers.is(XPackLicenseState.AllowedRealmType.ALL));
    }

    public void testSecurityBasic() {
        XPackLicenseState licenseState = new XPackLicenseState();
        licenseState.update(BASIC, true);

        assertThat(licenseState.isAuthAllowed(), is(false));
        assertThat(licenseState.isIpFilteringAllowed(), is(false));
        assertThat(licenseState.isAuditingAllowed(), is(false));
        assertThat(licenseState.isStatsAndHealthAllowed(), is(true));
        assertThat(licenseState.isDocumentAndFieldLevelSecurityAllowed(), is(false));
        assertThat(licenseState.allowedRealmType(), Matchers.is(XPackLicenseState.AllowedRealmType.NONE));
    }

    public void testSecurityBasicExpired() {
        XPackLicenseState licenseState = new XPackLicenseState();
        licenseState.update(BASIC, false);

        assertThat(licenseState.isAuthAllowed(), is(false));
        assertThat(licenseState.isIpFilteringAllowed(), is(false));
        assertThat(licenseState.isAuditingAllowed(), is(false));
        assertThat(licenseState.isStatsAndHealthAllowed(), is(false));
        assertThat(licenseState.isDocumentAndFieldLevelSecurityAllowed(), is(false));
        assertThat(licenseState.allowedRealmType(), Matchers.is(XPackLicenseState.AllowedRealmType.NONE));
    }

    public void testSecurityStandard() {
        XPackLicenseState licenseState = new XPackLicenseState();
        licenseState.update(STANDARD, true);

        assertThat(licenseState.isAuthAllowed(), is(true));
        assertThat(licenseState.isIpFilteringAllowed(), is(false));
        assertThat(licenseState.isAuditingAllowed(), is(false));
        assertThat(licenseState.isStatsAndHealthAllowed(), is(true));
        assertThat(licenseState.isDocumentAndFieldLevelSecurityAllowed(), is(false));
        assertThat(licenseState.allowedRealmType(), Matchers.is(XPackLicenseState.AllowedRealmType.NATIVE));
    }

    public void testSecurityStandardExpired() {
        XPackLicenseState licenseState = new XPackLicenseState();
        licenseState.update(STANDARD, false);

        assertThat(licenseState.isAuthAllowed(), is(true));
        assertThat(licenseState.isIpFilteringAllowed(), is(false));
        assertThat(licenseState.isAuditingAllowed(), is(false));
        assertThat(licenseState.isStatsAndHealthAllowed(), is(false));
        assertThat(licenseState.isDocumentAndFieldLevelSecurityAllowed(), is(false));
        assertThat(licenseState.allowedRealmType(), Matchers.is(XPackLicenseState.AllowedRealmType.NATIVE));
    }

    public void testSecurityGold() {
        XPackLicenseState licenseState = new XPackLicenseState();
        licenseState.update(GOLD, true);

        assertThat(licenseState.isAuthAllowed(), is(true));
        assertThat(licenseState.isIpFilteringAllowed(), is(true));
        assertThat(licenseState.isAuditingAllowed(), is(true));
        assertThat(licenseState.isStatsAndHealthAllowed(), is(true));
        assertThat(licenseState.isDocumentAndFieldLevelSecurityAllowed(), is(false));
        assertThat(licenseState.allowedRealmType(), Matchers.is(XPackLicenseState.AllowedRealmType.DEFAULT));
    }

    public void testSecurityGoldExpired() {
        XPackLicenseState licenseState = new XPackLicenseState();
        licenseState.update(GOLD, false);

        assertThat(licenseState.isAuthAllowed(), is(true));
        assertThat(licenseState.isIpFilteringAllowed(), is(true));
        assertThat(licenseState.isAuditingAllowed(), is(true));
        assertThat(licenseState.isStatsAndHealthAllowed(), is(false));
        assertThat(licenseState.isDocumentAndFieldLevelSecurityAllowed(), is(false));
        assertThat(licenseState.allowedRealmType(), Matchers.is(XPackLicenseState.AllowedRealmType.DEFAULT));
    }

    public void testSecurityPlatinum() {
        XPackLicenseState licenseState = new XPackLicenseState();
        licenseState.update(PLATINUM, true);

        assertThat(licenseState.isAuthAllowed(), is(true));
        assertThat(licenseState.isIpFilteringAllowed(), is(true));
        assertThat(licenseState.isAuditingAllowed(), is(true));
        assertThat(licenseState.isStatsAndHealthAllowed(), is(true));
        assertThat(licenseState.isDocumentAndFieldLevelSecurityAllowed(), is(true));
        assertThat(licenseState.allowedRealmType(), Matchers.is(XPackLicenseState.AllowedRealmType.ALL));
    }

    public void testSecurityPlatinumExpired() {
        XPackLicenseState licenseState = new XPackLicenseState();
        licenseState.update(PLATINUM, false);

        assertThat(licenseState.isAuthAllowed(), is(true));
        assertThat(licenseState.isIpFilteringAllowed(), is(true));
        assertThat(licenseState.isAuditingAllowed(), is(true));
        assertThat(licenseState.isStatsAndHealthAllowed(), is(false));
        assertThat(licenseState.isDocumentAndFieldLevelSecurityAllowed(), is(true));
        assertThat(licenseState.allowedRealmType(), Matchers.is(XPackLicenseState.AllowedRealmType.ALL));
    }

    public void testSecurityAckBasicToNotGoldOrStandard() {
        OperationMode toMode = randomFrom(OperationMode.values(), mode -> mode != GOLD && mode != STANDARD);
        assertAckMesssages(Security.NAME, BASIC, toMode, 0);
    }

    public void testSecurityAckAnyToTrialOrPlatinum() {
        assertAckMesssages(Security.NAME, randomMode(), randomTrialOrPlatinumMode(), 0);
    }

    public void testSecurityAckTrialStandardGoldOrPlatinumToBasic() {
        assertAckMesssages(Security.NAME, randomTrialStandardGoldOrPlatinumMode(), BASIC, 3);
    }

    public void testSecurityAckAnyToStandard() {
        OperationMode from = randomFrom(BASIC, GOLD, PLATINUM, TRIAL);
        assertAckMesssages(Security.NAME, from, STANDARD, 4);
    }

    public void testSecurityAckBasicStandardTrialOrPlatinumToGold() {
        OperationMode from = randomFrom(BASIC, PLATINUM, TRIAL, STANDARD);
        assertAckMesssages(Security.NAME, from, GOLD, 2);
    }

    public void testMonitoringAckBasicToAny() {
        assertAckMesssages(Monitoring.NAME, BASIC, randomMode(), 0);
    }

    public void testMonitoringAckAnyToTrialGoldOrPlatinum() {
        assertAckMesssages(Monitoring.NAME, randomMode(), randomTrialStandardGoldOrPlatinumMode(), 0);
    }

    public void testMonitoringAckNotBasicToBasic() {
        OperationMode from = randomFrom(STANDARD, GOLD, PLATINUM, TRIAL);
        assertAckMesssages(Monitoring.NAME, from, BASIC, 2);
    }

    public void testMonitoringAllowed() {
        assertAllowed(randomMode(), true, XPackLicenseState::isMonitoringAllowed, true);
        assertAllowed(randomMode(), false, XPackLicenseState::isMonitoringAllowed, false);
    }

    public void testMonitoringUpdateRetention() {
        OperationMode mode = randomFrom(STANDARD, GOLD, PLATINUM, TRIAL);
        assertAllowed(mode, true, XPackLicenseState::isUpdateRetentionAllowed, true);
        assertAllowed(BASIC, true, XPackLicenseState::isUpdateRetentionAllowed, false);
        assertAllowed(MISSING, false, XPackLicenseState::isUpdateRetentionAllowed, false);
    }

    public void testWatcherPlatinumGoldTrial() throws Exception {
        assertAllowed(randomFrom(TRIAL, GOLD, PLATINUM), true, XPackLicenseState::isWatcherAllowed, true);
    }

    public void testWatcherBasicStandardLicense() throws Exception {
        assertAllowed(randomFrom(BASIC, STANDARD), true, XPackLicenseState::isWatcherAllowed, false);
    }

    public void testWatcherInactive() {
        assertAllowed(randomFrom(BASIC, STANDARD), false, XPackLicenseState::isWatcherAllowed, false);
    }

    public void testWatcherInactivePlatinumGoldTrial() throws Exception {
        assertAllowed(randomFrom(TRIAL, GOLD, PLATINUM), false, XPackLicenseState::isWatcherAllowed, false);
    }

    public void testGraphPlatinumTrial() throws Exception {
        assertAllowed(TRIAL, true, XPackLicenseState::isGraphAllowed, true);
        assertAllowed(PLATINUM, true, XPackLicenseState::isGraphAllowed, true);
    }

    public void testGraphBasic() throws Exception {
        assertAllowed(BASIC, true, XPackLicenseState::isGraphAllowed, false);
    }

    public void testGraphStandard() throws Exception {
        assertAllowed(STANDARD, true, XPackLicenseState::isGraphAllowed, false);
    }

    public void testGraphInactiveBasic() {
        assertAllowed(BASIC, false, XPackLicenseState::isGraphAllowed, false);
    }

    public void testGraphInactivePlatinumTrial() throws Exception {
        assertAllowed(TRIAL, false, XPackLicenseState::isGraphAllowed, false);
        assertAllowed(PLATINUM, false, XPackLicenseState::isGraphAllowed, false);
    }
}
