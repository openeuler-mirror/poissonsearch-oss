/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license.plugin.core;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateUpdateTask;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.transport.LocalTransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.license.core.License;
import org.elasticsearch.license.plugin.TestUtils;
import org.junit.After;
import org.junit.Before;
import org.mockito.ArgumentCaptor;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class LicenseClusterChangeTests extends AbstractLicenseServiceTestCase {

    private TestUtils.AssertingLicensee licensee;

    @Before
    public void setup() {
        setInitialState(null);
        licensesService.start();
        licensee = new TestUtils.AssertingLicensee("LicenseClusterChangeTests", logger);
        licensesService.register(licensee);
    }

    @After
    public void teardown() {
        licensesService.stop();
    }


    public void testNotificationOnNewLicense() throws Exception {
        ClusterState oldState = ClusterState.builder(new ClusterName("a")).build();
        final License license = TestUtils.generateSignedLicense(TimeValue.timeValueHours(24));
        MetaData metaData = MetaData.builder().putCustom(LicensesMetaData.TYPE, new LicensesMetaData(license)).build();
        ClusterState newState = ClusterState.builder(new ClusterName("a")).metaData(metaData).build();
        licensesService.clusterChanged(new ClusterChangedEvent("simulated", newState, oldState));
        assertThat(licensee.statuses.size(), equalTo(1));
        assertTrue(licensee.statuses.get(0).getLicenseState() == LicenseState.ENABLED);
    }

    public void testNoNotificationOnExistingLicense() throws Exception {
        final License license = TestUtils.generateSignedLicense(TimeValue.timeValueHours(24));
        MetaData metaData = MetaData.builder().putCustom(LicensesMetaData.TYPE, new LicensesMetaData(license)).build();
        ClusterState newState = ClusterState.builder(new ClusterName("a")).metaData(metaData).build();
        ClusterState oldState = ClusterState.builder(newState).build();
        licensesService.clusterChanged(new ClusterChangedEvent("simulated", newState, oldState));
        assertThat(licensee.statuses.size(), equalTo(0));
    }

    public void testTrialLicenseGeneration() throws Exception {
        DiscoveryNode master = new DiscoveryNode("b", LocalTransportAddress.buildUnique(), emptyMap(), emptySet(), Version.CURRENT);
        ClusterState oldState = ClusterState.builder(new ClusterName("a"))
                .nodes(DiscoveryNodes.builder().masterNodeId(master.getId()).put(master)).build();
        when(discoveryNodes.isLocalNodeElectedMaster()).thenReturn(true);
        ClusterState newState = ClusterState.builder(oldState).nodes(discoveryNodes).build();

        licensesService.clusterChanged(new ClusterChangedEvent("simulated", newState, oldState));
        ArgumentCaptor<ClusterStateUpdateTask> stateUpdater = ArgumentCaptor.forClass(ClusterStateUpdateTask.class);
        verify(clusterService, times(1)).submitStateUpdateTask(any(), stateUpdater.capture());
        ClusterState stateWithLicense = stateUpdater.getValue().execute(newState);
        LicensesMetaData licenseMetaData = stateWithLicense.metaData().custom(LicensesMetaData.TYPE);
        assertNotNull(licenseMetaData);
        assertNotNull(licenseMetaData.getLicense());
        assertEquals(clock.millis() + LicensesService.TRIAL_LICENSE_DURATION.millis(), licenseMetaData.getLicense().expiryDate());
    }
}