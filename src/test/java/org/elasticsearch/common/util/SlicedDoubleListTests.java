/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.common.util;

import org.elasticsearch.test.ElasticSearchTestCase;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Tests for {@link SlicedDoubleList}
 */
public class SlicedDoubleListTests extends ElasticSearchTestCase {
    
    @Test
    public void testCapacity() {
        SlicedDoubleList list = new SlicedDoubleList(5);
        assertThat(list.length, equalTo(5));
        assertThat(list.offset, equalTo(0));
        assertThat(list.values.length, equalTo(5));
        assertThat(list.size(), equalTo(5));

        
        list = new SlicedDoubleList(new double[10], 5, 5);
        assertThat(list.length, equalTo(5));
        assertThat(list.offset, equalTo(5));
        assertThat(list.size(), equalTo(5));
        assertThat(list.values.length, equalTo(10));
    }
    
    @Test
    public void testGrow() {
        SlicedDoubleList list = new SlicedDoubleList(5);
        list.length = 1000;
        for (int i = 0; i < list.length; i++) {
            list.grow(i+1);
            list.values[i] = ((double)i);
        }
        int expected = 0;
        for (Double d : list) {
            assertThat((double)expected++, equalTo(d));
        }
        
        for (int i = 0; i < list.length; i++) {
            assertThat((double)i, equalTo(list.get(i)));
        }
        
        int count = 0;
        for (int i = list.offset; i < list.offset+list.length; i++) {
            assertThat((double)count++, equalTo(list.values[i]));
        }
    }
    
    @Test
    public void testIndexOf() {
        SlicedDoubleList list = new SlicedDoubleList(5);
        list.length = 1000;
        for (int i = 0; i < list.length; i++) {
            list.grow(i+1);
            list.values[i] = ((double)i%100);
        }
        
        assertThat(999, equalTo(list.lastIndexOf(99.0d)));
        assertThat(99, equalTo(list.indexOf(99.0d)));
        
        assertThat(-1, equalTo(list.lastIndexOf(100.0d)));
        assertThat(-1, equalTo(list.indexOf(100.0d)));
    }
    
    public void testIsEmpty() {
        SlicedDoubleList list = new SlicedDoubleList(5);
        assertThat(false, equalTo(list.isEmpty()));
        list.length = 0;
        assertThat(true, equalTo(list.isEmpty()));
    }
    
    @Test
    public void testSet() {
        SlicedDoubleList list = new SlicedDoubleList(5);
        try {
            list.set(0, (double)4);
            assert false;
        } catch (UnsupportedOperationException ex) {
        }
        try {
            list.add((double)4);
            assert false;
        } catch (UnsupportedOperationException ex) {
        }
    }
    
    @Test
    public void testToString() {
        SlicedDoubleList list = new SlicedDoubleList(5);
        assertThat("[0.0, 0.0, 0.0, 0.0, 0.0]", equalTo(list.toString()));
        for (int i = 0; i < list.length; i++) {
            list.grow(i+1);
            list.values[i] = ((double)i);
        }
        assertThat("[0.0, 1.0, 2.0, 3.0, 4.0]", equalTo(list.toString()));
    }
    
}
