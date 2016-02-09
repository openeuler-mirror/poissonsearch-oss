/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.support.http;

import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.watcher.support.http.auth.HttpAuthRegistry;
import org.elasticsearch.watcher.support.http.auth.basic.BasicAuth;
import org.elasticsearch.watcher.support.http.auth.basic.BasicAuthFactory;
import org.elasticsearch.watcher.support.secret.SecretService;
import org.elasticsearch.watcher.support.text.TextTemplate;
import org.elasticsearch.watcher.test.MockTextTemplateEngine;
import org.jboss.netty.handler.codec.http.HttpHeaders;

import java.util.Collections;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;

/**
 *
 */
public class HttpRequestTemplateTests extends ESTestCase {

    public void testBodyWithXContent() throws Exception {
        XContentType type = randomFrom(XContentType.JSON, XContentType.YAML);
        HttpRequestTemplate template = HttpRequestTemplate.builder("_host", 1234)
                .body(XContentBuilder.builder(type.xContent()).startObject().endObject())
                .build();
        HttpRequest request = template.render(new MockTextTemplateEngine(), emptyMap());
        assertThat(request.headers, hasEntry(HttpHeaders.Names.CONTENT_TYPE, type.mediaType()));
    }

    public void testBody() throws Exception {
        HttpRequestTemplate template = HttpRequestTemplate.builder("_host", 1234)
                .body("_body")
                .build();
        HttpRequest request = template.render(new MockTextTemplateEngine(), emptyMap());
        assertThat(request.headers.size(), is(0));
    }

    public void testProxy() throws Exception {
        HttpRequestTemplate template = HttpRequestTemplate.builder("_host", 1234)
                .proxy(new HttpProxy("localhost", 8080))
                .build();
        HttpRequest request = template.render(new MockTextTemplateEngine(), Collections.emptyMap());
        assertThat(request.proxy().getHost(), is("localhost"));
        assertThat(request.proxy().getPort(), is(8080));
    }

    public void testRender() {
        HttpRequestTemplate template = HttpRequestTemplate.builder("_host", 1234)
                .body(TextTemplate.inline("_body"))
                .path(TextTemplate.inline("_path"))
                .putParam("_key1", TextTemplate.inline("_value1"))
                .putHeader("_key2", TextTemplate.inline("_value2"))
                .build();

        HttpRequest result = template.render(new MockTextTemplateEngine(), Collections.emptyMap());
        assertThat(result.body(), equalTo("_body"));
        assertThat(result.path(), equalTo("_path"));
        assertThat(result.params(), equalTo(Collections.singletonMap("_key1", "_value1")));
        assertThat(result.headers(), equalTo(Collections.singletonMap("_key2", "_value2")));
    }

    public void testProxyParsing() throws Exception {
        HttpRequestTemplate.Builder builder = HttpRequestTemplate.builder("_host", 1234);
        builder.path("/path");
        builder.method(randomFrom(HttpMethod.values()));
        String proxyHost = randomAsciiOfLength(10);
        int proxyPort = randomIntBetween(1, 65534);
        builder.proxy(new HttpProxy(proxyHost, proxyPort));
        HttpRequestTemplate template = builder.build();

        XContentBuilder xContentBuilder = template.toXContent(jsonBuilder(), ToXContent.EMPTY_PARAMS);
        XContentParser xContentParser = JsonXContent.jsonXContent.createParser(xContentBuilder.bytes());
        xContentParser.nextToken();

        HttpRequestTemplate.Parser parser = new HttpRequestTemplate.Parser(mock(HttpAuthRegistry.class));
        HttpRequestTemplate parsedTemplate = parser.parse(xContentParser);
        assertThat(parsedTemplate.proxy().getPort(), is(proxyPort));
        assertThat(parsedTemplate.proxy().getHost(), is(proxyHost));
    }

