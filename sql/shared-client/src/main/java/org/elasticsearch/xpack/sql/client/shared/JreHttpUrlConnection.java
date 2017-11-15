/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.client.shared;

import java.io.Closeable;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Base64;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

public class JreHttpUrlConnection implements Closeable {
    public static <R> R http(URL url, ConnectionConfiguration cfg, Function<JreHttpUrlConnection, R> handler) {
        try (JreHttpUrlConnection con = new JreHttpUrlConnection(url, cfg)) {
            return handler.apply(con);
        }
    }

    private boolean closed = false;
    final HttpURLConnection con;
    private final URL url;
    private static final String GZIP = "gzip";

    public JreHttpUrlConnection(URL url, ConnectionConfiguration cfg) throws ClientException {
        this.url = url;
        try {
            // due to the way the URL API is designed, the proxy needs to be passed in first
            Proxy p = cfg.proxyConfig().proxy();
            con = (HttpURLConnection) (p != null ? url.openConnection(p) : url.openConnection());
        } catch (IOException ex) {
            throw new ClientException(ex, "Cannot setup connection to %s (%s)", url, ex.getMessage());
        }

        // the rest of the connection setup
        setupConnection(cfg);
    }

    private void setupConnection(ConnectionConfiguration cfg) {
        // setup basic stuff first

        // timeouts
        con.setConnectTimeout((int) cfg.connectTimeout());
        con.setReadTimeout((int) cfg.networkTimeout());

        // disable content caching
        con.setAllowUserInteraction(false);
        con.setUseCaches(false);

        // HTTP params
        // HttpURL adds this header by default, HttpS does not
        // adding it here to be consistent
        con.setRequestProperty("Accept-Charset", "UTF-8");
        //con.setRequestProperty("Accept-Encoding", GZIP);

        setupSSL(cfg);
        setupBasicAuth(cfg);
    }

    private void setupSSL(ConnectionConfiguration cfg) {
        if (cfg.sslConfig().isEnabled()) {
            HttpsURLConnection https = (HttpsURLConnection) con;
            SSLSocketFactory factory = cfg.sslConfig().sslSocketFactory();
            AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
                  https.setSSLSocketFactory(factory);
                  return null;
            });
        }
    }

    private void setupBasicAuth(ConnectionConfiguration cfg) {
        if (StringUtils.hasText(cfg.authUser())) {
            String basicValue = cfg.authUser() + ":" + cfg.authPass();
            String encoded = StringUtils.asUTFString(Base64.getEncoder().encode(StringUtils.toUTF(basicValue)));
            con.setRequestProperty("Authorization", "Basic " + encoded);
        }
    }

    public boolean head() throws ClientException {
        try {
            con.setRequestMethod("HEAD");
            int responseCode = con.getResponseCode();
            return responseCode == HttpURLConnection.HTTP_OK;
        } catch (IOException ex) {
            throw new ClientException(ex, "Cannot HEAD address %s (%s)", url, ex.getMessage());
        }
    }

    public <R> R post(
                CheckedConsumer<DataOutput, IOException> doc,
                CheckedFunction<DataInput, R, IOException> parser,
                BiFunction<Integer, RemoteFailure, R> failureConverter
            ) throws ClientException {
        try {
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = con.getOutputStream()) {
                doc.accept(new DataOutputStream(out));
            }
            if (con.getResponseCode() < 300) {
                try (InputStream stream = getStream(con, con.getInputStream())) {
                    return parser.apply(new DataInputStream(stream));
                }
            }
            RemoteFailure failure;
            try (InputStream stream = getStream(con, con.getErrorStream())) {
                failure = RemoteFailure.parseFromResponse(stream);
            }
            return failureConverter.apply(con.getResponseCode(), failure);
        } catch (IOException ex) {
            throw new ClientException(ex, "Cannot POST address %s (%s)", url, ex.getMessage());
        }
    }

    private static InputStream getStream(HttpURLConnection con, InputStream stream) throws IOException {
        if (GZIP.equals(con.getContentEncoding())) {
            return new GZIPInputStream(stream);
        }
        return stream;
    }

    public void connect() {
        if (closed) {
            throw new ClientException("Connection cannot be reused");
        }
        try {
            con.connect();
        } catch (IOException ex) {
            throw new ClientException(ex, "Cannot open connection to %s (%s)", url, ex.getMessage());
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;

            // consume streams
            consumeStreams();
        }
    }

    public void disconnect() {
        try {
            connect();
        } finally {
            con.disconnect();
        }
    }

    // http://docs.oracle.com/javase/7/docs/technotes/guides/net/http-keepalive.html
    private void consumeStreams() {
        try (InputStream in = con.getInputStream()) {
            while (in != null && in.read() > -1) {
            }
        } catch (IOException ex) {
            // ignore
        } finally {
            try (InputStream ein = con.getErrorStream()) {
                while (ein != null && ein.read() > -1) {
                }
            } catch (IOException ex) {
                // keep on ignoring
            }
        }
    }
}
