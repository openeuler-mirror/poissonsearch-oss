/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license.plugin;

import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ProcessedClusterStateUpdateTask;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.base.Predicate;
import org.elasticsearch.common.collect.Lists;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.license.core.ESLicense;
import org.elasticsearch.license.licensor.ESLicenseSigner;
import org.elasticsearch.license.plugin.action.put.PutLicenseRequestBuilder;
import org.elasticsearch.license.plugin.action.put.PutLicenseResponse;
import org.elasticsearch.license.plugin.consumer.EagerLicenseRegistrationPluginService;
import org.elasticsearch.license.plugin.consumer.LazyLicenseRegistrationPluginService;
import org.elasticsearch.license.plugin.consumer.TestPluginServiceBase;
import org.elasticsearch.license.plugin.core.LicensesManagerService;
import org.elasticsearch.license.plugin.core.LicensesMetaData;
import org.elasticsearch.license.plugin.core.LicensesStatus;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.elasticsearch.test.InternalTestCluster;
import org.junit.Ignore;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.license.AbstractLicensingTestBase.getTestPriKeyPath;
import static org.elasticsearch.license.AbstractLicensingTestBase.getTestPubKeyPath;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

public abstract class AbstractLicensesIntegrationTests extends ElasticsearchIntegrationTest {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return ImmutableSettings.settingsBuilder()
                .put("plugins.load_classpath_plugins", false)
                .put("plugin.types", LicensePlugin.class.getName())
                .build();
    }

    @Override
    protected Settings transportClientSettings() {
        // Plugin should be loaded on the transport client as well
        return nodeSettings(0);
    }

    protected void wipeAllLicenses() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        ClusterService clusterService = internalCluster().getInstance(ClusterService.class, internalCluster().getMasterName());
        clusterService.submitStateUpdateTask("delete licensing metadata", new ProcessedClusterStateUpdateTask() {
            @Override
            public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                latch.countDown();
            }

            @Override
            public ClusterState execute(ClusterState currentState) throws Exception {
                MetaData.Builder mdBuilder = MetaData.builder(currentState.metaData());
                mdBuilder.putCustom(LicensesMetaData.TYPE, null);
                return ClusterState.builder(currentState).metaData(mdBuilder).build();
            }

            @Override
            public void onFailure(String source, @Nullable Throwable t) {
                logger.error("error on metaData cleanup after test", t);
            }
        });
        latch.await();
    }

    public static ESLicense generateSignedLicense(String feature, TimeValue expiryDate) throws Exception {
        final ESLicense licenseSpec = ESLicense.builder()
                .uid(UUID.randomUUID().toString())
                .feature(feature)
                .expiryDate(System.currentTimeMillis() + expiryDate.getMillis())
                .issueDate(System.currentTimeMillis())
                .type("subscription")
                .subscriptionType("gold")
                .issuedTo("customer")
                .issuer("elasticsearch")
                .maxNodes(randomIntBetween(5, 100))
                .build();

        ESLicenseSigner signer = new ESLicenseSigner(getTestPriKeyPath(), getTestPubKeyPath());
        return signer.sign(licenseSpec);
    }

    protected void putLicense(String feature, TimeValue expiryDuration) throws Exception {
        ESLicense license1 = generateSignedLicense(feature, expiryDuration);
        final PutLicenseResponse putLicenseResponse = new PutLicenseRequestBuilder(client().admin().cluster()).setLicense(Lists.newArrayList(license1)).get();
        assertThat(putLicenseResponse.isAcknowledged(), equalTo(true));
        assertThat(putLicenseResponse.status(), equalTo(LicensesStatus.VALID));
    }


    protected void assertLicenseManagerEnabledFeatureFor(final String feature) throws InterruptedException {
        assertLicenseManagerStatusFor(feature, true);
    }

    protected void assertLicenseManagerDisabledFeatureFor(final String feature) throws InterruptedException {
        assertLicenseManagerStatusFor(feature, false);
    }

    protected void assertLicenseManagerStatusFor(final String feature, final boolean expectedEnabled) throws InterruptedException {
        assertThat("LicenseManager for feature " + feature + " should have enabled status of " + expectedEnabled, awaitBusy(new Predicate<Object>() {
            @Override
            public boolean apply(Object o) {
                for (LicensesManagerService managerService : licensesManagerServices()) {
                    if (expectedEnabled != managerService.enabledFeatures().contains(feature)) {
                        return false;
                    }
                }
                return true;
            }
        }, 2, TimeUnit.SECONDS), equalTo(true));
    }

    protected void assertEagerConsumerPluginDisableNotification(int timeoutInSec) throws InterruptedException {
        assertEagerConsumerPluginNotification(false, timeoutInSec);
    }

    protected void assertEagerConsumerPluginEnableNotification(int timeoutInSec) throws InterruptedException {
        assertEagerConsumerPluginNotification(true, timeoutInSec);
    }

    protected void assertLazyConsumerPluginDisableNotification(int timeoutInSec) throws InterruptedException {
        assertLazyConsumerPluginNotification(false, timeoutInSec);
    }

    protected void assertLazyConsumerPluginEnableNotification(int timeoutInSec) throws InterruptedException {
        assertLazyConsumerPluginNotification(true, timeoutInSec);
    }

    protected void assertLazyConsumerPluginNotification(final boolean expectedEnabled, int timeoutInSec) throws InterruptedException {
        final List<TestPluginServiceBase> consumerPluginServices = consumerLazyPluginServices();
        assertConsumerPluginNotification(consumerPluginServices, expectedEnabled, timeoutInSec);
    }

    protected void assertEagerConsumerPluginNotification(final boolean expectedEnabled, int timeoutInSec) throws InterruptedException {
        final List<TestPluginServiceBase> consumerPluginServices = consumerEagerPluginServices();
        assertConsumerPluginNotification(consumerPluginServices, expectedEnabled, timeoutInSec);
    }

    protected void assertConsumerPluginNotification(final List<TestPluginServiceBase> consumerPluginServices, final boolean expectedEnabled, int timeoutInSec) throws InterruptedException {
        assertThat("At least one instance has to be present", consumerPluginServices.size(), greaterThan(0));
        assertConsumerPluginNotification(consumerPluginServices.get(0).getClass().getName() + " should have license status of: " + expectedEnabled, consumerPluginServices, expectedEnabled, timeoutInSec);
    }

    private void assertConsumerPluginNotification(String msg, final Iterable<TestPluginServiceBase> consumerPluginServices, final boolean expectedEnabled, int timeoutInSec) throws InterruptedException {
        assertThat(msg, awaitBusy(new Predicate<Object>() {
            @Override
            public boolean apply(Object o) {
                for (TestPluginServiceBase pluginService : consumerPluginServices) {
                    if (expectedEnabled != pluginService.enabled()) {
                        return false;
                    }
                }
                return true;
            }
        }, timeoutInSec, TimeUnit.SECONDS), equalTo(true));

    }

    private List<TestPluginServiceBase> consumerLazyPluginServices() {
        final InternalTestCluster clients = internalCluster();
        List<TestPluginServiceBase> consumerPluginServices = new ArrayList<>();
        for (TestPluginServiceBase service : clients.getDataNodeInstances(LazyLicenseRegistrationPluginService.class)) {
            consumerPluginServices.add(service);
        }
        return consumerPluginServices;
    }

    private List<TestPluginServiceBase> consumerEagerPluginServices() {
        final InternalTestCluster clients = internalCluster();
        List<TestPluginServiceBase> consumerPluginServices = new ArrayList<>();
        for (TestPluginServiceBase service : clients.getDataNodeInstances(EagerLicenseRegistrationPluginService.class)) {
            consumerPluginServices.add(service);
        }
        return consumerPluginServices;
    }

    private Iterable<LicensesManagerService> licensesManagerServices() {
        final InternalTestCluster clients = internalCluster();
        return clients.getDataNodeInstances(LicensesManagerService.class);
    }
}
