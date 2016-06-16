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

public class LambdaTests extends ScriptTestCase {

    public void testNoArgLambda() {
        assertEquals(1, exec("Optional.empty().orElseGet(() -> { return 1; });"));
    }
    
    public void testNoArgLambdaDef() {
        assertEquals(1, exec("def x = Optional.empty(); x.orElseGet(() -> { return 1; });"));
    }
    
    public void testLambdaWithArgs() {
        assertEquals("short", exec("List l = new ArrayList(); l.add('looooong'); l.add('short'); " 
                                 + "l.sort((a, b) -> { a.length() - b.length(); }); return l.get(0)"));

    }
    
    public void testLambdaWithTypedArgs() {
        assertEquals("short", exec("List l = new ArrayList(); l.add('looooong'); l.add('short'); " 
                                 + "l.sort((String a, String b) -> { (a.length() - b.length()); }); return l.get(0)"));

    }
    
    public void testPrimitiveLambdas() {
        assertEquals(4, exec("List l = new ArrayList(); l.add(1); l.add(1); "
                           + "return l.stream().mapToInt(x -> { x + 1; }).sum();"));
    }
    
    public void testPrimitiveLambdasWithTypedArgs() {
        assertEquals(4, exec("List l = new ArrayList(); l.add(1); l.add(1); "
                           + "return l.stream().mapToInt(int x -> { x + 1; }).sum();"));
    }
    
    public void testPrimitiveLambdasDef() {
        assertEquals(4, exec("def l = new ArrayList(); l.add(1); l.add(1); "
                           + "return l.stream().mapToInt(x -> { x + 1; }).sum();"));
    }
    
    public void testPrimitiveLambdasWithTypedArgsDef() {
        assertEquals(4, exec("def l = new ArrayList(); l.add(1); l.add(1); "
                           + "return l.stream().mapToInt(int x -> { x + 1; }).sum();"));
    }
    
    public void testPrimitiveLambdasConvertible() {
        assertEquals(2, exec("List l = new ArrayList(); l.add(1); l.add(1); "
                           + "return l.stream().mapToInt(byte x -> { return x; }).sum();"));
    }
    
    public void testPrimitiveArgs() {
        assertEquals(2, exec("int applyOne(IntFunction arg) { arg.apply(1) } applyOne(x -> { x + 1; })"));
    }
    
    public void testPrimitiveArgsTyped() {
        assertEquals(2, exec("int applyOne(IntFunction arg) { arg.apply(1) } applyOne(int x -> { x + 1; })"));
    }
    
    public void testPrimitiveArgsTypedOddly() {
        assertEquals(2L, exec("long applyOne(IntFunction arg) { arg.apply(1) } applyOne(long x -> { x + 1; })"));
    }
}