    public void testParseSelfGenerated() throws Exception {
        HttpRequestTemplate.Builder builder = HttpRequestTemplate.builder("_host", 1234);

        if (randomBoolean()) {
            builder.method(randomFrom(HttpMethod.values()));
        }
        if (randomBoolean()) {
            builder.path("/path");
        }
        boolean xbody = randomBoolean();
        if (randomBoolean()) {
            if (xbody) {
                builder.body(jsonBuilder().startObject().endObject());
            } else {
                builder.body("_body");
            }
        }
        if (randomBoolean()) {
            builder.auth(new BasicAuth("_username", "_password".toCharArray()));
        }
        if (randomBoolean()) {
            builder.putParam("_key", TextTemplate.inline("_value"));
        }
        if (randomBoolean()) {
            builder.putHeader("_key", TextTemplate.inline("_value"));
        }
        long connectionTimeout = randomBoolean() ? 0 : randomIntBetween(5, 10);
        if (connectionTimeout > 0) {
            builder.connectionTimeout(TimeValue.timeValueSeconds(connectionTimeout));
        }
        long readTimeout = randomBoolean() ? 0 : randomIntBetween(5, 10);
        if (readTimeout > 0) {
            builder.readTimeout(TimeValue.timeValueSeconds(readTimeout));
        }
        boolean enableProxy = randomBoolean();
        if (enableProxy) {
            builder.proxy(new HttpProxy(randomAsciiOfLength(10), randomIntBetween(1, 65534)));
        }

        HttpRequestTemplate template = builder.build();

        HttpAuthRegistry registry = new HttpAuthRegistry(singletonMap(BasicAuth.TYPE,
                new BasicAuthFactory(SecretService.Insecure.INSTANCE)));
        HttpRequestTemplate.Parser parser = new HttpRequestTemplate.Parser(registry);

        XContentBuilder xContentBuilder = template.toXContent(jsonBuilder(), ToXContent.EMPTY_PARAMS);
        XContentParser xContentParser = JsonXContent.jsonXContent.createParser(xContentBuilder.bytes());
        xContentParser.nextToken();
        HttpRequestTemplate parsed = parser.parse(xContentParser);

        assertThat(parsed, equalTo(template));
    }

    public void testParsingFromUrl() throws Exception {
        HttpRequestTemplate.Builder builder = HttpRequestTemplate.builder("www.example.org", 1234);
        builder.path("/foo/bar/org");
        builder.putParam("param", TextTemplate.inline("test"));
        builder.scheme(Scheme.HTTPS);
        assertThatManualBuilderEqualsParsingFromUrl("https://www.example.org:1234/foo/bar/org?param=test", builder);

        // test without specifying port
        builder = HttpRequestTemplate.builder("www.example.org", 80);
        assertThatManualBuilderEqualsParsingFromUrl("http://www.example.org", builder);

        // encoded values
        builder = HttpRequestTemplate.builder("www.example.org", 80).putParam("foo", TextTemplate.inline(" white space"));
        assertThatManualBuilderEqualsParsingFromUrl("http://www.example.org?foo=%20white%20space", builder);
    }

    private void assertThatManualBuilderEqualsParsingFromUrl(String url, HttpRequestTemplate.Builder builder) throws Exception {
        XContentBuilder urlContentBuilder = jsonBuilder().startObject().field("url", url).endObject();
        XContentParser urlContentParser = JsonXContent.jsonXContent.createParser(urlContentBuilder.bytes());
        urlContentParser.nextToken();

        HttpRequestTemplate.Parser parser = new HttpRequestTemplate.Parser(mock(HttpAuthRegistry.class));
        HttpRequestTemplate urlParsedTemplate = parser.parse(urlContentParser);

        XContentBuilder xContentBuilder = builder.build().toXContent(jsonBuilder(), ToXContent.EMPTY_PARAMS);
        XContentParser xContentParser = JsonXContent.jsonXContent.createParser(xContentBuilder.bytes());
        xContentParser.nextToken();
        HttpRequestTemplate parsedTemplate = parser.parse(xContentParser);

        assertThat(parsedTemplate, is(urlParsedTemplate));
    }

}
