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
        assertEquals(1, exec("Optional.empty().orElseGet(() -> 1);"));
    }
    
    public void testNoArgLambdaDef() {
        assertEquals(1, exec("def x = Optional.empty(); x.orElseGet(() -> 1);"));
    }
    
    public void testLambdaWithArgs() {
        assertEquals("short", exec("List l = new ArrayList(); l.add('looooong'); l.add('short'); " 
                                 + "l.sort((a, b) -> a.length() - b.length()); return l.get(0)"));

    }
    
    public void testLambdaWithTypedArgs() {
        assertEquals("short", exec("List l = new ArrayList(); l.add('looooong'); l.add('short'); " 
                                 + "l.sort((String a, String b) -> a.length() - b.length()); return l.get(0)"));

    }
    
    public void testPrimitiveLambdas() {
        assertEquals(4, exec("List l = new ArrayList(); l.add(1); l.add(1); "
                           + "return l.stream().mapToInt(x -> x + 1).sum();"));
    }
    
    public void testPrimitiveLambdasWithTypedArgs() {
        assertEquals(4, exec("List l = new ArrayList(); l.add(1); l.add(1); "
                           + "return l.stream().mapToInt(int x -> x + 1).sum();"));
    }
    
    public void testPrimitiveLambdasDef() {
        assertEquals(4, exec("def l = new ArrayList(); l.add(1); l.add(1); "
                           + "return l.stream().mapToInt(x -> x + 1).sum();"));
    }
    
    public void testPrimitiveLambdasWithTypedArgsDef() {
        assertEquals(4, exec("def l = new ArrayList(); l.add(1); l.add(1); "
                           + "return l.stream().mapToInt(int x -> x + 1).sum();"));
    }
    
    public void testPrimitiveLambdasConvertible() {
        assertEquals(2, exec("List l = new ArrayList(); l.add(1); l.add(1); "
                           + "return l.stream().mapToInt(byte x -> x).sum();"));
    }
    
    public void testPrimitiveArgs() {
        assertEquals(2, exec("int applyOne(IntFunction arg) { arg.apply(1) } applyOne(x -> x + 1)"));
    }
    
    public void testPrimitiveArgsTyped() {
        assertEquals(2, exec("int applyOne(IntFunction arg) { arg.apply(1) } applyOne(int x -> x + 1)"));
    }
    
    public void testPrimitiveArgsTypedOddly() {
        assertEquals(2L, exec("long applyOne(IntFunction arg) { arg.apply(1) } applyOne(long x -> x + 1)"));
    }

    public void testMultipleStatements() {
        assertEquals(2, exec("int applyOne(IntFunction arg) { arg.apply(1) } applyOne(x -> { x = x + 1; return x })"));
    }

    public void testUnneededCurlyStatements() {
        assertEquals(2, exec("int applyOne(IntFunction arg) { arg.apply(1) } applyOne(x -> { x + 1 })"));
    }
    
    /** interface ignores return value */
    public void testVoidReturn() {
        assertEquals(2, exec("List list = new ArrayList(); "
                           + "list.add(2); "
                           + "List list2 = new ArrayList(); "
                           + "list.forEach(x -> list2.add(x));"
                           + "return list[0]"));
    }
    
    /** interface ignores return value */
    public void testVoidReturnDef() {
        assertEquals(2, exec("def list = new ArrayList(); "
                           + "list.add(2); "
                           + "List list2 = new ArrayList(); "
                           + "list.forEach(x -> list2.add(x));"
                           + "return list[0]"));
    }

    public void testTwoLambdas() {
        assertEquals("testingcdefg", exec(
                "org.elasticsearch.painless.FeatureTest test = new org.elasticsearch.painless.FeatureTest(2,3);" +
                "return test.twoFunctionsOfX(x -> 'testing'.concat(x), y -> 'abcdefg'.substring(y))"));
    }

    public void testNestedLambdas() {
        assertEquals(1, exec("Optional.empty().orElseGet(() -> Optional.empty().orElseGet(() -> 1));"));
    }

    public void testLambdaInLoop() {
        assertEquals(100, exec("int sum = 0; " +
                               "for (int i = 0; i < 100; i++) {" +
                               "  sum += Optional.empty().orElseGet(() -> 1);" +
                               "}" +
                               "return sum;"));
    }
    
    public void testCapture() {
        assertEquals(5, exec("int x = 5; return Optional.empty().orElseGet(() -> x);"));
    }
    
    public void testTwoCaptures() {
        assertEquals("1test", exec("int x = 1; String y = 'test'; return Optional.empty().orElseGet(() -> x + y);"));
    }
    
    public void testCapturesAreReadOnly() {
        IllegalArgumentException expected = expectScriptThrows(IllegalArgumentException.class, () -> {
            exec("List l = new ArrayList(); l.add(1); l.add(1); "
                    + "return l.stream().mapToInt(x -> { l = null; return x + 1 }).sum();");
        });
        assertTrue(expected.getMessage().contains("is read-only"));
    }
    
    public void testOnlyCapturesAreReadOnly() {
        assertEquals(4, exec("List l = new ArrayList(); l.add(1); l.add(1); "
                           + "return l.stream().mapToInt(x -> { x += 1; return x }).sum();"));
    }
    
    /** Lambda parameters shouldn't be able to mask a variable already in scope */
    public void testNoParamMasking() {
        IllegalArgumentException expected = expectScriptThrows(IllegalArgumentException.class, () -> {
            exec("int x = 0; List l = new ArrayList(); l.add(1); l.add(1); "
                    + "return l.stream().mapToInt(x -> { x += 1; return x }).sum();");
        });
        assertTrue(expected.getMessage().contains("already defined"));
    }

    public void testCaptureDef() {
        assertEquals(5, exec("int x = 5; def y = Optional.empty(); y.orElseGet(() -> x);"));
    }
    
    public void testNestedCapture() {
        assertEquals(1, exec("boolean x = false; int y = 1;" +
                             "return Optional.empty().orElseGet(() -> x ? 5 : Optional.empty().orElseGet(() -> y));"));
    }
    
    public void testNestedCaptureParams() {
        assertEquals(2, exec("int foo(Function f) { return f.apply(1) }" +
                             "return foo(x -> foo(y -> x + 1))"));
    }
    
    public void testWrongArity() {
        IllegalArgumentException expected = expectScriptThrows(IllegalArgumentException.class, () -> {
            exec("Optional.empty().orElseGet(x -> x);");
        });
        assertTrue(expected.getMessage().contains("Incorrect number of parameters"));
    }
    
    public void testWrongArityDef() {
        IllegalArgumentException expected = expectScriptThrows(IllegalArgumentException.class, () -> {
            exec("def y = Optional.empty(); return y.orElseGet(x -> x);");
        });
        assertTrue(expected.getMessage(), expected.getMessage().contains("Incorrect number of parameters"));
    }
    
    public void testWrongArityNotEnough() {
        IllegalArgumentException expected = expectScriptThrows(IllegalArgumentException.class, () -> {
            exec("List l = new ArrayList(); l.add(1); l.add(1); "
               + "return l.stream().mapToInt(() -> 5).sum();");
        });
        assertTrue(expected.getMessage().contains("Incorrect number of parameters"));
    }
    
    public void testWrongArityNotEnoughDef() {
        IllegalArgumentException expected = expectScriptThrows(IllegalArgumentException.class, () -> {
            exec("def l = new ArrayList(); l.add(1); l.add(1); "
               + "return l.stream().mapToInt(() -> 5).sum();");
        });
        assertTrue(expected.getMessage().contains("Incorrect number of parameters"));
    }
    
    public void testLambdaInFunction() {
        assertEquals(5, exec("def foo() { Optional.empty().orElseGet(() -> 5) } return foo();"));
    }
    
    public void testLambdaCaptureFunctionParam() {
        assertEquals(5, exec("def foo(int x) { Optional.empty().orElseGet(() -> x) } return foo(5);"));
    }
}
