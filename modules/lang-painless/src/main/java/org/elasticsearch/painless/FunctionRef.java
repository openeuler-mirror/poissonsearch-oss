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

import org.elasticsearch.painless.Definition.Method;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;

/** 
 * Reference to a function or lambda. 
 * <p>
 * Once you have created one of these, you have "everything you need" to call LambdaMetaFactory
 * either statically from bytecode with invokedynamic, or at runtime from Java.  
 */
public class FunctionRef {
    /** Function Object's method name */
    public final String invokedName;
    /** CallSite signature */
    public final MethodType invokedType;
    /** Implementation method */
    public final MethodHandle implMethod;
    /** Function Object's method signature */
    public final MethodType samMethodType;
    /** When bridging is required, request this bridge interface */
    public final MethodType interfaceMethodType;
    
    /** ASM "Handle" to the method, for the constant pool */
    public final Handle implMethodASM;
    
    /**
     * Creates a new FunctionRef.
     * @param expected interface type to implement.
     * @param type the left hand side of a method reference expression
     * @param call the right hand side of a method reference expression
     */
    public FunctionRef(Definition.Type expected, String type, String call) {
        boolean isCtorReference = "new".equals(call);
        // check its really a functional interface
        // for e.g. Comparable
        Method method = expected.struct.getFunctionalMethod();
        if (method == null) {
            throw new IllegalArgumentException("Cannot convert function reference [" + type + "::" + call + "] " +
                                               "to [" + expected.name + "], not a functional interface");
        }
        // e.g. compareTo
        invokedName = method.name;
        // e.g. (Object)Comparator
        invokedType = MethodType.methodType(expected.clazz);
        // e.g. (Object,Object)int
        interfaceMethodType = method.handle.type().dropParameterTypes(0, 1);
        // lookup requested method
        Definition.Struct struct = Definition.getType(type).struct;
        final Definition.Method impl;
        // ctor ref
        if (isCtorReference) {
            impl = struct.constructors.get(new Definition.MethodKey("<init>", method.arguments.size()));
        } else {
            // look for a static impl first
            Definition.Method staticImpl = struct.staticMethods.get(new Definition.MethodKey(call, method.arguments.size()));
            if (staticImpl == null) {
                // otherwise a virtual impl
                impl = struct.methods.get(new Definition.MethodKey(call, method.arguments.size()-1));
            } else {
                impl = staticImpl;
            }
        }
        if (impl == null) {
            throw new IllegalArgumentException("Unknown reference [" + type + "::" + call + "] matching " +
                                               "[" + expected + "]");
        }
        
        final int tag;
        if (isCtorReference) {
            tag = Opcodes.H_NEWINVOKESPECIAL;
        } else if (Modifier.isStatic(impl.modifiers)) {
            tag = Opcodes.H_INVOKESTATIC;
        } else {
            tag = Opcodes.H_INVOKEVIRTUAL;
        }
        implMethodASM = new Handle(tag, struct.type.getInternalName(), impl.name, impl.method.getDescriptor());
        implMethod = impl.handle;
        if (isCtorReference) {
            samMethodType = MethodType.methodType(interfaceMethodType.returnType(), impl.handle.type().parameterArray());
        } else if (Modifier.isStatic(impl.modifiers)) {
            samMethodType = impl.handle.type();
        } else {
            // ensure the receiver type is exact and not a superclass type
            samMethodType = impl.handle.type().changeParameterType(0, struct.clazz);
        }
    }
    
    /** Returns true if you should ask LambdaMetaFactory to construct a bridge for the interface signature */
    public boolean needsBridges() {
        // currently if the interface differs, we ask for a bridge, but maybe we should do smarter checking?
        // either way, stuff will fail if its wrong :)
        return interfaceMethodType.equals(samMethodType) == false;
    }
}
