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

package org.elasticsearch.index.mapper.json;

import org.apache.lucene.document.Document;
import org.codehaus.jackson.JsonParser;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.util.concurrent.NotThreadSafe;
import org.elasticsearch.util.lucene.all.AllEntries;

/**
 * @author kimchy (Shay Banon)
 */
@NotThreadSafe
public class JsonParseContext {

    private final JsonDocumentMapper docMapper;

    private final JsonPath path;

    private JsonParser jsonParser;

    private Document document;

    private String type;

    private byte[] source;

    private String id;

    private DocumentMapper.ParseListener listener;

    private String uid;

    private StringBuilder stringBuilder = new StringBuilder();

    private ParsedIdState parsedIdState;

    private boolean mappersAdded = false;

    private boolean externalValueSet;

    private Object externalValue;

    private AllEntries allEntries = new AllEntries();

    public JsonParseContext(JsonDocumentMapper docMapper, JsonPath path) {
        this.docMapper = docMapper;
        this.path = path;
    }

    public void reset(JsonParser jsonParser, Document document, String type, byte[] source, DocumentMapper.ParseListener listener) {
        this.jsonParser = jsonParser;
        this.document = document;
        this.type = type;
        this.source = source;
        this.path.reset();
        this.parsedIdState = ParsedIdState.NO;
        this.mappersAdded = false;
        this.listener = listener;
        this.allEntries = new AllEntries();
    }

    public boolean mappersAdded() {
        return this.mappersAdded;
    }

    public void addedMapper() {
        this.mappersAdded = true;
    }

    public String type() {
        return this.type;
    }

    public byte[] source() {
        return this.source;
    }

    public JsonPath path() {
        return this.path;
    }

    public JsonParser jp() {
        return this.jsonParser;
    }

    public DocumentMapper.ParseListener listener() {
        return this.listener;
    }

    public Document doc() {
        return this.document;
    }

    public JsonDocumentMapper docMapper() {
        return this.docMapper;
    }

    public String id() {
        return id;
    }

    public void parsedId(ParsedIdState parsedIdState) {
        this.parsedIdState = parsedIdState;
    }

    public ParsedIdState parsedIdState() {
        return this.parsedIdState;
    }

    /**
     * Really, just the id mapper should set this.
     */
    public void id(String id) {
        this.id = id;
    }

    public String uid() {
        return this.uid;
    }

    /**
     * Really, just the uid mapper should set this.
     */
    public void uid(String uid) {
        this.uid = uid;
    }

    public AllEntries allEntries() {
        return this.allEntries;
    }

    public void externalValue(Object externalValue) {
        this.externalValueSet = true;
        this.externalValue = externalValue;
    }

    public boolean externalValueSet() {
        return this.externalValueSet;
    }

    public Object externalValue() {
        externalValueSet = false;
        return externalValue;
    }

    /**
     * A string builder that can be used to construct complex names for example.
     * Its better to reuse the.
     */
    public StringBuilder stringBuilder() {
        stringBuilder.setLength(0);
        return this.stringBuilder;
    }

    public static enum ParsedIdState {
        NO,
        PARSED,
        EXTERNAL
    }
}
