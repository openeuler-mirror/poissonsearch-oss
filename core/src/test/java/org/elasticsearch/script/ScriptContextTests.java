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

package org.elasticsearch.script;

import org.elasticsearch.test.ESTestCase;

public class ScriptContextTests extends ESTestCase {

    public interface TwoNewInstance {
        String newInstance(int foo, int bar);
        String newInstance(int foo);
    }

    public interface MissingNewInstance {
        String typoNewInstanceMethod(int foo);
    }

    public interface DummyScript {
        int execute(int foo);

        interface Compiled {
            DummyScript newInstance();
        }
    }

    public void testTwoNewInstanceMethods() {
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () ->
            new ScriptContext<>("test", TwoNewInstance.class));
        assertEquals("Cannot have multiple newInstance methods on CompiledType class ["
            + TwoNewInstance.class.getName() + "] for script context [test]", e.getMessage());
    }

    public void testMissingNewInstanceMethod() {
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () ->
            new ScriptContext<>("test", MissingNewInstance.class));
        assertEquals("Could not find method newInstance on CompiledType class ["
            + MissingNewInstance.class.getName() + "] for script context [test]", e.getMessage());
    }

    public void testInstanceTypeReflection() {
        ScriptContext<?> context = new ScriptContext<>("test", DummyScript.Compiled.class);
        assertEquals("test", context.name);
        assertEquals(DummyScript.class, context.instanceClazz);
        assertEquals(DummyScript.Compiled.class, context.compiledClazz);
    }
}
