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

package org.elasticsearch.common.xcontent;

import org.elasticsearch.common.Strings;
import org.elasticsearch.common.Unicode;
import org.elasticsearch.common.io.FastByteArrayOutputStream;
import org.elasticsearch.common.joda.time.DateTimeZone;
import org.elasticsearch.common.joda.time.ReadableInstant;
import org.elasticsearch.common.joda.time.format.DateTimeFormatter;
import org.elasticsearch.common.joda.time.format.ISODateTimeFormat;
import org.elasticsearch.common.xcontent.support.XContentMapConverter;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * @author kimchy (shay.banon)
 */
public final class XContentBuilder {

    public static enum FieldCaseConversion {
        /**
         * No came conversion will occur.
         */
        NONE,
        /**
         * Camel Case will be converted to Underscore casing.
         */
        UNDERSCORE,
        /**
         * Underscore will be converted to Camel case conversion.
         */
        CAMELCASE
    }

    public final static DateTimeFormatter defaultDatePrinter = ISODateTimeFormat.dateTime().withZone(DateTimeZone.UTC);

    protected static FieldCaseConversion globalFieldCaseConversion = FieldCaseConversion.NONE;

    public static void globalFieldCaseConversion(FieldCaseConversion globalFieldCaseConversion) {
        XContentBuilder.globalFieldCaseConversion = globalFieldCaseConversion;
    }

    private XContentGenerator generator;

    private final FastByteArrayOutputStream bos;

    private FieldCaseConversion fieldCaseConversion = globalFieldCaseConversion;

    private StringBuilder cachedStringBuilder;

    public static XContentBuilder cachedBuilder(XContent xContent) throws IOException {
        return new XContentBuilder(FastByteArrayOutputStream.Cached.cached(), xContent);
    }

    public static XContentBuilder builder(XContent xContent) throws IOException {
        return new XContentBuilder(new FastByteArrayOutputStream(), xContent);
    }

    public XContentBuilder(FastByteArrayOutputStream bos, XContent xContent) throws IOException {
        this.bos = bos;
        this.generator = xContent.createGenerator(bos);
    }

    public XContentBuilder fieldCaseConversion(FieldCaseConversion fieldCaseConversion) {
        this.fieldCaseConversion = fieldCaseConversion;
        return this;
    }

    public XContentType contentType() {
        return generator.contentType();
    }

    public XContentBuilder prettyPrint() {
        generator.usePrettyPrint();
        return this;
    }

    public XContentBuilder startObject(String name) throws IOException {
        field(name);
        startObject();
        return this;
    }

    public XContentBuilder startObject(String name, FieldCaseConversion conversion) throws IOException {
        field(name, conversion);
        startObject();
        return this;
    }

    public XContentBuilder startObject(XContentBuilderString name) throws IOException {
        field(name);
        startObject();
        return this;
    }

    public XContentBuilder startObject() throws IOException {
        generator.writeStartObject();
        return this;
    }

    public XContentBuilder endObject() throws IOException {
        generator.writeEndObject();
        return this;
    }

    public XContentBuilder array(String name, String... values) throws IOException {
        startArray(name);
        for (String value : values) {
            value(value);
        }
        endArray();
        return this;
    }

    public XContentBuilder array(XContentBuilderString name, String... values) throws IOException {
        startArray(name);
        for (String value : values) {
            value(value);
        }
        endArray();
        return this;
    }

    public XContentBuilder array(String name, Object... values) throws IOException {
        startArray(name);
        for (Object value : values) {
            value(value);
        }
        endArray();
        return this;
    }

    public XContentBuilder array(XContentBuilderString name, Object... values) throws IOException {
        startArray(name);
        for (Object value : values) {
            value(value);
        }
        endArray();
        return this;
    }

    public XContentBuilder startArray(String name, FieldCaseConversion conversion) throws IOException {
        field(name, conversion);
        startArray();
        return this;
    }

    public XContentBuilder startArray(String name) throws IOException {
        field(name);
        startArray();
        return this;
    }

    public XContentBuilder startArray(XContentBuilderString name) throws IOException {
        field(name);
        startArray();
        return this;
    }

    public XContentBuilder startArray() throws IOException {
        generator.writeStartArray();
        return this;
    }

