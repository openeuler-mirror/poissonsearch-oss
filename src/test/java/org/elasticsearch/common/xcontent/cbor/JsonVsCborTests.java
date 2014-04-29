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

package org.elasticsearch.common.xcontent.cbor;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentGenerator;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.test.ElasticsearchTestCase;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 *
 */
public class JsonVsCborTests extends ElasticsearchTestCase {

    @Test
    public void testBugInJacksonCBOR() throws Exception {
        JsonFactory factory = new CBORFactory();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JsonGenerator generator = factory.createGenerator(out);
        generator.writeStartObject();
        generator.writeFieldName("field");
        generator.writeNumber(-1000000000001L);
        generator.writeEndObject();
        generator.close();

        JsonParser parser = factory.createParser(out.toByteArray());
        parser.nextToken();
        parser.nextToken();
        parser.nextToken();
        // this is the bug, if it gets fixed when upgrading to a new Jackson version
        // we should re-enable using CBOR in our randomized testing (ElasticsearchTestCase#randomXContentType)
        assertThat(parser.getLongValue(), equalTo(0L));
    }

    @Test
    public void compareParsingTokens() throws IOException {
        BytesStreamOutput xsonOs = new BytesStreamOutput();
        XContentGenerator xsonGen = XContentFactory.xContent(XContentType.CBOR).createGenerator(xsonOs);

        BytesStreamOutput jsonOs = new BytesStreamOutput();
        XContentGenerator jsonGen = XContentFactory.xContent(XContentType.JSON).createGenerator(jsonOs);

        xsonGen.writeStartObject();
        jsonGen.writeStartObject();

        xsonGen.writeStringField("test", "value");
        jsonGen.writeStringField("test", "value");

        xsonGen.writeArrayFieldStart("arr");
        jsonGen.writeArrayFieldStart("arr");
        xsonGen.writeNumber(1);
        jsonGen.writeNumber(1);
        xsonGen.writeNull();
        jsonGen.writeNull();
        xsonGen.writeEndArray();
        jsonGen.writeEndArray();

        xsonGen.writeEndObject();
        jsonGen.writeEndObject();

        xsonGen.close();
        jsonGen.close();

        verifySameTokens(XContentFactory.xContent(XContentType.JSON).createParser(jsonOs.bytes().toBytes()), XContentFactory.xContent(XContentType.CBOR).createParser(xsonOs.bytes().toBytes()));
    }

    private void verifySameTokens(XContentParser parser1, XContentParser parser2) throws IOException {
        while (true) {
            XContentParser.Token token1 = parser1.nextToken();
            XContentParser.Token token2 = parser2.nextToken();
            if (token1 == null) {
                assertThat(token2, nullValue());
                return;
            }
            assertThat(token1, equalTo(token2));
            switch (token1) {
                case FIELD_NAME:
                    assertThat(parser1.currentName(), equalTo(parser2.currentName()));
                    break;
                case VALUE_STRING:
                    assertThat(parser1.text(), equalTo(parser2.text()));
                    break;
                case VALUE_NUMBER:
                    assertThat(parser1.numberType(), equalTo(parser2.numberType()));
                    assertThat(parser1.numberValue(), equalTo(parser2.numberValue()));
                    break;
            }
        }
    }
}
