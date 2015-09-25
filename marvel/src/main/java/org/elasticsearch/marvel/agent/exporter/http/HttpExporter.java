/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.marvel.agent.exporter.http;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.Version;
import org.elasticsearch.common.Base64;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsException;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.env.Environment;
import org.elasticsearch.marvel.agent.exporter.ExportBulk;
import org.elasticsearch.marvel.agent.exporter.Exporter;
import org.elasticsearch.marvel.agent.exporter.MarvelDoc;
import org.elasticsearch.marvel.agent.renderer.Renderer;
import org.elasticsearch.marvel.agent.renderer.RendererRegistry;
import org.elasticsearch.marvel.agent.settings.MarvelSettings;
import org.elasticsearch.marvel.shield.MarvelSettingsFilter;

import javax.net.ssl.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

/**
 *
 */
public class HttpExporter extends Exporter {

    public static final String TYPE = "http";

    public static final String HOST_SETTING = "host";
    public static final String CONNECTION_TIMEOUT_SETTING = "connection.timeout";
    public static final String CONNECTION_READ_TIMEOUT_SETTING = "connection.read_timeout";
    public static final String AUTH_USERNAME_SETTING = "auth.username";
    public static final String AUTH_PASSWORD_SETTING = "auth.password";

    // es level timeout used when checking and writing templates (used to speed up tests)
    public static final String TEMPLATE_CHECK_TIMEOUT_SETTING = "index.template.master_timeout";

    public static final String SSL_SETTING = "ssl";
    public static final String SSL_PROTOCOL_SETTING = SSL_SETTING + ".protocol";
    public static final String SSL_TRUSTSTORE_SETTING = SSL_SETTING + ".truststore.path";
    public static final String SSL_TRUSTSTORE_PASSWORD_SETTING = SSL_SETTING + ".truststore.password";
    public static final String SSL_TRUSTSTORE_ALGORITHM_SETTING = SSL_SETTING + ".truststore.algorithm";
    public static final String SSL_HOSTNAME_VERIFICATION_SETTING = SSL_SETTING + ".hostname_verification";

    /** Minimum supported version of the remote marvel cluster **/
    public static final Version MIN_SUPPORTED_CLUSTER_VERSION = Version.V_2_0_0_beta2;

    volatile String[] hosts;
    final TimeValue connectionTimeout;
    final TimeValue connectionReadTimeout;
    final BasicAuth auth;

    /** https support * */
    final SSLSocketFactory sslSocketFactory;
    final boolean hostnameVerification;

    final Environment env;
    final RendererRegistry rendererRegistry;

    final @Nullable TimeValue templateCheckTimeout;

    volatile boolean checkedAndUploadedIndexTemplate = false;
    volatile boolean supportedClusterVersion = false;


    /** Version of the built-in template **/
    final Version templateVersion;

    final ConnectionKeepAliveWorker keepAliveWorker;
    Thread keepAliveThread;

    public HttpExporter(Exporter.Config config, Environment env, RendererRegistry rendererRegistry) {

        super(TYPE, config);
        this.env = env;
        this.rendererRegistry = rendererRegistry;

        hosts = config.settings().getAsArray(HOST_SETTING, Strings.EMPTY_ARRAY);
        if (hosts.length == 0) {
            throw new SettingsException("missing required setting [" + settingFQN(HOST_SETTING) + "]");
        }
        validateHosts(hosts);

        auth = resolveAuth(config.settings());

        connectionTimeout = config.settings().getAsTime(CONNECTION_TIMEOUT_SETTING, TimeValue.timeValueMillis(6000));
        connectionReadTimeout = config.settings().getAsTime(CONNECTION_READ_TIMEOUT_SETTING, TimeValue.timeValueMillis(connectionTimeout.millis() * 10));

        // HORRIBLE!!! We can't use settings.getAsTime(..) !!!
        // WE MUST FIX THIS IN CORE...
        // TimeValue SHOULD NOT SELECTIVELY CHOOSE WHAT FIELDS TO PARSE BASED ON THEIR NAMES!!!!
        String templateCheckTimeoutValue = config.settings().get(TEMPLATE_CHECK_TIMEOUT_SETTING, null);
        templateCheckTimeout = TimeValue.parseTimeValue(templateCheckTimeoutValue, null, settingFQN(TEMPLATE_CHECK_TIMEOUT_SETTING));

        keepAliveWorker = new ConnectionKeepAliveWorker();

        sslSocketFactory = createSSLSocketFactory(config.settings().getAsSettings(SSL_SETTING));
        hostnameVerification = config.settings().getAsBoolean(SSL_HOSTNAME_VERIFICATION_SETTING, true);

        // Checks that the built-in template is versioned
        templateVersion = HttpExporterUtils.parseTemplateVersion(HttpExporterUtils.loadDefaultTemplate());
        if (templateVersion == null) {
            throw new IllegalStateException("unable to find built-in template version");
        }

        logger.debug("initialized with hosts [{}], index prefix [{}], index resolver [{}], template version [{}]",
                Strings.arrayToCommaDelimitedString(hosts),
                MarvelSettings.MARVEL_INDICES_PREFIX, indexNameResolver, templateVersion);
    }

