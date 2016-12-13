/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.common.http;

import com.carrotsearch.randomizedtesting.generators.RandomStrings;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.util.Supplier;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.env.Environment;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.http.MockResponse;
import org.elasticsearch.test.http.MockWebServer;
import org.elasticsearch.test.junit.annotations.Network;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.elasticsearch.xpack.common.http.auth.HttpAuthRegistry;
import org.elasticsearch.xpack.common.http.auth.basic.BasicAuth;
import org.elasticsearch.xpack.common.http.auth.basic.BasicAuthFactory;
import org.elasticsearch.xpack.ssl.SSLService;
import org.elasticsearch.xpack.ssl.TestsSSLService;
import org.elasticsearch.xpack.ssl.VerificationMode;
import org.junit.After;
import org.junit.Before;

import javax.net.ssl.SSLContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.singletonMap;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;

public class HttpClientTests extends ESTestCase {

    private MockWebServer webServer = new MockWebServer();
    private HttpClient httpClient;
    private HttpAuthRegistry authRegistry;
    private Environment environment = new Environment(Settings.builder().put("path.home", createTempDir()).build());

    @Before
    public void init() throws Exception {
        authRegistry = new HttpAuthRegistry(singletonMap(BasicAuth.TYPE, new BasicAuthFactory(null)));
        webServer.start();
        httpClient = new HttpClient(Settings.EMPTY, authRegistry, new SSLService(environment.settings(), environment));
    }

    @After
    public void shutdown() throws Exception {
        webServer.close();
    }

    public void testBasics() throws Exception {
        int responseCode = randomIntBetween(200, 203);
        String body = randomAsciiOfLengthBetween(2, 8096);
        webServer.enqueue(new MockResponse().setResponseCode(responseCode).setBody(body));

        HttpRequest.Builder requestBuilder = HttpRequest.builder("localhost", webServer.getPort())
                .method(HttpMethod.POST)
                .path("/" + randomAsciiOfLength(5));

        String paramKey = randomAsciiOfLength(3);
        String paramValue = randomAsciiOfLength(3);
        requestBuilder.setParam(paramKey, paramValue);

        // Certain headers keys like via and host are illegal and the jdk http client ignores those, so lets
        // prepend all keys with `_`, so we don't run into a failure because randomly a restricted header was used:
        String headerKey = "_" + randomAsciiOfLength(3);
        String headerValue = randomAsciiOfLength(3);
        requestBuilder.setHeader(headerKey, headerValue);

        requestBuilder.body(randomAsciiOfLength(5));
        HttpRequest request = requestBuilder.build();

        HttpResponse response = httpClient.execute(request);

        assertThat(response.status(), equalTo(responseCode));
        assertThat(response.body().utf8ToString(), equalTo(body));
        assertThat(webServer.requests(), hasSize(1));
        assertThat(webServer.requests().get(0).getUri().getPath(), equalTo(request.path()));
        assertThat(webServer.requests().get(0).getUri().getQuery(), equalTo(paramKey + "=" + paramValue));
        assertThat(webServer.requests().get(0).getHeader(headerKey), equalTo(headerValue));
    }

    @TestLogging("org.elasticsearch.http.test:TRACE")
    public void testNoQueryString() throws Exception {
        webServer.enqueue(new MockResponse().setResponseCode(200).setBody("body"));
        HttpRequest.Builder requestBuilder = HttpRequest.builder("localhost", webServer.getPort())
                .method(HttpMethod.GET)
                .path("/test");

        HttpResponse response = httpClient.execute(requestBuilder.build());
        assertThat(response.status(), equalTo(200));
        assertThat(response.body().utf8ToString(), equalTo("body"));

        assertThat(webServer.requests(), hasSize(1));
        assertThat(webServer.requests().get(0).getUri().getPath(), is("/test"));
        assertThat(webServer.requests().get(0).getBody(), is(nullValue()));
    }