    public XContentBuilder endArray() throws IOException {
        generator.writeEndArray();
        return this;
    }

    public XContentBuilder field(XContentBuilderString name) throws IOException {
        if (fieldCaseConversion == FieldCaseConversion.UNDERSCORE) {
            generator.writeFieldName(name.underscore());
        } else if (fieldCaseConversion == FieldCaseConversion.CAMELCASE) {
            generator.writeFieldName(name.camelCase());
        } else {
            generator.writeFieldName(name.underscore());
        }
        return this;
    }

    public XContentBuilder field(String name) throws IOException {
        if (fieldCaseConversion == FieldCaseConversion.UNDERSCORE) {
            if (cachedStringBuilder == null) {
                cachedStringBuilder = new StringBuilder();
            }
            name = Strings.toUnderscoreCase(name, cachedStringBuilder);
        } else if (fieldCaseConversion == FieldCaseConversion.CAMELCASE) {
            if (cachedStringBuilder == null) {
                cachedStringBuilder = new StringBuilder();
            }
            name = Strings.toCamelCase(name, cachedStringBuilder);
        }
        generator.writeFieldName(name);
        return this;
    }

    public XContentBuilder field(String name, FieldCaseConversion conversion) throws IOException {
        if (conversion == FieldCaseConversion.UNDERSCORE) {
            if (cachedStringBuilder == null) {
                cachedStringBuilder = new StringBuilder();
            }
            name = Strings.toUnderscoreCase(name, cachedStringBuilder);
        } else if (conversion == FieldCaseConversion.CAMELCASE) {
            if (cachedStringBuilder == null) {
                cachedStringBuilder = new StringBuilder();
            }
            name = Strings.toCamelCase(name, cachedStringBuilder);
        }
        generator.writeFieldName(name);
        return this;
    }

    public XContentBuilder field(String name, char[] value, int offset, int length) throws IOException {
        field(name);
        if (value == null) {
            generator.writeNull();
        } else {
            generator.writeString(value, offset, length);
        }
        return this;
    }

    public XContentBuilder field(XContentBuilderString name, char[] value, int offset, int length) throws IOException {
        field(name);
        if (value == null) {
            generator.writeNull();
        } else {
            generator.writeString(value, offset, length);
        }
        return this;
    }

    public XContentBuilder field(String name, String value) throws IOException {
        field(name);
        if (value == null) {
            generator.writeNull();
        } else {
            generator.writeString(value);
        }
        return this;
    }

    public XContentBuilder field(XContentBuilderString name, String value) throws IOException {
        field(name);
        if (value == null) {
            generator.writeNull();
        } else {
            generator.writeString(value);
        }
        return this;
    }

    public XContentBuilder field(String name, Integer value) throws IOException {
        return field(name, value.intValue());
    }

    public XContentBuilder field(XContentBuilderString name, Integer value) throws IOException {
        return field(name, value.intValue());
    }

    public XContentBuilder field(String name, int value) throws IOException {
        field(name);
        generator.writeNumber(value);
        return this;
    }

    public XContentBuilder field(XContentBuilderString name, int value) throws IOException {
        field(name);
        generator.writeNumber(value);
        return this;
    }

    public XContentBuilder field(String name, Long value) throws IOException {
        return field(name, value.longValue());
    }

    public XContentBuilder field(XContentBuilderString name, Long value) throws IOException {
        return field(name, value.longValue());
    }

    public XContentBuilder field(String name, long value) throws IOException {
        field(name);
        generator.writeNumber(value);
        return this;
    }

    public XContentBuilder field(XContentBuilderString name, long value) throws IOException {
        field(name);
        generator.writeNumber(value);
        return this;
    }

    public XContentBuilder field(String name, Float value) throws IOException {
        return field(name, value.floatValue());
    }

    public XContentBuilder field(XContentBuilderString name, Float value) throws IOException {
        return field(name, value.floatValue());
    }

    public XContentBuilder field(String name, float value) throws IOException {
        field(name);
        generator.writeNumber(value);
        return this;
    }

    public XContentBuilder field(XContentBuilderString name, float value) throws IOException {
        field(name);
        generator.writeNumber(value);
        return this;
    }

