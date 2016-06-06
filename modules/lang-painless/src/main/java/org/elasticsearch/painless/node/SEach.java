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

import org.elasticsearch.painless.Location;
import org.elasticsearch.painless.MethodWriter;
import org.elasticsearch.painless.Variables;

public class SEach extends AStatement {
    final String type;
    final String name;
    AExpression expression;
    AStatement block;

    public SEach(final Location location, final String type, final String name, final AExpression expression, final SBlock block) {
        super(location);

        this.type = type;
        this.name = name;
        this.expression = expression;
        this.block = block;
    }


    @Override
    AStatement analyze(Variables variables) {
        return null;
    }

    @Override
    void write(MethodWriter writer) {

    }
}
