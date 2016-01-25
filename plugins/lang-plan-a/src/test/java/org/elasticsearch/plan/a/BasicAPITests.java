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

package org.elasticsearch.plan.a;

public class BasicAPITests extends ScriptTestCase {

    public void testListIterator() {
        assertEquals(3, exec("List x = new ArrayList(); x.add(2); x.add(3); x.add(-2); Iterator y = x.iterator(); " +
            "int total = 0; while (y.hasNext()) total += y.next(); return total;"));
        assertEquals(3, exec("List<Object> x = new ArrayList(); x.add(2); x.add(3); x.add(-2); Iterator<Object> y = x.iterator(); " +
            "int total = 0; while (y.hasNext()) total += (int)y.next(); return total;"));
        assertEquals("abc", exec("List<String> x = new ArrayList(); x.add(\"a\"); x.add(\"b\"); x.add(\"c\"); " +
            "Iterator<String> y = x.iterator(); String total = \"\"; while (y.hasNext()) total += y.next(); return total;"));
        assertEquals(3, exec("def x = new ArrayList(); x.add(2); x.add(3); x.add(-2); def y = x.iterator(); " +
            "def total = 0; while (y.hasNext()) total += y.next(); return total;"));
    }

    public void testSetIterator() {
        assertEquals(3, exec("Set x = new HashSet(); x.add(2); x.add(3); x.add(-2); Iterator y = x.iterator(); " +
            "int total = 0; while (y.hasNext()) total += y.next(); return total;"));
        assertEquals(3, exec("Set<Object> x = new HashSet(); x.add(2); x.add(3); x.add(-2); Iterator<Object> y = x.iterator(); " +
            "int total = 0; while (y.hasNext()) total += (int)y.next(); return total;"));
        assertEquals("abc", exec("Set<String> x = new HashSet(); x.add(\"a\"); x.add(\"b\"); x.add(\"c\"); " +
            "Iterator<String> y = x.iterator(); String total = \"\"; while (y.hasNext()) total += y.next(); return total;"));
        assertEquals(3, exec("def x = new HashSet(); x.add(2); x.add(3); x.add(-2); def y = x.iterator(); " +
            "def total = 0; while (y.hasNext()) total += (int)y.next(); return total;"));
    }

    public void testMapIterator() {
        assertEquals(3, exec("Map x = new HashMap(); x.put(2, 2); x.put(3, 3); x.put(-2, -2); Iterator y = x.keySet().iterator(); " +
            "int total = 0; while (y.hasNext()) total += (int)y.next(); return total;"));
        assertEquals(3, exec("Map x = new HashMap(); x.put(2, 2); x.put(3, 3); x.put(-2, -2); Iterator y = x.values().iterator(); " +
            "int total = 0; while (y.hasNext()) total += (int)y.next(); return total;"));
    }
}