    @Override
    public ExportBulk openBulk() {
        HttpURLConnection connection = openExportingConnection();
        return connection != null ? new Bulk(connection) : null;
    }

    @Override
    public void close() {
        if (keepAliveThread != null && keepAliveThread.isAlive()) {
            keepAliveWorker.closed = true;
            keepAliveThread.interrupt();
            try {
                keepAliveThread.join(6000);
            } catch (InterruptedException e) {
                // don't care.
            }
        }
    }

    private HttpURLConnection openExportingConnection() {
        logger.trace("setting up an export connection");
        String queryString = "";
        if (bulkTimeout != null) {
            queryString = "?master_timeout=" + bulkTimeout;
        }
        HttpURLConnection conn = openAndValidateConnection("POST", "/_bulk" + queryString, XContentType.SMILE.restContentType());
        if (conn != null && (keepAliveThread == null || !keepAliveThread.isAlive())) {
            // start keep alive upon successful connection if not there.
            initKeepAliveThread();
        }
        return conn;
    }

    private void render(MarvelDoc marvelDoc, OutputStream out) throws IOException {
        final XContentType xContentType = XContentType.SMILE;

        // Get the appropriate renderer in order to render the MarvelDoc
        Renderer renderer = rendererRegistry.renderer(marvelDoc.type());
        if (renderer == null) {
            logger.warn("unable to render marvel document of type [{}]. no renderer found in registry", marvelDoc.type());
            return;
        }

        try (XContentBuilder builder = new XContentBuilder(xContentType.xContent(), out)) {

            // Builds the bulk action metadata line
            builder.startObject();
            builder.startObject("index");

            // we need the index to be based on the document timestamp
            builder.field("_index", indexNameResolver.resolve(marvelDoc));

            if (marvelDoc.type() != null) {
                builder.field("_type", marvelDoc.type());
            }
            if (marvelDoc.id() != null) {
                builder.field("_id", marvelDoc.id());
            }
            builder.endObject();
            builder.endObject();

            // Adds action metadata line bulk separator
            builder.flush(); // Flush is needed here because the separator is written directly in the builder's stream
            builder.stream().write(builder.contentType().xContent().streamSeparator());

            // Render the MarvelDoc
            renderer.render(marvelDoc, xContentType, out);

            // Adds final bulk separator
            builder.flush();
            builder.stream().write(builder.contentType().xContent().streamSeparator());
        }
    }

    @SuppressWarnings("unchecked")
    private void sendCloseExportingConnection(HttpURLConnection conn) throws IOException {
        logger.trace("sending content");
        OutputStream os = conn.getOutputStream();
        os.close();
        if (conn.getResponseCode() != 200) {
            logConnectionError("remote target didn't respond with 200 OK", conn);
            return;
        }

        InputStream inputStream = conn.getInputStream();
        try (XContentParser parser = XContentType.SMILE.xContent().createParser(inputStream)) {
            Map<String, Object> response = parser.map();
            if (response.get("items") != null) {
                ArrayList<Object> list = (ArrayList<Object>) response.get("items");
                for (Object itemObject : list) {
                    Map<String, Object> actions = (Map<String, Object>) itemObject;
                    for (String actionKey : actions.keySet()) {
                        Map<String, Object> action = (Map<String, Object>) actions.get(actionKey);
                        if (action.get("error") != null) {
                            logger.error("{} failure (index:[{}] type: [{}]): {}", actionKey, action.get("_index"), action.get("_type"), action.get("error"));
                        }
                    }
                }
            }
        }
    }