    public void testUrlEncodingWithQueryStrings() throws Exception{
        webServer.enqueue(new MockResponse().setResponseCode(200).setBody("body"));
        HttpRequest.Builder requestBuilder = HttpRequest.builder("localhost", webServer.getPort())
                .method(HttpMethod.GET)
                .path("/test")
                .setParam("key", "value 123:123");

        HttpResponse response = httpClient.execute(requestBuilder.build());
        assertThat(response.status(), equalTo(200));
        assertThat(response.body().utf8ToString(), equalTo("body"));

        assertThat(webServer.requests(), hasSize(1));
        assertThat(webServer.requests().get(0).getUri().getPath(), is("/test"));
        assertThat(webServer.requests().get(0).getUri().getRawQuery(), is("key=value+123%3A123"));
        assertThat(webServer.requests().get(0).getBody(), is(nullValue()));
    }

    public void testBasicAuth() throws Exception {
        webServer.enqueue(new MockResponse().setResponseCode(200).setBody("body"));
        HttpRequest.Builder request = HttpRequest.builder("localhost", webServer.getPort())
                .method(HttpMethod.POST)
                .path("/test")
                .auth(new BasicAuth("user", "pass".toCharArray()))
                .body("body");
        HttpResponse response = httpClient.execute(request.build());
        assertThat(response.status(), equalTo(200));
        assertThat(response.body().utf8ToString(), equalTo("body"));

        assertThat(webServer.requests(), hasSize(1));
        assertThat(webServer.requests().get(0).getUri().getPath(), is("/test"));
        assertThat(webServer.requests().get(0).getHeader("Authorization"), is("Basic dXNlcjpwYXNz"));
    }

    public void testNoPathSpecified() throws Exception {
        webServer.enqueue(new MockResponse().setResponseCode(200).setBody("doesntmatter"));
        HttpRequest.Builder request = HttpRequest.builder("localhost", webServer.getPort()).method(HttpMethod.GET);
        httpClient.execute(request.build());

        assertThat(webServer.requests(), hasSize(1));
        assertThat(webServer.requests().get(0).getUri().getPath(), is("/"));
    }

    public void testHttps() throws Exception {
        Path resource = getDataPath("/org/elasticsearch/xpack/security/keystore/truststore-testnode-only.jks");

        Settings settings;
        if (randomBoolean()) {
            settings = Settings.builder()
                    .put("xpack.http.ssl.truststore.path", resource.toString())
                    .put("xpack.http.ssl.truststore.password", "truststore-testnode-only")
                    .build();
        } else {
            settings = Settings.builder()
                    .put("xpack.ssl.truststore.path", resource.toString())
                    .put("xpack.ssl.truststore.password", "truststore-testnode-only")
                    .build();
        }
        httpClient = new HttpClient(settings, authRegistry, new SSLService(settings, environment));

        // We can't use the client created above for the server since it is only a truststore
        Settings settings2 = Settings.builder()
                .put("xpack.ssl.keystore.path", getDataPath("/org/elasticsearch/xpack/security/keystore/testnode.jks"))
                .put("xpack.ssl.keystore.password", "testnode")
                .build();

        TestsSSLService sslService = new TestsSSLService(settings2, environment);
        testSslMockWebserver(sslService.sslContext(), false);
    }

