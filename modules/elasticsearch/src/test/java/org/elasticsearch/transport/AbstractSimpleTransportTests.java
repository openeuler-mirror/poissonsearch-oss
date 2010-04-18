/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

package org.elasticsearch.transport;

import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.threadpool.scaling.ScalingThreadPool;
import org.elasticsearch.util.io.stream.StreamInput;
import org.elasticsearch.util.io.stream.StreamOutput;
import org.elasticsearch.util.io.stream.Streamable;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;

/**
 * @author kimchy (shay.banon)
 */
public abstract class AbstractSimpleTransportTests {

    protected ThreadPool threadPool;

    protected TransportService serviceA;
    protected TransportService serviceB;
    protected DiscoveryNode serviceANode;
    protected DiscoveryNode serviceBNode;

    @BeforeMethod public void setUp() {
        threadPool = new ScalingThreadPool();
        build();
        serviceA.connectToNode(serviceBNode);
        serviceB.connectToNode(serviceANode);
    }

    @AfterMethod public void tearDown() {
        serviceA.close();
        serviceB.close();

        threadPool.shutdown();
    }

    protected abstract void build();

    @Test public void testHelloWorld() {
        serviceA.registerHandler("sayHello", new BaseTransportRequestHandler<StringMessage>() {
            @Override public StringMessage newInstance() {
                return new StringMessage();
            }

            @Override public void messageReceived(StringMessage request, TransportChannel channel) {
                System.out.println("got message: " + request.message);
                assertThat("moshe", equalTo(request.message));
                try {
                    channel.sendResponse(new StringMessage("hello " + request.message));
                } catch (IOException e) {
                    e.printStackTrace();
                    assertThat(e.getMessage(), false, equalTo(true));
                }
            }
        });

        TransportFuture<StringMessage> res = serviceB.submitRequest(serviceANode, "sayHello",
                new StringMessage("moshe"), new BaseTransportResponseHandler<StringMessage>() {
                    @Override public StringMessage newInstance() {
                        return new StringMessage();
                    }

                    @Override public void handleResponse(StringMessage response) {
                        System.out.println("got response: " + response.message);
                        assertThat("hello moshe", equalTo(response.message));
                    }

                    @Override public void handleException(RemoteTransportException exp) {
                        exp.printStackTrace();
                        assertThat("got exception instead of a response: " + exp.getMessage(), false, equalTo(true));
                    }
                });

        try {
            StringMessage message = res.get();
            assertThat("hello moshe", equalTo(message.message));
        } catch (Exception e) {
            assertThat(e.getMessage(), false, equalTo(true));
        }

        System.out.println("after ...");
    }

    @Test public void testErrorMessage() {
        serviceA.registerHandler("sayHelloException", new BaseTransportRequestHandler<StringMessage>() {
            @Override public StringMessage newInstance() {
                return new StringMessage();
            }

            @Override public void messageReceived(StringMessage request, TransportChannel channel) throws Exception {
                System.out.println("got message: " + request.message);
                assertThat("moshe", equalTo(request.message));
                throw new RuntimeException("bad message !!!");
            }
        });

        TransportFuture<StringMessage> res = serviceB.submitRequest(serviceANode, "sayHelloException",
                new StringMessage("moshe"), new BaseTransportResponseHandler<StringMessage>() {
                    @Override public StringMessage newInstance() {
                        return new StringMessage();
                    }

                    @Override public void handleResponse(StringMessage response) {
                        assertThat("got response instead of exception", false, equalTo(true));
                    }

                    @Override public void handleException(RemoteTransportException exp) {
                        assertThat("bad message !!!", equalTo(exp.getCause().getMessage()));
                    }
                });

        try {
            res.txGet();
            assertThat("exception should be thrown", false, equalTo(true));
        } catch (Exception e) {
            assertThat("bad message !!!", equalTo(e.getCause().getMessage()));
        }

        System.out.println("after ...");

    }

    @Test
    public void testDisconnectListener() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        TransportConnectionListener disconnectListener = new TransportConnectionListener() {
            @Override public void onNodeConnected(DiscoveryNode node) {
                throw new RuntimeException("Should not be called");
            }

            @Override public void onNodeDisconnected(DiscoveryNode node) {
                latch.countDown();
            }
        };
        serviceA.addConnectionListener(disconnectListener);
        serviceB.close();
        assertThat(latch.await(1, TimeUnit.SECONDS), equalTo(true));
    }

    private class StringMessage implements Streamable {

        private String message;

        private StringMessage(String message) {
            this.message = message;
        }

        private StringMessage() {
        }

        @Override public void readFrom(StreamInput in) throws IOException {
            message = in.readUTF();
        }

        @Override public void writeTo(StreamOutput out) throws IOException {
            out.writeUTF(message);
        }
    }
}