    public XContentBuilder field(String name, Double value) throws IOException {
        return field(name, value.doubleValue());
    }

    public XContentBuilder field(XContentBuilderString name, Double value) throws IOException {
        return field(name, value.doubleValue());
    }

    public XContentBuilder field(String name, double value) throws IOException {
        field(name);
        generator.writeNumber(value);
        return this;
    }

    public XContentBuilder field(XContentBuilderString name, double value) throws IOException {
        field(name);
        generator.writeNumber(value);
        return this;
    }

    public XContentBuilder field(String name, Map<String, Object> value) throws IOException {
        field(name);
        value(value);
        return this;
    }

    public XContentBuilder field(XContentBuilderString name, Map<String, Object> value) throws IOException {
        field(name);
        value(value);
        return this;
    }

    public XContentBuilder field(String name, List value) throws IOException {
        startArray(name);
        for (Object o : value) {
            value(o);
        }
        endArray();
        return this;
    }

    public XContentBuilder field(XContentBuilderString name, List value) throws IOException {
        startArray(name);
        for (Object o : value) {
            value(o);
        }
        endArray();
        return this;
    }

    public XContentBuilder field(String name, String... value) throws IOException {
        startArray(name);
        for (String o : value) {
            value(o);
        }
        endArray();
        return this;
    }

    public XContentBuilder field(XContentBuilderString name, String... value) throws IOException {
        startArray(name);
        for (String o : value) {
            value(o);
        }
        endArray();
        return this;
    }

    public XContentBuilder field(String name, Object... value) throws IOException {
        startArray(name);
        for (Object o : value) {
            value(o);
        }
        endArray();
        return this;
    }

    public XContentBuilder field(XContentBuilderString name, Object... value) throws IOException {
        startArray(name);
        for (Object o : value) {
            value(o);
        }
        endArray();
        return this;
    }

    public XContentBuilder field(String name, int... value) throws IOException {
        startArray(name);
        for (Object o : value) {
            value(o);
        }
        endArray();
        return this;
    }

    public XContentBuilder field(XContentBuilderString name, int... value) throws IOException {
        startArray(name);
        for (Object o : value) {
            value(o);
        }
        endArray();
        return this;
    }

    public XContentBuilder field(String name, long... value) throws IOException {
        startArray(name);
        for (Object o : value) {
            value(o);
        }
        endArray();
        return this;
    }

    public XContentBuilder field(XContentBuilderString name, long... value) throws IOException {
        startArray(name);
        for (Object o : value) {
            value(o);
        }
        endArray();
        return this;
    }

    public XContentBuilder field(String name, float... value) throws IOException {
        startArray(name);
        for (Object o : value) {
            value(o);
        }
        endArray();
        return this;
    }

    public XContentBuilder field(XContentBuilderString name, float... value) throws IOException {
        startArray(name);
        for (Object o : value) {
            value(o);
        }
        endArray();
        return this;
    }

    public XContentBuilder field(String name, double... value) throws IOException {
        startArray(name);
        for (Object o : value) {
            value(o);
        }
        endArray();
        return this;
    }

    public XContentBuilder field(XContentBuilderString name, double... value) throws IOException {
        startArray(name);
        for (Object o : value) {
            value(o);
        }
        endArray();
        return this;
    }

    public XContentBuilder field(String name, Object value) throws IOException {
        if (value == null) {
            nullField(name);
            return this;
        }
        Class type = value.getClass();
        if (type == String.class) {
            field(name, (String) value);
        } else if (type == Float.class) {
            field(name, ((Float) value).floatValue());
        } else if (type == Double.class) {
            field(name, ((Double) value).doubleValue());
        } else if (type == Integer.class) {
            field(name, ((Integer) value).intValue());
        } else if (type == Long.class) {
            field(name, ((Long) value).longValue());
        } else if (type == Boolean.class) {
            field(name, ((Boolean) value).booleanValue());
        } else if (type == Date.class) {
            field(name, (Date) value);
        } else if (type == byte[].class) {
            field(name, (byte[]) value);
        } else if (value instanceof ReadableInstant) {
            field(name, (ReadableInstant) value);
        } else if (value instanceof Map) {
            //noinspection unchecked
            field(name, (Map<String, Object>) value);
        } else if (value instanceof List) {
            field(name, (List) value);
        } else if (value instanceof Object[]) {
            field(name, (Object[]) value);
        } else if (value instanceof int[]) {
            field(name, (int[]) value);
        } else if (value instanceof long[]) {
            field(name, (long[]) value);
        } else if (value instanceof float[]) {
            field(name, (float[]) value);
        } else if (value instanceof double[]) {
            field(name, (double[]) value);
        } else {
            field(name, value.toString());
        }
        return this;
    }

