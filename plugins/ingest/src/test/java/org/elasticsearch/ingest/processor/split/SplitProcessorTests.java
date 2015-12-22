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

package org.elasticsearch.ingest.processor.split;

import org.elasticsearch.ingest.IngestDocument;
import org.elasticsearch.ingest.RandomDocumentPicks;
import org.elasticsearch.ingest.processor.Processor;
import org.elasticsearch.test.ESTestCase;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

public class SplitProcessorTests extends ESTestCase {

    public void testSplit() throws Exception {
        IngestDocument ingestDocument = RandomDocumentPicks.randomIngestDocument(random());
        String fieldName = RandomDocumentPicks.addRandomField(random(), ingestDocument, "127.0.0.1");
        Processor processor = new SplitProcessor(fieldName, "\\.");
        processor.execute(ingestDocument);
        assertThat(ingestDocument.getFieldValue(fieldName, List.class), equalTo(Arrays.asList("127", "0", "0", "1")));
    }

    public void testSplitFieldNotFound() throws Exception {
        IngestDocument ingestDocument = RandomDocumentPicks.randomIngestDocument(random(), new HashMap<>());
        String fieldName = RandomDocumentPicks.randomFieldName(random());
        Processor processor = new SplitProcessor(fieldName, "\\.");
        try {
            processor.execute(ingestDocument);
            fail("split processor should have failed");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), containsString("not present as part of path [" + fieldName + "]"));
        }
    }

    public void testSplitNullValue() throws Exception {
        IngestDocument ingestDocument = RandomDocumentPicks.randomIngestDocument(random(), Collections.singletonMap("field", null));
        Processor processor = new SplitProcessor("field", "\\.");
        try {
            processor.execute(ingestDocument);
            fail("split processor should have failed");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), equalTo("field [field] is null, cannot split."));
        }
    }

    public void testSplitNonStringValue() throws Exception {
        IngestDocument ingestDocument = RandomDocumentPicks.randomIngestDocument(random(), new HashMap<>());
        String fieldName = RandomDocumentPicks.randomFieldName(random());
        ingestDocument.setFieldValue(fieldName, randomInt());
        Processor processor = new SplitProcessor(fieldName, "\\.");
        try {
            processor.execute(ingestDocument);
            fail("split processor should have failed");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), equalTo("field [" + fieldName + "] of type [java.lang.Integer] cannot be cast to [java.lang.String]"));
        }
    }
}
