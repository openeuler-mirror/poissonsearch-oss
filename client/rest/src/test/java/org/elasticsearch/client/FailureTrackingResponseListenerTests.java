/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.client;

import org.elasticsearch.client.http.HttpHost;
import org.elasticsearch.client.http.HttpResponse;
import org.elasticsearch.client.http.ProtocolVersion;
import org.elasticsearch.client.http.RequestLine;
import org.elasticsearch.client.http.StatusLine;
import org.elasticsearch.client.http.message.BasicHttpResponse;
import org.elasticsearch.client.http.message.BasicRequestLine;
import org.elasticsearch.client.http.message.BasicStatusLine;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class FailureTrackingResponseListenerTests extends RestClientTestCase {

    public void testOnSuccess() {
        MockResponseListener responseListener = new MockResponseListener();
        RestClient.FailureTrackingResponseListener listener = new RestClient.FailureTrackingResponseListener(responseListener);

    }

    public void testOnFailure() {
        MockResponseListener responseListener = new MockResponseListener();
        RestClient.FailureTrackingResponseListener listener = new RestClient.FailureTrackingResponseListener(responseListener);
        int numIters = randomIntBetween(1, 10);
        Exception[] expectedExceptions = new Exception[numIters];
        for (int i = 0; i < numIters; i++) {
            RuntimeException runtimeException = new RuntimeException("test" + i);
            expectedExceptions[i] = runtimeException;
            listener.trackFailure(runtimeException);
            assertNull(responseListener.response.get());
            assertNull(responseListener.exception.get());
        }

        if (randomBoolean()) {
            Response response = mockResponse();
            listener.onSuccess(response);
            assertSame(response, responseListener.response.get());
            assertNull(responseListener.exception.get());
        } else {
            RuntimeException runtimeException = new RuntimeException("definitive");
            listener.onDefinitiveFailure(runtimeException);
            assertNull(responseListener.response.get());
            Throwable exception = responseListener.exception.get();
            assertSame(runtimeException, exception);

            int i = numIters - 1;
            do {
                assertNotNull(exception.getSuppressed());
                assertEquals(1, exception.getSuppressed().length);
                assertSame(expectedExceptions[i--], exception.getSuppressed()[0]);
                exception = exception.getSuppressed()[0];
            } while(i >= 0);
        }
    }

    private static class MockResponseListener implements ResponseListener {
        private final AtomicReference<Response> response = new AtomicReference<>();
        private final AtomicReference<Exception> exception = new AtomicReference<>();

        @Override
        public void onSuccess(Response response) {
            if (this.response.compareAndSet(null, response) == false) {
                throw new IllegalStateException("onSuccess was called multiple times");
            }
        }

        @Override
        public void onFailure(Exception exception) {
            if (this.exception.compareAndSet(null, exception) == false) {
                throw new IllegalStateException("onFailure was called multiple times");
            }
        }
    }

    private static Response mockResponse() {
        ProtocolVersion protocolVersion = new ProtocolVersion("HTTP", 1, 1);
        RequestLine requestLine = new BasicRequestLine("GET", "/", protocolVersion);
        StatusLine statusLine = new BasicStatusLine(protocolVersion, 200, "OK");
        HttpResponse httpResponse = new BasicHttpResponse(statusLine);
        return new Response(requestLine, new HttpHost("localhost", 9200), httpResponse);
    }
}