    public void testHttpsDisableHostnameVerification() throws Exception {
        Path resource = getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode-no-subjaltname.jks");

        Settings settings;
        if (randomBoolean()) {
            settings = Settings.builder()
                    .put("xpack.http.ssl.truststore.path", resource.toString())
                    .put("xpack.http.ssl.truststore.password", "testnode-no-subjaltname")
                    .put("xpack.http.ssl.verification_mode", randomFrom(VerificationMode.NONE, VerificationMode.CERTIFICATE))
                    .build();
        } else {
            settings = Settings.builder()
                    .put("xpack.ssl.truststore.path", resource.toString())
                    .put("xpack.ssl.truststore.password", "testnode-no-subjaltname")
                    .put("xpack.ssl.verification_mode", randomFrom(VerificationMode.NONE, VerificationMode.CERTIFICATE))
                    .build();
        }
        httpClient = new HttpClient(settings, authRegistry, new SSLService(settings, environment));

        // We can't use the client created above for the server since it only defines a truststore
        Settings settings2 = Settings.builder()
                .put("xpack.ssl.keystore.path",
                        getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode-no-subjaltname.jks"))
                .put("xpack.ssl.keystore.password", "testnode-no-subjaltname")
                .build();

        TestsSSLService sslService = new TestsSSLService(settings2, environment);
        testSslMockWebserver(sslService.sslContext(), false);
    }

    public void testHttpsClientAuth() throws Exception {
        Path resource = getDataPath("/org/elasticsearch/xpack/security/keystore/testnode.jks");
        Settings settings = Settings.builder()
                    .put("xpack.ssl.keystore.path", resource.toString())
                    .put("xpack.ssl.keystore.password", "testnode")
                    .build();

        TestsSSLService sslService = new TestsSSLService(settings, environment);
        httpClient = new HttpClient(settings, authRegistry, sslService);
        testSslMockWebserver(sslService.sslContext(), true);
    }

    private void testSslMockWebserver(SSLContext sslContext, boolean needClientAuth) throws IOException {
        try (MockWebServer mockWebServer = new MockWebServer(sslContext, needClientAuth)) {
            mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("body"));
            mockWebServer.start();

            HttpRequest.Builder request = HttpRequest.builder("localhost", mockWebServer.getPort())
                    .scheme(Scheme.HTTPS)
                    .path("/test");
            HttpResponse response = httpClient.execute(request.build());
            assertThat(response.status(), equalTo(200));
            assertThat(response.body().utf8ToString(), equalTo("body"));

            assertThat(mockWebServer.requests(), hasSize(1));
            assertThat(mockWebServer.requests().get(0).getUri().getPath(), is("/test"));
        }
    }

    public void testHttpResponseWithAnyStatusCodeCanReturnBody() throws Exception {
        int statusCode = randomFrom(200, 201, 400, 401, 403, 404, 405, 409, 413, 429, 500, 503);
        String body = RandomStrings.randomAsciiOfLength(random(), 100);
        boolean hasBody = usually();
        MockResponse mockResponse = new MockResponse().setResponseCode(statusCode);
        if (hasBody) {
            mockResponse.setBody(body);
        }
        webServer.enqueue(mockResponse);
        HttpRequest.Builder request = HttpRequest.builder("localhost", webServer.getPort())
                .method(HttpMethod.POST)
                .path("/test")
                .auth(new BasicAuth("user", "pass".toCharArray()))
                .body("body")
                .connectionTimeout(TimeValue.timeValueMillis(500))
                .readTimeout(TimeValue.timeValueMillis(500));
        HttpResponse response = httpClient.execute(request.build());
        assertThat(response.status(), equalTo(statusCode));
        assertThat(response.hasContent(), is(hasBody));
        if (hasBody) {
            assertThat(response.body().utf8ToString(), is(body));
        }
    }

    @Network
    public void testHttpsWithoutTruststore() throws Exception {
        HttpClient httpClient = new HttpClient(Settings.EMPTY, authRegistry, new SSLService(Settings.EMPTY, environment));

        // Known server with a valid cert from a commercial CA
        HttpRequest.Builder request = HttpRequest.builder("www.elastic.co", 443).scheme(Scheme.HTTPS);
        HttpResponse response = httpClient.execute(request.build());
        assertThat(response.status(), equalTo(200));
        assertThat(response.hasContent(), is(true));
        assertThat(response.body(), notNullValue());
    }

