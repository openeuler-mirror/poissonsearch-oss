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

package org.elasticsearch.common.bytes;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefIterator;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.ByteArray;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * A page based bytes reference, internally holding the bytes in a paged
 * data structure.
 */
public class PagedBytesReference extends BytesReference {

    private static final int PAGE_SIZE = BigArrays.BYTE_PAGE_SIZE;

    private final BigArrays bigarrays;
    protected final ByteArray bytearray;
    private final int offset;
    private final int length;
    private int hash = 0;

    public PagedBytesReference(BigArrays bigarrays, ByteArray bytearray, int length) {
        this(bigarrays, bytearray, 0, length);
    }

    public PagedBytesReference(BigArrays bigarrays, ByteArray bytearray, int from, int length) {
        this.bigarrays = bigarrays;
        this.bytearray = bytearray;
        this.offset = from;
        this.length = length;
    }

    @Override
    public byte get(int index) {
        return bytearray.get(offset + index);
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public BytesReference slice(int from, int length) {
        if (from < 0 || (from + length) > length()) {
            throw new IllegalArgumentException("can't slice a buffer with length [" + length() + "], with slice parameters from [" + from + "], length [" + length + "]");
        }

        return new PagedBytesReference(bigarrays, bytearray, offset + from, length);
    }

    @Override
    public StreamInput streamInput() {
        return new PagedBytesReferenceStreamInput(bytearray, offset, length);
    }

    @Override
    public BytesRef toBytesRef() {
        BytesRef bref = new BytesRef();
        // if length <= pagesize this will dereference the page, or materialize the byte[]
        bytearray.get(offset, length, bref);
        return bref;
    }

    private static class PagedBytesReferenceStreamInput extends StreamInput {

        private final ByteArray bytearray;
        private final BytesRef ref;
        private final int offset;
        private final int length;
        private int pos;
        private int mark;

        public PagedBytesReferenceStreamInput(ByteArray bytearray, int offset, int length) {
            this.bytearray = bytearray;
            this.ref = new BytesRef();
            this.offset = offset;
            this.length = length;
            this.pos = 0;

            if (offset + length > bytearray.size()) {
                throw new IndexOutOfBoundsException("offset+length >= bytearray.size()");
            }
        }

        @Override
        public byte readByte() throws IOException {
            if (pos >= length) {
                throw new EOFException();
            }

            return bytearray.get(offset + pos++);
        }

        @Override
        public void readBytes(byte[] b, int bOffset, int len) throws IOException {
            if (len > offset + length) {
                throw new IndexOutOfBoundsException("Cannot read " + len + " bytes from stream with length " + length + " at pos " + pos);
            }

            read(b, bOffset, len);
        }

        @Override
        public int read() throws IOException {
            return (pos < length) ? Byte.toUnsignedInt(bytearray.get(offset + pos++)) : -1;
        }

        @Override
        public int read(final byte[] b, final int bOffset, final int len) throws IOException {
            if (len == 0) {
                return 0;
            }

            if (pos >= offset + length) {
                return -1;
            }

            final int numBytesToCopy = Math.min(len, length - pos); // copy the full length or the remaining part

            // current offset into the underlying ByteArray
            long byteArrayOffset = offset + pos;

            // bytes already copied
            int copiedBytes = 0;

            while (copiedBytes < numBytesToCopy) {
                long pageFragment = PAGE_SIZE - (byteArrayOffset % PAGE_SIZE); // how much can we read until hitting N*PAGE_SIZE?
                int bulkSize = (int) Math.min(pageFragment, numBytesToCopy - copiedBytes); // we cannot copy more than a page fragment
                boolean copied = bytearray.get(byteArrayOffset, bulkSize, ref); // get the fragment
                assert (copied == false); // we should never ever get back a materialized byte[]
                System.arraycopy(ref.bytes, ref.offset, b, bOffset + copiedBytes, bulkSize); // copy fragment contents
                copiedBytes += bulkSize; // count how much we copied
                byteArrayOffset += bulkSize; // advance ByteArray index
            }

            pos += copiedBytes; // finally advance our stream position
            return copiedBytes;
        }

        @Override
        public boolean markSupported() {
            return true;
        }

        @Override
        public void mark(int readlimit) {
            this.mark = pos;
        }

        @Override
        public void reset() throws IOException {
            pos = mark;
        }

        @Override
        public void close() throws IOException {
            // do nothing
        }

        @Override
        public int available() throws IOException {
            return length - pos;
        }

    }

    @Override
    public final BytesRefIterator iterator() {
        final int offset = this.offset;
        final int length = this.length;
        // this iteration is page aligned to ensure we do NOT materialize the pages from the ByteArray
        // we calculate the initial fragment size here to ensure that if this reference is a slice we are still page aligned
        // across the entire iteration. The first page is smaller if our offset != 0 then we start in the middle of the page
        // otherwise we iterate full pages until we reach the last chunk which also might end within a page.
        final int initialFragmentSize = offset != 0 ? PAGE_SIZE - (offset % PAGE_SIZE) : PAGE_SIZE;
        return new BytesRefIterator() {
            int position = 0;
            int nextFragmentSize = Math.min(length, initialFragmentSize);
            // this BytesRef is reused across the iteration on purpose - BytesRefIterator interface was designed for this
            final BytesRef slice = new BytesRef();

            @Override
            public BytesRef next() throws IOException {
                if (nextFragmentSize != 0) {
                    final boolean materialized = bytearray.get(offset + position, nextFragmentSize, slice);
                    assert materialized == false : "iteration should be page aligned but array got materialized";
                    position += nextFragmentSize;
                    final int remaining = length - position;
                    nextFragmentSize = Math.min(remaining, PAGE_SIZE);
                    return slice;
                } else {
                    assert nextFragmentSize == 0 : "fragmentSize expected [0] but was: [" + nextFragmentSize + "]";
                    return null; // we are done with this iteration
                }
            }
        };
    }

    @Override
    public long ramBytesUsed() {
        return bytearray.ramBytesUsed();
    }
}
