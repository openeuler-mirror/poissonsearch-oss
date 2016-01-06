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

package org.elasticsearch.mapper.attachments;

import com.carrotsearch.randomizedtesting.annotations.Name;
import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.rest.ESRestTestCase;
import org.elasticsearch.test.rest.RestTestCandidate;
import org.elasticsearch.test.rest.parser.RestTestParseException;

import java.io.IOException;

public class MapperAttachmentsRestIT extends ESRestTestCase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put("plugin.types", MapperAttachmentsPlugin.class.getName())
                .build();
    }

    public MapperAttachmentsRestIT(@Name("yaml") RestTestCandidate testCandidate) {
        super(testCandidate);
    }

    @ParametersFactory
    public static Iterable<Object[]> parameters() throws IOException, RestTestParseException {
        return createParameters(0, 1);
    }
}

