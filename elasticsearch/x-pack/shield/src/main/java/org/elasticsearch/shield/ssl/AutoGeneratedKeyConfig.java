/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.ssl;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.util.set.Sets;
import org.elasticsearch.env.Environment;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 */
class AutoGeneratedKeyConfig extends KeyConfig {

    private static final char[] PASSWORD = "changeme".toCharArray();

    private final Set<InetAddress> certificateAddresses = new HashSet<>();
    private final X509ExtendedKeyManager[] keyManagers;
    private final X509ExtendedTrustManager[] trustManagers;
    private final KeyPair keyPair;
    private final Certificate caCert;
    private final Exception failure;

    private boolean certGenerated = false;

    AutoGeneratedKeyConfig(boolean includeSystem) {
        super(includeSystem, false);
        Exception thrown = null;
        X509ExtendedTrustManager trustManager;
        Certificate caCert = null;
        KeyPair keyPair = null;
        try {
            keyPair = CertUtils.generateKeyPair();
            caCert = readCACert();
            X509ExtendedTrustManager[] managers = CertUtils.trustManagers(new Certificate[] { caCert });
            trustManager = managers[0];
        } catch (Exception e) {
            thrown = e;
            trustManager = new EmptyX509TrustManager();
        }

        this.failure = thrown;
        this.caCert = caCert;
        this.keyPair = keyPair;
        this.keyManagers = new X509ExtendedKeyManager[] { new ReloadableX509KeyManager(new EmptyX509KeyManager(), null) };
        this.trustManagers = new X509ExtendedTrustManager[] { new ReloadableTrustManager(trustManager, null) };
    }

    @Override
    X509ExtendedKeyManager[] loadKeyManagers(@Nullable Environment environment) {
        return keyManagers;
    }

    @Override
    X509ExtendedTrustManager[] nonSystemTrustManagers(@Nullable Environment environment) {
        return trustManagers;
    }

    @Override
    void validate() {
        if (failure != null) {
            throw new ElasticsearchException("failed to auto generate keypair and read CA cert", failure);
        }
    }

    @Override
    List<Path> filesToMonitor(@Nullable Environment environment) {
        return Collections.emptyList();
    }

    @Override
    public String toString() {
        return "AutoGeneratedKeyConfig";
    }

    synchronized void generateCertIfNecessary(boolean resolveHostnames, String nodeName, Set<InetAddress> addresses, ESLogger logger)
            throws Exception {
        if (failure != null) {
            throw new ElasticsearchException("failed to auto generate keypair and read CA cert", failure);
        }

        // we shouldn't regenerate if we have no new addresses
        if (certGenerated && Sets.difference(addresses, certificateAddresses).isEmpty()) {
            return;
        }

        this.certificateAddresses.addAll(addresses);
        final PrivateKey caPrivateKey = readCAPrivateKey();
        final X509Certificate signedCert =
                CertUtils.generateSignedCertificate(resolveHostnames, nodeName, certificateAddresses, keyPair, caCert, caPrivateKey);
        Certificate[] certChain = new Certificate[] { signedCert, caCert };
        X509ExtendedKeyManager[] keyManagers = CertUtils.keyManagers(certChain, keyPair.getPrivate(), PASSWORD);
        X509ExtendedTrustManager[] trustManagers = CertUtils.trustManagers(certChain);
        ((ReloadableX509KeyManager) this.keyManagers[0]).setKeyManager(keyManagers[0]);
        ((ReloadableTrustManager) this.trustManagers[0]).setTrustManager(trustManagers[0]);
        this.certGenerated = true;
        logMessages(signedCert, logger);
    }

    static Certificate readCACert() throws Exception {
        try (InputStream inputStream = AutoGeneratedKeyConfig.class.getResourceAsStream("/cacert.pem");
             Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            List<Certificate> certificateList = new ArrayList<>(1);
            CertUtils.readCertificates(reader, certificateList, certificateFactory);
            if (certificateList.size() != 1) {
                throw new IllegalStateException("expected [1] default CA certificate but found [" + certificateList.size() + "]");
            }
            return certificateList.get(0);
        }
    }

    static PrivateKey readCAPrivateKey() throws Exception {
        try (InputStream inputStream = AutoGeneratedKeyConfig.class.getResourceAsStream("/cakey.pem");
             Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            return CertUtils.readPrivateKey(reader, PASSWORD);
        }
    }

    static void logMessages(X509Certificate signedCert, ESLogger logger) {
        logger.info("auto generated a X.509 certificate and private/public key pair for SSL use. this should never be used in production " +
                "as the signing certificate authority is the same for every installation of X-Pack.{}generated certificate:{}{}",
                System.lineSeparator(), System.lineSeparator(), signedCert.toString());
    }

    private static class EmptyX509KeyManager extends X509ExtendedKeyManager {

        @Override
        public String[] getClientAliases(String s, Principal[] principals) {
            return null;
        }

        @Override
        public String chooseClientAlias(String[] strings, Principal[] principals, Socket socket) {
            return null;
        }

        @Override
        public String[] getServerAliases(String s, Principal[] principals) {
            return null;
        }

        @Override
        public String chooseServerAlias(String s, Principal[] principals, Socket socket) {
            return null;
        }

        @Override
        public X509Certificate[] getCertificateChain(String s) {
            return null;
        }

        @Override
        public PrivateKey getPrivateKey(String s) {
            return null;
        }
    }

    private static class EmptyX509TrustManager extends X509ExtendedTrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] x509Certificates, String s, Socket socket) throws CertificateException {
            throw new CertificateException("trust nothing");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] x509Certificates, String s, Socket socket) throws CertificateException {
            throw new CertificateException("trust nothing");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] x509Certificates, String s, SSLEngine sslEngine) throws CertificateException {
            throw new CertificateException("trust nothing");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] x509Certificates, String s, SSLEngine sslEngine) throws CertificateException {
            throw new CertificateException("trust nothing");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
            throw new CertificateException("trust nothing");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
            throw new CertificateException("trust nothing");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return null;
        }
    }
}