    /**
     * open a connection to any host, validating it has the template installed if needed
     *
     * @return a url connection to the selected host or null if no current host is available.
     */
    private HttpURLConnection openAndValidateConnection(String method, String path, String contentType) {
        // out of for to move faulty hosts to the end
        int hostIndex = 0;
        try {
            for (; hostIndex < hosts.length; hostIndex++) {
                String host = hosts[hostIndex];
                if (!supportedClusterVersion) {
                    try {
                        Version remoteVersion = loadRemoteClusterVersion(host);
                        if (remoteVersion == null) {
                            logger.warn("unable to check remote cluster version: no version found on host [" + host + "]");
                            continue;
                        }
                        supportedClusterVersion = remoteVersion.onOrAfter(MIN_SUPPORTED_CLUSTER_VERSION);
                        if (!supportedClusterVersion) {
                            logger.error("remote cluster version [" + remoteVersion + "] is not supported, please use a cluster with minimum version [" + MIN_SUPPORTED_CLUSTER_VERSION + "]");
                            continue;
                        }
                    } catch (ElasticsearchException e) {
                        logger.error("exception when checking remote cluster version on host [{}]", e, host);
                        continue;
                    }
                }

                if (!checkedAndUploadedIndexTemplate) {
                    // check templates first on the host
                    checkedAndUploadedIndexTemplate = checkAndUploadIndexTemplate(host);
                    if (!checkedAndUploadedIndexTemplate) {
                        continue;
                    }
                }
                HttpURLConnection connection = openConnection(host, method, path, contentType);
                if (connection != null) {
                    return connection;
                }
                // failed hosts - reset template & cluster versions check, someone may have restarted the target cluster and deleted
                // it's data folder. be safe.
                checkedAndUploadedIndexTemplate = false;
                supportedClusterVersion = false;
            }
        } finally {
            if (hostIndex > 0 && hostIndex < hosts.length) {
                logger.debug("moving [{}] failed hosts to the end of the list", hostIndex);
                String[] newHosts = new String[hosts.length];
                System.arraycopy(hosts, hostIndex, newHosts, 0, hosts.length - hostIndex);
                System.arraycopy(hosts, 0, newHosts, hosts.length - hostIndex, hostIndex);
                hosts = newHosts;
                logger.debug("preferred target host is now [{}]", hosts[0]);
            }
        }

        logger.error("could not connect to any configured elasticsearch instances [{}]", Strings.arrayToCommaDelimitedString(hosts));

        return null;

    }

    /** open a connection to the given hosts, returning null when not successful * */
    private HttpURLConnection openConnection(String host, String method, String path, @Nullable String contentType) {
        try {
            final URL url = HttpExporterUtils.parseHostWithPath(host, path);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            if (conn instanceof HttpsURLConnection && sslSocketFactory != null) {
                HttpsURLConnection httpsConn = (HttpsURLConnection) conn;
                httpsConn.setSSLSocketFactory(sslSocketFactory);
                if (!hostnameVerification) {
                    httpsConn.setHostnameVerifier(TrustAllHostnameVerifier.INSTANCE);
                }
            }

            conn.setRequestMethod(method);
            conn.setConnectTimeout((int) connectionTimeout.getMillis());
            conn.setReadTimeout((int) connectionReadTimeout.getMillis());
            if (contentType != null) {
                conn.setRequestProperty("Content-Type", contentType);
            }
            if (auth != null) {
                auth.apply(conn);
            }
            conn.setUseCaches(false);
            if (method.equalsIgnoreCase("POST") || method.equalsIgnoreCase("PUT")) {
                conn.setDoOutput(true);
            }
            conn.connect();

            return conn;
        } catch (URISyntaxException e) {
            logErrorBasedOnLevel(e, "error parsing host [{}]", host);
        } catch (IOException e) {
            logErrorBasedOnLevel(e, "error connecting to [{}]", host);
        }
        return null;
    }

    private void logErrorBasedOnLevel(Throwable t, String msg, Object... params) {
        logger.error(msg + " [" + t.getMessage() + "]", params);
        if (logger.isDebugEnabled()) {
            logger.debug(msg + ". full error details:\n[{}]", params, ExceptionsHelper.detailedMessage(t));
        }
    }