    public XContentBuilder field(XContentBuilderString name, Object value) throws IOException {
        if (value == null) {
            nullField(name);
            return this;
        }
        Class type = value.getClass();
        if (type == String.class) {
            field(name, (String) value);
        } else if (type == Float.class) {
            field(name, ((Float) value).floatValue());
        } else if (type == Double.class) {
            field(name, ((Double) value).doubleValue());
        } else if (type == Integer.class) {
            field(name, ((Integer) value).intValue());
        } else if (type == Long.class) {
            field(name, ((Long) value).longValue());
        } else if (type == Boolean.class) {
            field(name, ((Boolean) value).booleanValue());
        } else if (type == Date.class) {
            field(name, (Date) value);
        } else if (type == byte[].class) {
            field(name, (byte[]) value);
        } else if (value instanceof ReadableInstant) {
            field(name, (ReadableInstant) value);
        } else if (value instanceof Map) {
            //noinspection unchecked
            field(name, (Map<String, Object>) value);
        } else if (value instanceof List) {
            field(name, (List) value);
        } else if (value instanceof Object[]) {
            field(name, (Object[]) value);
        } else if (value instanceof int[]) {
            field(name, (int[]) value);
        } else if (value instanceof long[]) {
            field(name, (long[]) value);
        } else if (value instanceof float[]) {
            field(name, (float[]) value);
        } else if (value instanceof double[]) {
            field(name, (double[]) value);
        } else {
            field(name, value.toString());
        }
        return this;
    }

    public XContentBuilder value(Object value) throws IOException {
        if (value == null) {
            return nullValue();
        }
        Class type = value.getClass();
        if (type == String.class) {
            value((String) value);
        } else if (type == Float.class) {
            value(((Float) value).floatValue());
        } else if (type == Double.class) {
            value(((Double) value).doubleValue());
        } else if (type == Integer.class) {
            value(((Integer) value).intValue());
        } else if (type == Long.class) {
            value(((Long) value).longValue());
        } else if (type == Boolean.class) {
            value((Boolean) value);
        } else if (type == byte[].class) {
            value((byte[]) value);
        } else if (type == Date.class) {
            value((Date) value);
        } else if (value instanceof ReadableInstant) {
            value((ReadableInstant) value);
        } else if (value instanceof Map) {
            //noinspection unchecked
            value((Map<String, Object>) value);
        } else {
            throw new IOException("Type not allowed [" + type + "]");
        }
        return this;
    }

    public XContentBuilder field(String name, boolean value) throws IOException {
        field(name);
        generator.writeBoolean(value);
        return this;
    }

    public XContentBuilder field(XContentBuilderString name, boolean value) throws IOException {
        field(name);
        generator.writeBoolean(value);
        return this;
    }

    public XContentBuilder field(String name, byte[] value) throws IOException {
        field(name);
        generator.writeBinary(value);
        return this;
    }

    public XContentBuilder field(XContentBuilderString name, byte[] value) throws IOException {
        field(name);
        generator.writeBinary(value);
        return this;
    }

    public XContentBuilder field(String name, ReadableInstant date) throws IOException {
        field(name);
        return value(date);
    }

    public XContentBuilder field(XContentBuilderString name, ReadableInstant date) throws IOException {
        field(name);
        return value(date);
    }

    public XContentBuilder field(String name, ReadableInstant date, DateTimeFormatter formatter) throws IOException {
        field(name);
        return value(date, formatter);
    }

    public XContentBuilder field(XContentBuilderString name, ReadableInstant date, DateTimeFormatter formatter) throws IOException {
        field(name);
        return value(date, formatter);
    }

