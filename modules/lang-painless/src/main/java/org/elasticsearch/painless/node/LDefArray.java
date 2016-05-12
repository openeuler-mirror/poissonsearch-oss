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

import org.elasticsearch.painless.CompilerSettings;
import org.elasticsearch.painless.Definition;
import org.elasticsearch.painless.DynamicCallSite;
import org.elasticsearch.painless.Variables;
import org.objectweb.asm.commons.GeneratorAdapter;

import static org.elasticsearch.painless.WriterConstants.DEF_BOOTSTRAP_HANDLE;
import static org.elasticsearch.painless.WriterConstants.DEF_DYNAMIC_ARRAY_LOAD_DESC;
import static org.elasticsearch.painless.WriterConstants.DEF_DYNAMIC_ARRAY_STORE_DESC;

/**
 * Represents an array load/store or shortcut on a def type.  (Internal only.)
 */
final class LDefArray extends ALink {

    AExpression index;

    LDefArray(final int line, final String location, final AExpression index) {
        super(line, location, 0);

        this.index = index;
    }


    @Override
    ALink analyze(final CompilerSettings settings, final Definition definition, final Variables variables) {
        index.expected = definition.objectType;
        index.analyze(settings, definition, variables);
        index = index.cast(settings, definition, variables);

        after = definition.defType;

        return this;
    }

    @Override
    void write(final CompilerSettings settings, final Definition definition, final GeneratorAdapter adapter) {
        index.write(settings, definition, adapter);
    }

    @Override
    void load(final CompilerSettings settings, final Definition definition, final GeneratorAdapter adapter) {
        adapter.visitInvokeDynamicInsn(
            "arrayLoad", DEF_DYNAMIC_ARRAY_LOAD_DESC, DEF_BOOTSTRAP_HANDLE, new Object[] { DynamicCallSite.ARRAY_LOAD });

    }

    @Override
    void store(final CompilerSettings settings, final Definition definition, final GeneratorAdapter adapter) {
        adapter.visitInvokeDynamicInsn(
            "arrayStore", DEF_DYNAMIC_ARRAY_STORE_DESC, DEF_BOOTSTRAP_HANDLE, new Object[] { DynamicCallSite.ARRAY_STORE });
    }
}
