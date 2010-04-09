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

package org.elasticsearch.util.json;

import org.apache.lucene.util.UnicodeUtil;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.util.ThreadLocals;
import org.elasticsearch.util.Unicode;
import org.elasticsearch.util.concurrent.NotThreadSafe;
import org.elasticsearch.util.io.FastCharArrayWriter;

import java.io.IOException;

/**
 * @author kimchy (Shay Banon)
 */
@NotThreadSafe
public class StringJsonBuilder extends JsonBuilder<StringJsonBuilder> {

    /**
     * A thread local based cache of {@link StringJsonBuilder}.
     */
    public static class Cached {

        private static final ThreadLocal<ThreadLocals.CleanableValue<StringJsonBuilder>> cache = new ThreadLocal<ThreadLocals.CleanableValue<StringJsonBuilder>>() {
            @Override protected ThreadLocals.CleanableValue<StringJsonBuilder> initialValue() {
                try {
                    StringJsonBuilder builder = new StringJsonBuilder();
                    builder.cachedStringBuilder = new StringBuilder();
                    return new ThreadLocals.CleanableValue<StringJsonBuilder>(builder);
                } catch (IOException e) {
                    throw new ElasticSearchException("Failed to create json generator", e);
                }
            }
        };

        /**
         * Returns the cached thread local generator, with its internal {@link StringBuilder} cleared.
         */
        static StringJsonBuilder cached() throws IOException {
            StringJsonBuilder sb = cache.get().get();
            sb.reset();
            return sb;
        }
    }

    private final FastCharArrayWriter writer;

    private final JsonFactory factory;

    final UnicodeUtil.UTF8Result utf8Result = new UnicodeUtil.UTF8Result();

    private StringBuilder cachedStringBuilder;

    public StringJsonBuilder() throws IOException {
        this(Jackson.defaultJsonFactory());
    }

    public StringJsonBuilder(JsonFactory factory) throws IOException {
        this.writer = new FastCharArrayWriter();
        this.factory = factory;
        this.generator = factory.createJsonGenerator(writer);
        this.builder = this;
    }

    public StringJsonBuilder(JsonGenerator generator) throws IOException {
        this.writer = new FastCharArrayWriter();
        this.generator = generator;
        this.factory = null;
        this.builder = this;
    }

    @Override protected StringBuilder cachedStringBuilder() {
        return cachedStringBuilder;
    }

    @Override public StringJsonBuilder raw(byte[] json) throws IOException {
        flush();
        Unicode.UTF16Result result = Unicode.unsafeFromBytesAsUtf16(json);
        writer.write(result.result, 0, result.length);
        return this;
    }

    public StringJsonBuilder reset() throws IOException {
        fieldCaseConversion = globalFieldCaseConversion;
        writer.reset();
        generator = factory.createJsonGenerator(writer);
        return this;
    }

    public String string() throws IOException {
        flush();
        return writer.toStringTrim();
    }

    public FastCharArrayWriter unsafeChars() throws IOException {
        flush();
        return writer;
    }

    @Override public byte[] unsafeBytes() throws IOException {
        return utf8().result;
    }

    /**
     * Call this AFTER {@link #unsafeBytes()}.
     */
    @Override public int unsafeBytesLength() {
        return utf8Result.length;
    }

    @Override public byte[] copiedBytes() throws IOException {
        flush();
        byte[] ret = new byte[utf8Result.length];
        System.arraycopy(utf8Result.result, 0, ret, 0, ret.length);
        return ret;
    }

    /**
     * Returns the byte[] that represents the utf8 of the json written up until now.
     * Note, the result is shared within this instance, so copy the byte array if needed
     * or use {@link #utf8copied()}.
     */
    public UnicodeUtil.UTF8Result utf8() throws IOException {
        flush();

        // ignore whitepsaces
        int st = 0;
        int len = writer.size();
        char[] val = writer.unsafeCharArray();

        while ((st < len) && (val[st] <= ' ')) {
            st++;
            len--;
        }
        while ((st < len) && (val[len - 1] <= ' ')) {
            len--;
        }

        UnicodeUtil.UTF16toUTF8(val, st, len, utf8Result);

        return utf8Result;
    }

    /**
     * Returns a copied byte[] that represnts the utf8 o fthe json written up until now.
     */
    public byte[] utf8copied() throws IOException {
        utf8();
        byte[] result = new byte[utf8Result.length];
        System.arraycopy(utf8Result.result, 0, result, 0, utf8Result.length);
        return result;
    }
}