    /**
     * Get the version of the remote Marvel cluster
     */
    Version loadRemoteClusterVersion(final String host) {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(host, "GET", "/", null);
            if (connection == null) {
                throw new ElasticsearchException("unable to check remote cluster version: no available connection for host [" + host + "]");
            }

            try (InputStream is = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                Streams.copy(is, out);
                return HttpExporterUtils.parseElasticsearchVersion(out.toByteArray());
            }
        } catch (IOException e) {
            throw new ElasticsearchException("failed to verify the remote cluster version on host [" + host + "]:\n" + e.getMessage());
        } finally {
            if (connection != null) {
                try {
                    connection.getInputStream().close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Checks if the index templates already exist and if not uploads it
     * Any critical error that should prevent data exporting is communicated via an exception.
     *
     * @return true if template exists or was uploaded successfully.
     */
    private boolean checkAndUploadIndexTemplate(final String host) {
        boolean updateTemplate = true;

        String url = "_template/marvel";
        if (templateCheckTimeout != null) {
            url += "?timeout=" + templateCheckTimeout;
        }

        HttpURLConnection connection = null;
        try {
            logger.debug("checking if marvel template exists on the marvel cluster");
            connection = openConnection(host, "GET", url, null);
            if (connection == null) {
                logger.debug("no available connection to check marvel template existence");
                return false;
            }

            // 200 means that the template has been found, 404 otherwise
            if (connection.getResponseCode() == 200) {
                logger.debug("marvel template found, checking its version");

                byte[] remoteTemplate;
                try (InputStream is = connection.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    Streams.copy(is, out);
                    remoteTemplate = out.toByteArray();
                }

                if ((remoteTemplate == null) || (remoteTemplate.length == 0)) {
                    logger.error("unable to load remote marvel template on host [{}]", host);
                    return false;
                }

                Version remoteVersion = HttpExporterUtils.parseTemplateVersion(remoteTemplate);
                logger.debug("detected existing remote template in version [{}] on host [{}]", remoteVersion, host);

                if (remoteVersion == null) {
                    logger.warn("marvel template version cannot be found: template will be updated to version [{}]", templateVersion);
                } else {

                    if (remoteVersion.before(MIN_SUPPORTED_TEMPLATE_VERSION)) {
                        logger.error("marvel template version [{}] is below the minimum compatible version [{}] on host [{}]: "
                                        + "please manually update the marvel template to a more recent version"
                                        + "and delete the current active marvel index (don't forget to back up it first if needed)",
                                remoteVersion, MIN_SUPPORTED_TEMPLATE_VERSION, host);
                        return false;
                    }

                    // Compares the remote template version with the built-in template
                    if (templateVersion.after(remoteVersion)) {
                        logger.info("marvel template version will be updated to a newer version [remote:{}, built-in:{}]", remoteVersion, templateVersion);
                        updateTemplate = true;

                    } else if (templateVersion.equals(remoteVersion)) {
                        logger.debug("marvel template version is up-to-date [remote:{}, built-in:{}]", remoteVersion, templateVersion);
                        // Always update a snapshot version
                        updateTemplate = templateVersion.snapshot();
                    } else {
                        logger.debug("marvel template version is newer than the one required by the marvel agent [remote:{}, built-in:{}]", remoteVersion, templateVersion);
                        updateTemplate = false;
                    }
                }
            }
        } catch (IOException e) {
            logger.error("failed to verify the marvel template to [{}]:\n{}", host, e.getMessage());
            return false;
        } finally {
            if (connection != null) {
                try {
                    connection.getInputStream().close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }

        if (updateTemplate) {
            try {
                connection = openConnection(host, "PUT", url, XContentType.JSON.restContentType());

                if (connection == null) {
                    logger.debug("no available connection to update marvel template");
                    return false;
                }

                logger.debug("loading marvel pre-configured template");
                byte[] template = HttpExporterUtils.loadDefaultTemplate();

                // Uploads the template and closes the outputstream
                Streams.copy(template, connection.getOutputStream());

                if (!(connection.getResponseCode() == 200 || connection.getResponseCode() == 201)) {
                    logConnectionError("error adding the marvel template to [" + host + "]", connection);
                    return false;
                }

                logger.info("marvel template updated to version [{}]", templateVersion);
            } catch (IOException e) {
                logger.error("failed to update the marvel template to [{}]:\n{}", host, e.getMessage());
                return false;

            } finally {
                if (connection != null) {
                    try {
                        connection.getInputStream().close();
                    } catch (IOException e) {
                        // Ignore
                    }
                }
            }
        }

        return updateTemplate;
    }

    private void logConnectionError(String msg, HttpURLConnection conn) {
        InputStream inputStream = conn.getErrorStream();
        String err = "";
        if (inputStream != null) {
            java.util.Scanner s = new java.util.Scanner(inputStream, "UTF-8").useDelimiter("\\A");
            err = s.hasNext() ? s.next() : "";
        }

        try {
            logger.error("{} response code [{} {}]. content: [{}]",
                    msg, conn.getResponseCode(),
                    conn.getResponseMessage(),
                    err);
        } catch (IOException e) {
            logger.error("{}. connection had an error while reporting the error. tough life.", msg);
        }
    }

    protected void initKeepAliveThread() {
        keepAliveThread = new Thread(keepAliveWorker, "marvel-exporter[" + config.name() + "][keep_alive]");
        keepAliveThread.setDaemon(true);
        keepAliveThread.start();
    }


    static private void validateHosts(String[] hosts) {
        for (String host : hosts) {
            try {
                HttpExporterUtils.parseHostWithPath(host, "");
            } catch (URISyntaxException e) {
                throw new SettingsException("[marvel.agent.exporter] invalid host: [" + host + "]." +
                        " error: [" + e.getMessage() + "]");
            } catch (MalformedURLException e) {
                throw new SettingsException("[marvel.agent.exporter] invalid host: [" + host + "]." +
                        " error: [" + e.getMessage() + "]");
            }
        }
    }

    /** SSL Initialization * */
    public SSLSocketFactory createSSLSocketFactory(Settings settings) {
        if (settings.names().isEmpty()) {
            logger.trace("no ssl context configured");
            return null;
        }
        SSLContext sslContext;
        // Initialize sslContext
        try {
            String protocol = settings.get(SSL_PROTOCOL_SETTING, "TLS");
            String trustStore = settings.get(SSL_TRUSTSTORE_SETTING, System.getProperty("javax.net.ssl.trustStore"));
            String trustStorePassword = settings.get(SSL_TRUSTSTORE_PASSWORD_SETTING, System.getProperty("javax.net.ssl.trustStorePassword"));
            String trustStoreAlgorithm = settings.get(SSL_TRUSTSTORE_ALGORITHM_SETTING, System.getProperty("ssl.TrustManagerFactory.algorithm"));

            if (trustStore == null) {
                throw new SettingsException("missing required setting [" + SSL_TRUSTSTORE_SETTING + "]");
            }

            if (trustStoreAlgorithm == null) {
                trustStoreAlgorithm = TrustManagerFactory.getDefaultAlgorithm();
            }

            logger.debug("using ssl trust store [{}] with algorithm [{}]", trustStore, trustStoreAlgorithm);

            Path trustStorePath = env.configFile().resolve(trustStore);
            if (!Files.exists(trustStorePath)) {
                throw new SettingsException("could not find trust store file [" + trustStorePath + "]");
            }

            TrustManager[] trustManagers;
            try (InputStream trustStoreStream = Files.newInputStream(trustStorePath)) {
                // Load TrustStore
                KeyStore ks = KeyStore.getInstance("jks");
                ks.load(trustStoreStream, trustStorePassword == null ? null : trustStorePassword.toCharArray());

                // Initialize a trust manager factory with the trusted store
                TrustManagerFactory trustFactory = TrustManagerFactory.getInstance(trustStoreAlgorithm);
                trustFactory.init(ks);

                // Retrieve the trust managers from the factory
                trustManagers = trustFactory.getTrustManagers();
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize a TrustManagerFactory", e);
            }

            sslContext = SSLContext.getInstance(protocol);
            sslContext.init(null, trustManagers, null);

        } catch (Exception e) {
            throw new ElasticsearchException("failed to initialize ssl", e);
        }
        return sslContext.getSocketFactory();
    }

    BasicAuth resolveAuth(Settings setting) {
        String username = setting.get(AUTH_USERNAME_SETTING, null);
        String password = setting.get(AUTH_PASSWORD_SETTING, null);
        if (username == null && password == null) {
            return null;
        }
        if (username == null) {
            throw new SettingsException("invalid auth setting. missing [" + settingFQN(AUTH_USERNAME_SETTING) + "]");
        }
        return new BasicAuth(username, password);
    }

    /**
     * Trust all hostname verifier. This simply returns true to completely disable hostname verification
     */
    static class TrustAllHostnameVerifier implements HostnameVerifier {
        static final HostnameVerifier INSTANCE = new TrustAllHostnameVerifier();

        private TrustAllHostnameVerifier() {
        }

        @Override
        public boolean verify(String s, SSLSession sslSession) {
            return true;
        }
    }

    /**
     * Sadly we need to make sure we keep the connection open to the target ES a
     * Java's connection pooling closes connections if idle for 5sec.
     */
    class ConnectionKeepAliveWorker implements Runnable {
        volatile boolean closed = false;

        @Override
        public void run() {
            logger.trace("starting keep alive thread");
            while (!closed) {
                try {
                    Thread.sleep(1000);
                    if (closed) {
                        return;
                    }
                    String[] currentHosts = hosts;
                    if (currentHosts.length == 0) {
                        logger.trace("keep alive thread shutting down. no hosts defined");
                        return; // no hosts configured at the moment.
                    }
                    HttpURLConnection conn = openConnection(currentHosts[0], "GET", "", null);
                    if (conn == null) {
                        logger.trace("keep alive thread shutting down. failed to open connection to current host [{}]", currentHosts[0]);
                        return;
                    } else {
                        conn.getInputStream().close(); // close and release to connection pool.
                    }
                } catch (InterruptedException e) {
                    // ignore, if closed, good....
                } catch (Throwable t) {
                    logger.debug("error in keep alive thread, shutting down (will be restarted after a successful connection has been made) {}",
                            ExceptionsHelper.detailedMessage(t));
                    return;
                }
            }
        }
    }

    static class BasicAuth {

        String username;
        char[] password;

        public BasicAuth(String username, String password) {
            this.username = username;
            this.password = password != null ? password.toCharArray() : null;
        }

        void apply(HttpURLConnection connection) throws UnsupportedEncodingException {
            String userInfo = username + ":" + (password != null ? new String(password) : "");
            String basicAuth = "Basic " + Base64.encodeBytes(userInfo.getBytes("ISO-8859-1"));
            connection.setRequestProperty("Authorization", basicAuth);
        }
    }

    class Bulk extends ExportBulk {

        private HttpURLConnection connection;
        private OutputStream out;

        public Bulk(HttpURLConnection connection) {
            super(name());
            this.connection = connection;
        }

        @Override
        public Bulk add(Collection<MarvelDoc> docs) throws Exception {
            if (connection == null) {
                connection = openExportingConnection();
            }
            if ((docs != null) && (!docs.isEmpty())) {
                if (out == null) {
                    out = connection.getOutputStream();
                }

                // We need to use a buffer to render each Marvel document
                // because the renderer might close the outputstream (ex: XContentBuilder)
                try (BytesStreamOutput buffer = new BytesStreamOutput()) {
                    for (MarvelDoc marvelDoc : docs) {
                        render(marvelDoc, buffer);

                        // write the result to the connection
                        out.write(buffer.bytes().toBytes());
                    }
                }
            }
            return this;
        }

        @Override
        public void flush() throws IOException {
            if (connection != null) {
                flush(connection);
                connection = null;
            }
        }

        private void flush(HttpURLConnection connection) throws IOException {
            try {
                sendCloseExportingConnection(connection);
            } catch (IOException e) {
                logger.error("failed sending data to [{}]: {}", connection.getURL(), ExceptionsHelper.detailedMessage(e));
                throw e;
            }
        }
    }

    public static class Factory extends Exporter.Factory<HttpExporter> {

        private final Environment env;
        private final RendererRegistry rendererRegistry;

        @Inject
        public Factory(Environment env, RendererRegistry rendererRegistry) {
            super(TYPE, false);
            this.env = env;
            this.rendererRegistry = rendererRegistry;
        }

        @Override
        public HttpExporter create(Config config) {
            return new HttpExporter(config, env, rendererRegistry);
        }

        @Override
        public void filterOutSensitiveSettings(String prefix, MarvelSettingsFilter filter) {
            filter.filterOut(prefix + AUTH_PASSWORD_SETTING);
        }
    }
}
