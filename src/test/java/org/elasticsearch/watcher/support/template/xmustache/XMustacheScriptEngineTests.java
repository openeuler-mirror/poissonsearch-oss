/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.support.template.xmustache;

import com.carrotsearch.randomizedtesting.annotations.Repeat;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.test.ElasticsearchTestCase;
import org.junit.Before;
import org.junit.Test;

import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

/**
 *
 */
public class XMustacheScriptEngineTests extends ElasticsearchTestCase {

    private XMustacheScriptEngineService engine;

    @Before
    public void setup() {
        engine = new XMustacheScriptEngineService(ImmutableSettings.Builder.EMPTY_SETTINGS);
    }

    @Test
    public void testSimpleParameterReplace() {
        {
            String template = "__json__::GET _search {\"query\": " + "{\"boosting\": {" + "\"positive\": {\"match\": {\"body\": \"gift\"}},"
                    + "\"negative\": {\"term\": {\"body\": {\"value\": \"solr\"}" + "}}, \"negative_boost\": {{boost_val}} } }}";
            Map<String, Object> vars = new HashMap<>();
            vars.put("boost_val", "0.3");
            BytesReference o = (BytesReference) engine.execute(engine.compile(template), vars);
            assertEquals("GET _search {\"query\": {\"boosting\": {\"positive\": {\"match\": {\"body\": \"gift\"}},"
                            + "\"negative\": {\"term\": {\"body\": {\"value\": \"solr\"}}}, \"negative_boost\": 0.3 } }}",
                    new String(o.toBytes(), Charset.forName("UTF-8")));
        }
        {
            String template = "__json__::GET _search {\"query\": " + "{\"boosting\": {" + "\"positive\": {\"match\": {\"body\": \"gift\"}},"
                    + "\"negative\": {\"term\": {\"body\": {\"value\": \"{{body_val}}\"}" + "}}, \"negative_boost\": {{boost_val}} } }}";
            Map<String, Object> vars = new HashMap<>();
            vars.put("boost_val", "0.3");
            vars.put("body_val", "\"quick brown\"");
            BytesReference o = (BytesReference) engine.execute(engine.compile(template), vars);
            assertEquals("GET _search {\"query\": {\"boosting\": {\"positive\": {\"match\": {\"body\": \"gift\"}},"
                            + "\"negative\": {\"term\": {\"body\": {\"value\": \"\\\"quick brown\\\"\"}}}, \"negative_boost\": 0.3 } }}",
                    new String(o.toBytes(), Charset.forName("UTF-8")));
        }
    }

    @Test @Repeat(iterations = 100)
    public void testInvalidPrefixes() throws Exception {
        String[] specialStrings = new String[]{"\f", "\n", "\r", "\"", "\\", "\t", "\b", "__::", "__" };
        String prefix = randomFrom("", "__", "____::", "___::", "____", "::", "++json__::", "__json__", "+_json__::", "__json__:");
        String template = prefix + " {{test_var1}} {{test_var2}}";
        Map<String, Object> vars = new HashMap<>();
        Writer var1Writer = new StringWriter();
        Writer var2Writer = new StringWriter();

        for(int i = 0; i < scaledRandomIntBetween(10,1000); ++i) {
            var1Writer.write(randomRealisticUnicodeOfCodepointLengthBetween(0, 10));
            var2Writer.write(randomRealisticUnicodeOfCodepointLengthBetween(0, 10));
            var1Writer.append(randomFrom(specialStrings));
            var2Writer.append(randomFrom(specialStrings));
        }

        vars.put("test_var1", var1Writer.toString());
        vars.put("test_var2", var2Writer.toString());
        BytesReference o = (BytesReference) engine.execute(engine.compile(template), vars);
        String s1 = o.toUtf8();
        String s2 =  prefix + " " + var1Writer.toString() + " " + var2Writer.toString();
        assertEquals(s1, s2);
     }
}
