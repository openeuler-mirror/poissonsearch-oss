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

package org.elasticsearch.transport.nio;

import org.elasticsearch.test.ESTestCase;

import java.nio.ByteBuffer;

public class InboundChannelBufferTests extends ESTestCase {

    private static final int PAGE_SIZE = 1 << 14;

    public void testNewBufferHasSinglePage() {
        InboundChannelBuffer channelBuffer = new InboundChannelBuffer();

        assertEquals(PAGE_SIZE, channelBuffer.getCapacity());
        assertEquals(PAGE_SIZE, channelBuffer.getRemaining());
        assertEquals(0, channelBuffer.getIndex());
    }

    public void testExpandCapacity() {
        InboundChannelBuffer channelBuffer = new InboundChannelBuffer();

        assertEquals(PAGE_SIZE, channelBuffer.getCapacity());
        assertEquals(PAGE_SIZE, channelBuffer.getRemaining());

        channelBuffer.ensureCapacity(PAGE_SIZE + 1);

        assertEquals(PAGE_SIZE * 2, channelBuffer.getCapacity());
        assertEquals(PAGE_SIZE * 2, channelBuffer.getRemaining());
    }

    public void testExpandCapacityMultiplePages() {
        InboundChannelBuffer channelBuffer = new InboundChannelBuffer();

        assertEquals(PAGE_SIZE, channelBuffer.getCapacity());

        int multiple = randomInt(80);
        channelBuffer.ensureCapacity(PAGE_SIZE + ((multiple * PAGE_SIZE) - randomInt(500)));

        assertEquals(PAGE_SIZE * (multiple + 1), channelBuffer.getCapacity());
        assertEquals(PAGE_SIZE * (multiple + 1), channelBuffer.getRemaining());
    }

    public void testExpandCapacityRespectsOffset() {
        InboundChannelBuffer channelBuffer = new InboundChannelBuffer();

        assertEquals(PAGE_SIZE, channelBuffer.getCapacity());
        assertEquals(PAGE_SIZE, channelBuffer.getRemaining());

        int offset = randomInt(300);

        channelBuffer.release(offset);

        assertEquals(PAGE_SIZE - offset, channelBuffer.getCapacity());
        assertEquals(PAGE_SIZE - offset, channelBuffer.getRemaining());

        channelBuffer.ensureCapacity(PAGE_SIZE + 1);

        assertEquals(PAGE_SIZE * 2 - offset, channelBuffer.getCapacity());
        assertEquals(PAGE_SIZE * 2 - offset, channelBuffer.getRemaining());
    }

    public void testIncrementIndex() {
        InboundChannelBuffer channelBuffer = new InboundChannelBuffer();

        assertEquals(0, channelBuffer.getIndex());
        assertEquals(PAGE_SIZE, channelBuffer.getRemaining());

        channelBuffer.incrementIndex(10);

        assertEquals(10, channelBuffer.getIndex());
        assertEquals(PAGE_SIZE - 10, channelBuffer.getRemaining());
    }

    public void testIncrementIndexWithOffset() {
        InboundChannelBuffer channelBuffer = new InboundChannelBuffer();

        assertEquals(0, channelBuffer.getIndex());
        assertEquals(PAGE_SIZE, channelBuffer.getRemaining());

        channelBuffer.release(10);
        assertEquals(PAGE_SIZE - 10, channelBuffer.getRemaining());

        channelBuffer.incrementIndex(10);

        assertEquals(10, channelBuffer.getIndex());
        assertEquals(PAGE_SIZE - 20, channelBuffer.getRemaining());

        channelBuffer.release(2);
        assertEquals(8, channelBuffer.getIndex());
        assertEquals(PAGE_SIZE - 20, channelBuffer.getRemaining());
    }

    public void testAccessByteBuffers() {
        InboundChannelBuffer channelBuffer = new InboundChannelBuffer();

        int pages = randomInt(50) + 5;
        channelBuffer.ensureCapacity(pages * PAGE_SIZE);

        long capacity = channelBuffer.getCapacity();

        ByteBuffer[] postIndexBuffers = channelBuffer.sliceBuffersFrom(channelBuffer.getIndex());
        int i = 0;
        for (ByteBuffer buffer : postIndexBuffers) {
            while (buffer.hasRemaining()) {
                buffer.put((byte) (i++ % 127));
            }
        }

        int indexIncremented = 0;
        int bytesReleased = 0;
        while (indexIncremented < capacity) {
            assertEquals(indexIncremented - bytesReleased, channelBuffer.getIndex());

            long amountToInc = Math.min(randomInt(2000), channelBuffer.getRemaining());
            ByteBuffer[] postIndexBuffers2 = channelBuffer.sliceBuffersFrom(channelBuffer.getIndex());
            assertEquals((byte) ((channelBuffer.getIndex() + bytesReleased) % 127), postIndexBuffers2[0].get());
            ByteBuffer[] preIndexBuffers = channelBuffer.sliceBuffersTo(channelBuffer.getIndex());
            if (preIndexBuffers.length > 0) {
                ByteBuffer preIndexBuffer = preIndexBuffers[preIndexBuffers.length - 1];
                assertEquals((byte) ((channelBuffer.getIndex() + bytesReleased - 1) % 127), preIndexBuffer.get(preIndexBuffer.limit() - 1));
            }
            if (randomBoolean()) {
                long bytesToRelease = Math.min(randomInt(50), channelBuffer.getIndex());
                channelBuffer.release(bytesToRelease);
                bytesReleased += bytesToRelease;
            }
            channelBuffer.incrementIndex(amountToInc);
            indexIncremented += amountToInc;
        }

        assertEquals(0, channelBuffer.sliceBuffersFrom(channelBuffer.getIndex()).length);
    }
}
