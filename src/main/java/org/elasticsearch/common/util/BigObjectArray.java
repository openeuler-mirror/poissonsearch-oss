/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
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

package org.elasticsearch.common.util;

import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.RamUsageEstimator;

import java.util.Arrays;

/**
 * Int array abstraction able to support more than 2B values. This implementation slices data into fixed-sized blocks of
 * configurable length.
 */
final class BigObjectArray<T> extends AbstractBigArray implements ObjectArray<T> {

    /**
     * Page size, 16KB of memory per page.
     */
    public static final int PAGE_SIZE = BigArrays.PAGE_SIZE_IN_BYTES / RamUsageEstimator.NUM_BYTES_OBJECT_REF;
    

    private Object[][] pages;

    /** Constructor. */
    public BigObjectArray(long size) {
        super(PAGE_SIZE);
        this.size = size;
        pages = new Object[numPages(size)][];
        for (int i = 0; i < pages.length; ++i) {
            pages[i] = new Object[pageSize()];
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public T get(long index) {
        final int pageIndex = pageIndex(index);
        final int indexInPage = indexInPage(index);
        return (T) pages[pageIndex][indexInPage];
    }

    @Override
    public T set(long index, T value) {
        final int pageIndex = pageIndex(index);
        final int indexInPage = indexInPage(index);
        final Object[] page = pages[pageIndex];
        @SuppressWarnings("unchecked")
        final T ret = (T) page[indexInPage];
        page[indexInPage] = value;
        return ret;
    }

    @Override
    protected int numBytesPerElement() {
        return RamUsageEstimator.NUM_BYTES_INT;
    }

    /** Change the size of this array. Content between indexes <code>0</code> and <code>min(size(), newSize)</code> will be preserved. */
    public void resize(long newSize) {
        final int numPages = numPages(newSize);
        if (numPages > pages.length) {
            pages = Arrays.copyOf(pages, ArrayUtil.oversize(numPages, RamUsageEstimator.NUM_BYTES_OBJECT_REF));
        }
        for (int i = numPages - 1; i >= 0 && pages[i] == null; --i) {
            pages[i] = new Object[pageSize()];
        }
        for (int i = numPages; i < pages.length && pages[i] != null; ++i) {
            pages[i] = null;
        }
        this.size = newSize;
    }

}
