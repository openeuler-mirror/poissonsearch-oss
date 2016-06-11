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

package org.elasticsearch.painless.node;

import org.elasticsearch.painless.Definition;
import org.elasticsearch.painless.FunctionRef;
import org.elasticsearch.painless.Location;
import org.elasticsearch.painless.MethodWriter;
import org.elasticsearch.painless.Definition.Method;
import org.elasticsearch.painless.Definition.MethodKey;
import org.elasticsearch.painless.Locals;
import org.objectweb.asm.Type;

import static org.elasticsearch.painless.WriterConstants.LAMBDA_BOOTSTRAP_HANDLE;

import java.lang.invoke.LambdaMetafactory;
import java.lang.reflect.Modifier;

/**
 * Represents a function reference.
 */
public class EFunctionRef extends AExpression {
    public final String type;
    public final String call;

    private FunctionRef ref;

    public EFunctionRef(Location location, String type, String call) {
        super(location);

        this.type = type;
        this.call = call;
    }

    @Override
    void analyze(Locals locals) {
        if (expected == null) {
            ref = null;
            actual = Definition.getType("String");
        } else {
            try {
                if ("this".equals(type)) {
                    // user's own function
                    Method interfaceMethod = expected.struct.getFunctionalMethod();
                    if (interfaceMethod == null) {
                        throw new IllegalArgumentException("Cannot convert function reference [" + type + "::" + call + "] " +
                                                           "to [" + expected.name + "], not a functional interface");
                    }
                    Method implMethod = locals.getMethod(new MethodKey(call, interfaceMethod.arguments.size()));
                    if (implMethod == null) {
                        throw new IllegalArgumentException("Cannot convert function reference [" + type + "::" + call + "] " +
                                                           "to [" + expected.name + "], function not found");
                    }
                    ref = new FunctionRef(expected, interfaceMethod, implMethod);
                } else {
                    // whitelist lookup
                    ref = new FunctionRef(expected, type, call);
                }
            } catch (IllegalArgumentException e) {
                throw createError(e);
            }
            actual = expected;
        }
    }

    @Override
    void write(MethodWriter writer) {
        if (ref == null) {
            writer.push("S" + type + "." + call + ",0");
        } else {
            writer.writeDebugInfo(location);
            // convert MethodTypes to asm Type for the constant pool.
            String invokedType = ref.invokedType.toMethodDescriptorString();
            Type samMethodType = Type.getMethodType(ref.samMethodType.toMethodDescriptorString());
            Type interfaceType = Type.getMethodType(ref.interfaceMethodType.toMethodDescriptorString());
            if (ref.needsBridges()) {
                writer.invokeDynamic(ref.invokedName,
                                     invokedType,
                                     LAMBDA_BOOTSTRAP_HANDLE,
                                     samMethodType,
                                     ref.implMethodASM,
                                     samMethodType,
                                     LambdaMetafactory.FLAG_BRIDGES,
                                     1,
                                     interfaceType);
            } else {
                writer.invokeDynamic(ref.invokedName,
                                     invokedType,
                                     LAMBDA_BOOTSTRAP_HANDLE,
                                     samMethodType,
                                     ref.implMethodASM,
                                     samMethodType,
                                     0);
            }
        }
    }
}
