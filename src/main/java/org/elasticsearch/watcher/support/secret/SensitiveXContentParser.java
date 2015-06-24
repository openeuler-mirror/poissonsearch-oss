/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.support.secret;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.xcontent.XContentLocation;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;

import java.io.IOException;
import java.util.Map;

/**
 *
 */
public class SensitiveXContentParser implements XContentParser {

    public static Secret secret(XContentParser parser) throws IOException {
        char[] chars = parser.text().toCharArray();
        if (parser instanceof SensitiveXContentParser) {
            chars = ((SensitiveXContentParser) parser).secretService.encrypt(chars);
            return new Secret(chars);
        }
        return new Secret(chars);
    }

    public static Secret secretOrNull(XContentParser parser) throws IOException {
        String text = parser.textOrNull();
        if (text == null) {
            return null;
        }
        char[] chars = parser.text().toCharArray();
        if (parser instanceof SensitiveXContentParser) {
            chars = ((SensitiveXContentParser) parser).secretService.encrypt(text.toCharArray());
            return new Secret(chars);
        }
        return new Secret(chars);
    }

    private final XContentParser parser;
    private final SecretService secretService;

    public SensitiveXContentParser(XContentParser parser, SecretService secretService) {
        this.parser = parser;
        this.secretService = secretService;
    }

    @Override
    public XContentType contentType() {
        return parser.contentType();
    }

    @Override
    public Token nextToken() throws IOException {
        return parser.nextToken();
    }

    @Override
    public void skipChildren() throws IOException {
        parser.skipChildren();
    }

    @Override
    public Token currentToken() {
        return parser.currentToken();
    }

    @Override
    public String currentName() throws IOException {
        return parser.currentName();
    }

    @Override
    public Map<String, Object> map() throws IOException {
        return parser.map();
    }

    @Override
    public Map<String, Object> mapOrdered() throws IOException {
        return parser.mapOrdered();
    }

    @Override
    public String text() throws IOException {
        return parser.text();
    }

    @Override
    public String textOrNull() throws IOException {
        return parser.textOrNull();
    }

    @Override
    public BytesRef utf8BytesOrNull() throws IOException {
        return parser.utf8BytesOrNull();
    }

    @Override
    public BytesRef utf8Bytes() throws IOException {
        return parser.utf8Bytes();
    }

    @Override
    public Object objectText() throws IOException {
        return parser.objectText();
    }

    @Override
    public Object objectBytes() throws IOException {
        return parser.objectBytes();
    }

    @Override
    public boolean hasTextCharacters() {
        return parser.hasTextCharacters();
    }

    @Override
    public char[] textCharacters() throws IOException {
        return parser.textCharacters();
    }

    @Override
    public int textLength() throws IOException {
        return parser.textLength();
    }

    @Override
    public int textOffset() throws IOException {
        return parser.textOffset();
    }

    @Override
    public Number numberValue() throws IOException {
        return parser.numberValue();
    }

    @Override
    public NumberType numberType() throws IOException {
        return parser.numberType();
    }

    @Override
    public boolean estimatedNumberType() {
        return parser.estimatedNumberType();
    }

    @Override
    public short shortValue(boolean coerce) throws IOException {
        return parser.shortValue(coerce);
    }

    @Override
    public int intValue(boolean coerce) throws IOException {
        return parser.intValue(coerce);
    }

    @Override
    public long longValue(boolean coerce) throws IOException {
        return parser.longValue(coerce);
    }

    @Override
    public float floatValue(boolean coerce) throws IOException {
        return parser.floatValue(coerce);
    }

    @Override
    public double doubleValue(boolean coerce) throws IOException {
        return parser.doubleValue(coerce);
    }

    @Override
    public short shortValue() throws IOException {
        return parser.shortValue();
    }

    @Override
    public int intValue() throws IOException {
        return parser.intValue();
    }

    @Override
    public long longValue() throws IOException {
        return parser.longValue();
    }

    @Override
    public float floatValue() throws IOException {
        return parser.floatValue();
    }

    @Override
    public double doubleValue() throws IOException {
        return parser.doubleValue();
    }

    @Override
    public boolean isBooleanValue() throws IOException {
        return parser.isBooleanValue();
    }

    @Override
    public boolean booleanValue() throws IOException {
        return parser.booleanValue();
    }

    @Override
    public byte[] binaryValue() throws IOException {
        return parser.binaryValue();
    }

    @Override
    public XContentLocation getTokenLocation() {
        return parser.getTokenLocation();
    }

    @Override
    public void close() throws ElasticsearchException {
        parser.close();
    }
}
