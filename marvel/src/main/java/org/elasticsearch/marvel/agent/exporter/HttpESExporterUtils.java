/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.marvel.agent.exporter;

import org.elasticsearch.Version;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.network.NetworkAddress;
import org.elasticsearch.common.transport.BoundTransportAddress;
import org.elasticsearch.common.transport.InetSocketTransportAddress;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HttpESExporterUtils {

    public static final String MARVEL_TEMPLATE_FILE = "/marvel_index_template.json";
    static final String MARVEL_VERSION_FIELD = "marvel_version";
    static final String VERSION_FIELD = "number";

    public static String[] extractHostsFromAddress(BoundTransportAddress boundAddress, ESLogger logger) {
        if (boundAddress == null || boundAddress.boundAddress() == null) {
            logger.debug("local http server is not yet started. can't connect");
            return null;
        }

        if (boundAddress.boundAddress().uniqueAddressTypeId() != 1) {
            logger.error("local node is not bound via the http transport. can't connect");
            return null;
        }
        InetSocketTransportAddress address = (InetSocketTransportAddress) boundAddress.boundAddress();
        InetSocketAddress inetSocketAddress = address.address();
        InetAddress inetAddress = inetSocketAddress.getAddress();
        if (inetAddress == null) {
            logger.error("failed to extract the ip address of current node.");
            return null;
        }

        return new String[]{ NetworkAddress.formatAddress(inetSocketAddress) };
    }

    public static URL parseHostWithPath(String host, String path) throws URISyntaxException, MalformedURLException {

        if (!host.contains("://")) {
            // prefix with http
            host = "http://" + host;
        }
        if (!host.endsWith("/")) {
            // make sure we can safely resolves sub paths and not replace parent folders
            host = host + "/";
        }

        URL hostUrl = new URL(host);

        if (hostUrl.getPort() == -1) {
            // url has no port, default to 9200 - sadly we need to rebuild..
            StringBuilder newUrl = new StringBuilder(hostUrl.getProtocol() + "://");
            if (hostUrl.getUserInfo() != null) {
                newUrl.append(hostUrl.getUserInfo()).append("@");
            }
            newUrl.append(hostUrl.getHost()).append(":9200").append(hostUrl.toURI().getPath());

            hostUrl = new URL(newUrl.toString());

        }
        return new URL(hostUrl, path);

    }

    /**
     * Loads the default Marvel template
     */
    public static byte[] loadDefaultTemplate() {
        try (InputStream is = HttpESExporterUtils.class.getResourceAsStream(MARVEL_TEMPLATE_FILE)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Streams.copy(is, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("unable to load marvel template", e);
        }
    }

    /**
     * Extract &amp; parse the version contained in the given template
     */
    public static Version parseTemplateVersion(byte[] template) {
        return parseTemplateVersion(new String(template, Charset.forName("UTF-8")));
    }

    /**
     * Extract &amp; parse the version contained in the given template
     */
    public static Version parseTemplateVersion(String template) {
        return parseVersion(MARVEL_VERSION_FIELD, template);
    }

    /**
     * Extract &amp; parse the elasticsearch version, as returned by the REST API
     */
    public static Version parseElasticsearchVersion(byte[] template) {
        return parseVersion(VERSION_FIELD, new String(template, Charset.forName("UTF-8")));
    }

    static Version parseVersion(String field, String template) {
        Pattern pattern = Pattern.compile(field + "\"\\s*:\\s*\"?([0-9a-zA-Z\\.\\-]+)\"?");
        Matcher matcher = pattern.matcher(template);
        if (matcher.find()) {
            String parsedVersion = matcher.group(1);
            if (Strings.hasText(parsedVersion)) {
                return Version.fromString(parsedVersion);
            }
        }
        return null;
    }

    private static final String userInfoChars = "\\w-\\._~!$&\\'\\(\\)*+,;=%";
    private static Pattern urlPwdSanitizer = Pattern.compile("([" + userInfoChars + "]+?):[" + userInfoChars + "]+?@");

    public static String santizeUrlPwds(Object text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = urlPwdSanitizer.matcher(text.toString());
        return matcher.replaceAll("$1:XXXXXX@");
    }
}
