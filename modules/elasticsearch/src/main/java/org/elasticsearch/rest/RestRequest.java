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

package org.elasticsearch.rest;

import org.elasticsearch.util.SizeValue;
import org.elasticsearch.util.TimeValue;
import org.elasticsearch.util.json.ToJson;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author kimchy (Shay Banon)
 */
public interface RestRequest extends ToJson.Params {

    enum Method {
        GET, POST, PUT, DELETE
    }

    Method method();

    String uri();

    boolean hasContent();

    InputStream contentAsStream();

    byte[] contentAsBytes();

    String contentAsString();

    Set<String> headerNames();

    String header(String name);

    List<String> headers(String name);

    String cookie();

    boolean hasParam(String key);

    String param(String key);

    String[] paramAsStringArray(String key, String[] defaultValue);

    float paramAsFloat(String key, float defaultValue);

    int paramAsInt(String key, int defaultValue);

    boolean paramAsBoolean(String key, boolean defaultValue);

    Boolean paramAsBoolean(String key, Boolean defaultValue);

    TimeValue paramAsTime(String key, TimeValue defaultValue);

    SizeValue paramAsSize(String key, SizeValue defaultValue);

    List<String> params(String key);

    Map<String, List<String>> params();
}