    public void testThatProxyCanBeConfigured() throws Exception {
        // this test fakes a proxy server that sends a response instead of forwarding it to the mock web server
        try (MockWebServer proxyServer = new MockWebServer()) {
            proxyServer.enqueue(new MockResponse().setResponseCode(200).setBody("fullProxiedContent"));
            proxyServer.start();
            Settings settings = Settings.builder()
                    .put(HttpSettings.PROXY_HOST.getKey(), "localhost")
                    .put(HttpSettings.PROXY_PORT.getKey(), proxyServer.getPort())
                    .build();
            HttpClient httpClient = new HttpClient(settings, authRegistry, new SSLService(settings, environment));

            HttpRequest.Builder requestBuilder = HttpRequest.builder("localhost", webServer.getPort())
                    .method(HttpMethod.GET)
                    .path("/");

            HttpResponse response = httpClient.execute(requestBuilder.build());
            assertThat(response.status(), equalTo(200));
            assertThat(response.body().utf8ToString(), equalTo("fullProxiedContent"));

            // ensure we hit the proxyServer and not the webserver
            assertThat(webServer.requests(), hasSize(0));
            assertThat(proxyServer.requests(), hasSize(1));
        }
    }

    public void testThatProxyCanBeOverriddenByRequest() throws Exception {
        // this test fakes a proxy server that sends a response instead of forwarding it to the mock web server
        try (MockWebServer proxyServer = new MockWebServer()) {
            proxyServer.enqueue(new MockResponse().setResponseCode(200).setBody("fullProxiedContent"));
            proxyServer.start();
            Settings settings = Settings.builder()
                    .put(HttpSettings.PROXY_HOST.getKey(), "localhost")
                    .put(HttpSettings.PROXY_PORT.getKey(), proxyServer.getPort() + 1)
                    .build();
            HttpClient httpClient = new HttpClient(settings, authRegistry, new SSLService(settings, environment));

            HttpRequest.Builder requestBuilder = HttpRequest.builder("localhost", webServer.getPort())
                    .method(HttpMethod.GET)
                    .proxy(new HttpProxy("localhost", proxyServer.getPort()))
                    .path("/");

            HttpResponse response = httpClient.execute(requestBuilder.build());
            assertThat(response.status(), equalTo(200));
            assertThat(response.body().utf8ToString(), equalTo("fullProxiedContent"));

            // ensure we hit the proxyServer and not the webserver
            assertThat(webServer.requests(), hasSize(0));
            assertThat(proxyServer.requests(), hasSize(1));
        }
    }

    public void testThatUrlPathIsNotEncoded() throws Exception {
        // %2F is a slash that needs to be encoded to not be misinterpreted as a path
        String path = "/%3Clogstash-%7Bnow%2Fd%7D%3E/_search";
        webServer.enqueue(new MockResponse().setResponseCode(200).setBody("foo"));
        HttpRequest request = HttpRequest.builder("localhost", webServer.getPort()).path(path).build();
        httpClient.execute(request);

        assertThat(webServer.requests(), hasSize(1));

        // under no circumstances have a double encode of %2F => %25 (percent sign)
        assertThat(webServer.requests(), hasSize(1));
        assertThat(webServer.requests().get(0).getUri().getRawPath(), not(containsString("%25")));
        assertThat(webServer.requests().get(0).getUri().getPath(), is("/<logstash-{now/d}>/_search"));
    }

    public void testThatHttpClientFailsOnNonHttpResponse() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Exception> hasExceptionHappened = new AtomicReference();
        try (ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getByName("localhost"))) {
            executor.execute(() -> {
                try (Socket socket = serverSocket.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    in.readLine();
                    socket.getOutputStream().write("This is not a HTTP response".getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                } catch (Exception e) {
                    hasExceptionHappened.set(e);
                    logger.error((Supplier<?>) () -> new ParameterizedMessage("Error in writing non HTTP response"), e);
                }
            });
            HttpRequest request = HttpRequest.builder("localhost", serverSocket.getLocalPort()).path("/").build();
            IOException e = expectThrows(IOException.class, () -> httpClient.execute(request));
            assertThat(e.getMessage(), is("Not a valid HTTP response, no status code in response"));
            assertThat("A server side exception occured, but shouldnt", hasExceptionHappened.get(), is(nullValue()));
        } finally {
            terminate(executor);
        }
    }
}
