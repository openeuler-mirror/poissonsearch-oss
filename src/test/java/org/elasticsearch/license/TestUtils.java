/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license;

import org.apache.commons.io.FileUtils;
import org.elasticsearch.license.core.DateUtils;
import org.elasticsearch.license.core.ESLicenses;
import org.elasticsearch.license.core.LicenseBuilders;
import org.elasticsearch.license.licensor.tools.LicenseGeneratorTool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.ParseException;
import java.util.Map;

import static org.junit.Assert.assertTrue;

public class TestUtils {


    public static String generateESLicenses(Map<ESLicenses.FeatureType, FeatureAttributes> featureAttributes) {
        StringBuilder licenseBuilder = new StringBuilder();
        int size = featureAttributes.values().size();
        int i = 0;
        for (FeatureAttributes attributes : featureAttributes.values()) {
            licenseBuilder.append("{\n" +
                    "    \"type\" : \"" + attributes.type + "\",\n" +
                    "    \"subscription_type\" : \"" + attributes.subscriptionType + "\",\n" +
                    "    \"issued_to\" : \"" + attributes.issuedTo + "\",\n" +
                    "    \"issuer\" : \"" + attributes.issuer + "\",\n" +
                    "    \"issue_date\" : \"" + attributes.issueDate + "\",\n" +
                    "    \"expiry_date\" : \"" + attributes.expiryDate + "\",\n" +
                    "    \"feature\" : \"" + attributes.featureType + "\",\n" +
                    "    \"max_nodes\" : " + attributes.maxNodes +
                    "}");
            if (++i < size) {
                licenseBuilder.append(",\n");
            }
        }
        return "{\n" +
                "  \"licenses\" : [" +
                licenseBuilder.toString() +
                "]\n" +
                "}";

    }

    public static String runLicenseGenerationTool(String licenseInput, String pubKeyPath, String priKeyPath) throws IOException {
        String args[] = new String[6];
        args[0] = "--license";
        args[1] = licenseInput;
        args[2] = "--publicKeyPath";
        args[3] = pubKeyPath;
        args[4] = "--privateKeyPath";
        args[5] = priKeyPath;

        return runLicenseGenerationTool(args);
    }

    public static String runLicenseGenerationTool(String[] args) throws IOException {
        File temp = File.createTempFile("temp", ".out");
        temp.deleteOnExit();
        try (FileOutputStream outputStream = new FileOutputStream(temp)) {
            LicenseGeneratorTool.run(args, outputStream);
        }
        return FileUtils.readFileToString(temp);
    }

    public static void verifyESLicenses(ESLicenses esLicenses, Map<ESLicenses.FeatureType, FeatureAttributes> featureAttributes) throws ParseException {
        assertTrue("Number of feature licenses should be " + featureAttributes.size(), esLicenses.features().size() == featureAttributes.size());
        for (Map.Entry<ESLicenses.FeatureType, FeatureAttributes> featureAttrTuple : featureAttributes.entrySet()) {
            ESLicenses.FeatureType featureType = featureAttrTuple.getKey();
            FeatureAttributes attributes = featureAttrTuple.getValue();
            final ESLicenses.ESLicense esLicense = esLicenses.get(featureType);
            assertTrue("license for " + featureType.string() + " should be present", esLicense != null);
            assertTrue("expected value for issuedTo was: " + attributes.issuedTo + " but got: " + esLicense.issuedTo(), esLicense.issuedTo().equals(attributes.issuedTo));
            assertTrue("expected value for type was: " + attributes.type + " but got: " + esLicense.type().string(), esLicense.type().string().equals(attributes.type));
            assertTrue("expected value for subscriptionType was: " + attributes.subscriptionType + " but got: " + esLicense.subscriptionType().string(), esLicense.subscriptionType().string().equals(attributes.subscriptionType));
            assertTrue("expected value for feature was: " + attributes.featureType + " but got: " + esLicense.feature().string(), esLicense.feature().string().equals(attributes.featureType));
            assertTrue("expected value for issueDate was: " + DateUtils.longFromDateString(attributes.issueDate) + " but got: " + esLicense.issueDate(), esLicense.issueDate() == DateUtils.longFromDateString(attributes.issueDate));
            assertTrue("expected value for expiryDate: " + DateUtils.longExpiryDateFromString(attributes.expiryDate) + " but got: " + esLicense.expiryDate(), esLicense.expiryDate() == DateUtils.longExpiryDateFromString(attributes.expiryDate));
            assertTrue("expected value for maxNodes: " + attributes.maxNodes + " but got: " + esLicense.maxNodes(), esLicense.maxNodes() == attributes.maxNodes);

            assertTrue("generated licenses should have non-null uid field", esLicense.uid() != null);
            assertTrue("generated licenses should have non-null signature field", esLicense.signature() != null);
        }
    }

