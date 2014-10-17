/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license.plugin;

import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.ListenableActionFuture;
import org.elasticsearch.common.collect.ImmutableSet;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.license.TestUtils;
import org.elasticsearch.license.core.ESLicenses;
import org.elasticsearch.license.core.LicenseBuilders;
import org.elasticsearch.license.core.LicenseUtils;
import org.elasticsearch.license.plugin.action.delete.DeleteLicenseRequestBuilder;
import org.elasticsearch.license.plugin.action.delete.DeleteLicenseResponse;
import org.elasticsearch.license.plugin.action.get.GetLicenseRequestBuilder;
import org.elasticsearch.license.plugin.action.get.GetLicenseResponse;
import org.elasticsearch.license.plugin.action.put.PutLicenseRequestBuilder;
import org.elasticsearch.license.plugin.action.put.PutLicenseResponse;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.elasticsearch.license.core.LicenseUtils.readLicensesFromString;
import static org.elasticsearch.test.ElasticsearchIntegrationTest.ClusterScope;
import static org.elasticsearch.test.ElasticsearchIntegrationTest.Scope.SUITE;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;

@ClusterScope(scope = SUITE, numDataNodes = 10)
public class LicenseTransportTests extends ElasticsearchIntegrationTest {

    private static String pubKeyPath = null;
    private static String priKeyPath = null;

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

    @BeforeClass
    public static void setup() throws IOException, URISyntaxException {
        priKeyPath = Paths.get(LicenseTransportTests.class.getResource("/org.elasticsearch.license.plugin/test_pri.key").toURI()).toAbsolutePath().toString();
        pubKeyPath = Paths.get(LicenseTransportTests.class.getResource("/org.elasticsearch.license.plugin/test_pub.key").toURI()).toAbsolutePath().toString();
    }

    /*
     * TODO:
     *  - add more delete tests
     *  - add put invalid licenses tests
     *  - add multiple licenses of the same feature tests
     */

    @Test
    public void testEmptyGetLicense() throws Exception {
        DeleteLicenseRequestBuilder deleteLicenseRequestBuilder = new DeleteLicenseRequestBuilder(client().admin().cluster()).setFeatures(ImmutableSet.of("marvel", "shield"));
        final ActionFuture<DeleteLicenseResponse> deleteFuture = deleteLicenseRequestBuilder.execute();
        final DeleteLicenseResponse deleteLicenseResponse = deleteFuture.get();
        assertTrue(deleteLicenseResponse.isAcknowledged());

        final ActionFuture<GetLicenseResponse> getLicenseFuture = new GetLicenseRequestBuilder(client().admin().cluster()).execute();

        final GetLicenseResponse getLicenseResponse = getLicenseFuture.get();

        assertThat("expected 0 licenses; but got: " + getLicenseResponse.licenses(), getLicenseResponse.licenses().licenses().size(), equalTo(0));
    }

    @Test
    public void testPutLicense() throws ParseException, ExecutionException, InterruptedException, IOException {

        Map<ESLicenses.FeatureType, TestUtils.FeatureAttributes> map = new HashMap<>();
        TestUtils.FeatureAttributes featureAttributes =
                new TestUtils.FeatureAttributes("shield", "subscription", "platinum", "foo bar Inc.", "elasticsearch", 2, "2014-12-13", "2015-12-13");
        map.put(ESLicenses.FeatureType.SHIELD, featureAttributes);
        String licenseString = TestUtils.generateESLicenses(map);
        String licenseOutput = TestUtils.runLicenseGenerationTool(licenseString, pubKeyPath, priKeyPath);

        PutLicenseRequestBuilder putLicenseRequestBuilder = new PutLicenseRequestBuilder(client().admin().cluster());
        //putLicenseRequest.license(licenseString);
        final ESLicenses putLicenses = LicenseUtils.readLicensesFromString(licenseOutput);
        putLicenseRequestBuilder.setLicense(putLicenses);
        //LicenseUtils.printLicense(putLicenses);
        ensureGreen();

        final ActionFuture<PutLicenseResponse> putLicenseFuture = putLicenseRequestBuilder.execute();

        final PutLicenseResponse putLicenseResponse = putLicenseFuture.get();

        assertThat(putLicenseResponse.isAcknowledged(), equalTo(true));

        ActionFuture<GetLicenseResponse> getLicenseFuture = new GetLicenseRequestBuilder(client().admin().cluster()).execute();

        GetLicenseResponse getLicenseResponse = getLicenseFuture.get();

        assertThat(getLicenseResponse.licenses(), notNullValue());

        //LicenseUtils.printLicense(getLicenseResponse.licenses());
        TestUtils.isSame(putLicenses, getLicenseResponse.licenses());


        final ActionFuture<DeleteLicenseResponse> deleteFuture = new DeleteLicenseRequestBuilder(client().admin().cluster())
                .setFeatures(ImmutableSet.of("marvel", "shield")).execute();
        final DeleteLicenseResponse deleteLicenseResponse = deleteFuture.get();
        assertTrue(deleteLicenseResponse.isAcknowledged());

        getLicenseResponse = new GetLicenseRequestBuilder(client().admin().cluster()).execute().get();
        TestUtils.isSame(getLicenseResponse.licenses(), LicenseBuilders.licensesBuilder().build());
    }

    @Test
    public void testPutInvalidLicense() throws Exception {
        Map<ESLicenses.FeatureType, TestUtils.FeatureAttributes> map = new HashMap<>();
        TestUtils.FeatureAttributes featureAttributes =
                new TestUtils.FeatureAttributes("shield", "subscription", "platinum", "foo bar Inc.", "elasticsearch", 2, "2014-12-13", "2015-12-13");
        map.put(ESLicenses.FeatureType.SHIELD, featureAttributes);
        String licenseString = TestUtils.generateESLicenses(map);
        String licenseOutput = TestUtils.runLicenseGenerationTool(licenseString, pubKeyPath, priKeyPath);

        ESLicenses esLicenses = readLicensesFromString(licenseOutput);

        ESLicenses.ESLicense esLicense = esLicenses.get(ESLicenses.FeatureType.SHIELD);
        ESLicenses.ESLicense tamperedLicense = LicenseBuilders.licenseBuilder(true)
                .fromLicense(esLicense)
                .expiryDate(esLicense.expiryDate() + 10 * 24 * 60 * 60 * 1000l)
                .issuer("elasticsearch")
                .build();

        PutLicenseRequestBuilder builder = new PutLicenseRequestBuilder(client().admin().cluster());
        builder.setLicense(LicenseBuilders.licensesBuilder().license(tamperedLicense).build());

        final ListenableActionFuture<PutLicenseResponse> execute = builder.execute();

        try {
            execute.get();
            fail("Invalid License should throw exception");
        } catch (Throwable e) {
            /* TODO: figure out error handling
            String msg =e.getCause().getCause().getCause().getMessage();//e.getCause().getCause().getMessage();// e.getCause().getCause().getCause().getMessage();
            assertTrue("Error message: " + msg, msg.contains("Invalid License(s)"));
            */
        }
    }

}
