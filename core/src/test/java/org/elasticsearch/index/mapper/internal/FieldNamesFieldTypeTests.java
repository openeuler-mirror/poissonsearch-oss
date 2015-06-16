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
package org.elasticsearch.index.mapper.internal;

import org.elasticsearch.index.mapper.FieldTypeTestCase;
import org.elasticsearch.index.mapper.MappedFieldType;

public class FieldNamesFieldTypeTests extends FieldTypeTestCase {
    @Override
    protected MappedFieldType createDefaultFieldType() {
        return new FieldNamesFieldMapper.FieldNamesFieldType();
    }

    @Override
    protected int numProperties() {
        return 1 + super.numProperties();
    }

    @Override
    protected void modifyProperty(MappedFieldType ft, int propNum) {
        FieldNamesFieldMapper.FieldNamesFieldType fnft = (FieldNamesFieldMapper.FieldNamesFieldType)ft;
        switch (propNum) {
            case 0: fnft.setEnabled(!fnft.isEnabled()); break;
            default: super.modifyProperty(ft, propNum - 1);
        }
    }
}
