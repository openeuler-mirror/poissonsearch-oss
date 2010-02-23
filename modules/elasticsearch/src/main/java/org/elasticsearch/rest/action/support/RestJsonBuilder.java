/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

package org.elasticsearch.rest.action.support;

import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.util.json.BinaryJsonBuilder;

import java.io.IOException;

import static org.elasticsearch.util.json.JsonBuilder.*;

/**
 * @author kimchy (Shay Banon)
 */
public class RestJsonBuilder {

    public static BinaryJsonBuilder restJsonBuilder(RestRequest request) throws IOException {
        BinaryJsonBuilder builder = binaryJsonBuilder();
        if (request.paramAsBoolean("pretty", false)) {
            builder.prettyPrint();
        }
        return builder;
    }
}