    //TODO: convert to asserts
    public static void isSame(ESLicenses firstLicenses, ESLicenses secondLicenses) {

        // we do the build to make sure we weed out any expired licenses
        final ESLicenses licenses1 = LicenseBuilders.licensesBuilder().licenses(firstLicenses).build();
        final ESLicenses licenses2 = LicenseBuilders.licensesBuilder().licenses(secondLicenses).build();

        // check if the effective licenses have the same feature set
        assertTrue("Both licenses should have the same number of features",licenses1.features().equals(licenses2.features()));


        // for every feature license, check if all the attributes are the same
        for (ESLicenses.FeatureType featureType : licenses1.features()) {
            ESLicenses.ESLicense license1 = licenses1.get(featureType);
            ESLicenses.ESLicense license2 = licenses2.get(featureType);

            isSame(license1, license2);

        }
    }

    public static void isSame(ESLicenses.ESLicense license1, ESLicenses.ESLicense license2) {

        assertTrue("Should have same uid; got: " + license1.uid() + " and " + license2.uid(), license1.uid().equals(license2.uid()));
        assertTrue("Should have same feature; got: " + license1.feature().string() + " and " + license2.feature().string(), license1.feature().string().equals(license2.feature().string()));
        assertTrue("Should have same subscriptType; got: " + license1.subscriptionType().string() + " and " + license2.subscriptionType().string(), license1.subscriptionType().string().equals(license2.subscriptionType().string()));
        assertTrue("Should have same type; got: " + license1.type().string() + " and " + license2.type().string(), license1.type().string().equals(license2.type().string()));
        assertTrue("Should have same issuedTo; got: " + license1.issuedTo() + " and " + license2.issuedTo(), license1.issuedTo().equals(license2.issuedTo()));
        assertTrue("Should have same signature; got: " + license1.signature() + " and " + license2.signature(), license1.signature().equals(license2.signature()));
        assertTrue("Should have same expiryDate; got: " + license1.expiryDate() + " and " + license2.expiryDate(), license1.expiryDate() == license2.expiryDate());
        assertTrue("Should have same issueDate; got: " + license1.issueDate() + " and " + license2.issueDate(), license1.issueDate() == license2.issueDate());
        assertTrue("Should have same maxNodes; got: " + license1.maxNodes() + " and " + license2.maxNodes(), license1.maxNodes() == license2.maxNodes());
    }

    public static class FeatureAttributes {

        public final String featureType;
        public final String type;
        public final String subscriptionType;
        public final String issuedTo;
        public final int maxNodes;
        public final String issueDate;
        public final String expiryDate;
        public final String issuer;

        public FeatureAttributes(String featureType, String type, String subscriptionType, String issuedTo, String issuer, int maxNodes, String issueDateStr, String expiryDateStr) throws ParseException {
            this.featureType = featureType;
            this.type = type;
            this.subscriptionType = subscriptionType;
            this.issuedTo = issuedTo;
            this.issuer = issuer;
            this.maxNodes = maxNodes;
            this.issueDate = issueDateStr;
            this.expiryDate = expiryDateStr;
        }
    }
}