    public XContentBuilder field(String name, Date date) throws IOException {
        field(name);
        return value(date);
    }

    public XContentBuilder field(XContentBuilderString name, Date date) throws IOException {
        field(name);
        return value(date);
    }

    public XContentBuilder field(String name, Date date, DateTimeFormatter formatter) throws IOException {
        field(name);
        return value(date, formatter);
    }

    public XContentBuilder field(XContentBuilderString name, Date date, DateTimeFormatter formatter) throws IOException {
        field(name);
        return value(date, formatter);
    }

    public XContentBuilder nullField(String name) throws IOException {
        generator.writeNullField(name);
        return this;
    }

    public XContentBuilder nullField(XContentBuilderString name) throws IOException {
        field(name);
        generator.writeNull();
        return this;
    }

    public XContentBuilder nullValue() throws IOException {
        generator.writeNull();
        return this;
    }

    public XContentBuilder rawField(String fieldName, byte[] content) throws IOException {
        generator.writeRawField(fieldName, content, bos);
        return this;
    }

    public XContentBuilder rawField(String fieldName, InputStream content) throws IOException {
        generator.writeRawField(fieldName, content, bos);
        return this;
    }

    public XContentBuilder value(Boolean value) throws IOException {
        return value(value.booleanValue());
    }

    public XContentBuilder value(boolean value) throws IOException {
        generator.writeBoolean(value);
        return this;
    }

    public XContentBuilder value(ReadableInstant date) throws IOException {
        return value(date, defaultDatePrinter);
    }

    public XContentBuilder value(ReadableInstant date, DateTimeFormatter dateTimeFormatter) throws IOException {
        return value(dateTimeFormatter.print(date));
    }

    public XContentBuilder value(Date date) throws IOException {
        return value(date, defaultDatePrinter);
    }

    public XContentBuilder value(Date date, DateTimeFormatter dateTimeFormatter) throws IOException {
        return value(dateTimeFormatter.print(date.getTime()));
    }

    public XContentBuilder value(Integer value) throws IOException {
        return value(value.intValue());
    }

    public XContentBuilder value(int value) throws IOException {
        generator.writeNumber(value);
        return this;
    }

    public XContentBuilder value(Long value) throws IOException {
        return value(value.longValue());
    }

    public XContentBuilder value(long value) throws IOException {
        generator.writeNumber(value);
        return this;
    }

    public XContentBuilder value(Float value) throws IOException {
        return value(value.floatValue());
    }

    public XContentBuilder value(float value) throws IOException {
        generator.writeNumber(value);
        return this;
    }

    public XContentBuilder value(Double value) throws IOException {
        return value(value.doubleValue());
    }

    public XContentBuilder value(double value) throws IOException {
        generator.writeNumber(value);
        return this;
    }

    public XContentBuilder value(String value) throws IOException {
        generator.writeString(value);
        return this;
    }

    public XContentBuilder value(byte[] value) throws IOException {
        generator.writeBinary(value);
        return this;
    }

    public XContentBuilder map(Map<String, Object> map) throws IOException {
        XContentMapConverter.writeMap(generator, map);
        return this;
    }

    public XContentBuilder value(Map<String, Object> map) throws IOException {
        XContentMapConverter.writeMap(generator, map);
        return this;
    }

    public XContentBuilder copyCurrentStructure(XContentParser parser) throws IOException {
        generator.copyCurrentStructure(parser);
        return this;
    }

    public XContentBuilder flush() throws IOException {
        generator.flush();
        return this;
    }

    public void close() {
        try {
            generator.close();
        } catch (IOException e) {
            // ignore
        }
    }

    public byte[] unsafeBytes() throws IOException {
        close();
        return bos.unsafeByteArray();
    }

    public int unsafeBytesLength() throws IOException {
        close();
        return bos.size();
    }

    public FastByteArrayOutputStream unsafeStream() throws IOException {
        close();
        return bos;
    }

    public byte[] copiedBytes() throws IOException {
        close();
        return bos.copiedByteArray();
    }

    public String string() throws IOException {
        close();
        return Unicode.fromBytes(bos.unsafeByteArray(), 0, bos.size());
    }
}
