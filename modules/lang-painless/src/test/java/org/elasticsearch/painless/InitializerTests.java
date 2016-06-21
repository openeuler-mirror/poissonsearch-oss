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

package org.elasticsearch.painless;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InitializerTests extends ScriptTestCase {

    public void testArrayInitializers() {
        int[] ints = (int[])exec("new int[] {}");

        assertEquals(0, ints.length);

        ints = (int[])exec("new int[] {5, 7, -1, 14}");

        assertEquals(4, ints.length);
        assertEquals(5, ints[0]);
        assertEquals(7, ints[1]);
        assertEquals(-1, ints[2]);
        assertEquals(14, ints[3]);

        ints = (int[])exec("int y = 2; int z = 3; int[] x = new int[] {y*z, y + z, y - z, y, z}; return x;");

        assertEquals(5, ints.length);
        assertEquals(6, ints[0]);
        assertEquals(5, ints[1]);
        assertEquals(-1, ints[2]);
        assertEquals(2, ints[3]);
        assertEquals(3, ints[4]);

        Object[] objects = (Object[])exec("int y = 2; List z = new ArrayList(); String s = 'aaa';" +
            "Object[] x = new Object[] {y, z, 1 + s, s + 'aaa'}; return x;");

        assertEquals(4, objects.length);
        assertEquals(new Integer(2), objects[0]);
        assertEquals(new ArrayList(), objects[1]);
        assertEquals("1aaa", objects[2]);
        assertEquals("aaaaaa", objects[3]);
    }

    public void testListInitializers() {
        List list = (List)exec("[]");

        assertEquals(0, list.size());

        list = (List)exec("[5, 7, -1, 14]");

        assertEquals(4, list.size());
        assertEquals(5, list.get(0));
        assertEquals(7, list.get(1));
        assertEquals(-1, list.get(2));
        assertEquals(14, list.get(3));

        list = (List)exec("int y = 2; int z = 3; def x = [y*z, y + z, y - z, y, z]; return x;");

        assertEquals(5, list.size());
        assertEquals(6, list.get(0));
        assertEquals(5, list.get(1));
        assertEquals(-1, list.get(2));
        assertEquals(2, list.get(3));
        assertEquals(3, list.get(4));

        list = (List)exec("int y = 2; List z = new ArrayList(); String s = 'aaa'; List x = [y, z, 1 + s, s + 'aaa']; return x;");

        assertEquals(4, list.size());
        assertEquals(new Integer(2), list.get(0));
        assertEquals(new ArrayList(),  list.get(1));
        assertEquals("1aaa",  list.get(2));
        assertEquals("aaaaaa", list.get(3));
    }

    public void testMapInitializers() {
        Map map = (Map)exec("[:]");

        assertEquals(0, map.size());

        map = (Map)exec("[5 : 7, -1 : 14]");

        assertEquals(2, map.size());
        assertEquals(new Integer(7), map.get(5));
        assertEquals(new Integer(14), map.get(-1));

        map = (Map)exec("int y = 2; int z = 3; Map x = [y*z : y + z, y - z : y, z : z]; return x;");

        assertEquals(3, map.size());
        assertEquals(new Integer(5), map.get(6));
        assertEquals(new Integer(2), map.get(-1));
        assertEquals(new Integer(3), map.get(3));

        map = (Map)exec("int y = 2; List z = new ArrayList(); String s = 'aaa';" +
            "def x = [y : z, 1 + s : s + 'aaa']; return x;");

        assertEquals(2, map.size());
        assertEquals(new ArrayList(), map.get(2));
        assertEquals("aaaaaa", map.get("1aaa"));
    }

    public void testCrazyInitializer() {
        Map map = (Map)exec("int y = 2; int z = 3; Map x = [y*z : y + z, 's' : [y, [y : [[z], [], [:]]]], z : [z, 9]]; return x;");

        List<Object> list0 = new ArrayList<>();
        list0.add(3);
        List<Object> list1 = new ArrayList();
        list1.add(list0);
        list1.add(new ArrayList());
        list1.add(new HashMap());
        Map<Object, Object> map0 = new HashMap<>();
        map0.put(2, list1);
        List<Object> list2 = new ArrayList();
        list2.add(2);
        list2.add(map0);

        List<Object> list3 = new ArrayList<>();
        list3.add(3);
        list3.add(9);

        assertEquals(3, map.size());
        assertEquals(new Integer(5), map.get(6));
        assertEquals(list2, map.get("s"));
        assertEquals(list3, map.get(3));
    }
}
