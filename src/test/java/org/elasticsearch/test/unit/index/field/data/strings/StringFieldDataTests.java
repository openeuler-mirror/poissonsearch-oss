/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
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

package org.elasticsearch.test.unit.index.field.data.strings;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.index.field.data.FieldData;
import org.elasticsearch.index.field.data.strings.StringFieldData;
import org.testng.annotations.Test;

import java.util.ArrayList;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 *
 */
public class StringFieldDataTests {

    @Test
    public void stringFieldDataTests() throws Exception {
        Directory dir = new RAMDirectory();
        IndexWriter indexWriter = new IndexWriter(dir, new IndexWriterConfig(Lucene.VERSION, Lucene.STANDARD_ANALYZER));

        Document document = new Document();
        document.add(new TextField("svalue", "zzz", Field.Store.YES));
        document.add(new TextField("mvalue", "111", Field.Store.YES));
        indexWriter.addDocument(document);

        document = new Document();
        document.add(new TextField("svalue", "xxx", Field.Store.YES));
        document.add(new TextField("mvalue", "222 333", Field.Store.YES));
        indexWriter.addDocument(document);

        document = new Document();
        document.add(new TextField("mvalue", "333 444", Field.Store.YES));
        indexWriter.addDocument(document);

        document = new Document();
        document.add(new TextField("svalue", "aaa", Field.Store.YES));
        indexWriter.addDocument(document);

        document = new Document();
        document.add(new TextField("svalue", "aaa", Field.Store.YES));
        indexWriter.addDocument(document);

        AtomicReader reader = SlowCompositeReaderWrapper.wrap(DirectoryReader.open(indexWriter, false));

        StringFieldData sFieldData = StringFieldData.load(reader, "svalue");
        StringFieldData mFieldData = StringFieldData.load(reader, "mvalue");

        assertThat(sFieldData.fieldName(), equalTo("svalue"));
        assertThat(sFieldData.multiValued(), equalTo(false));

        assertThat(mFieldData.fieldName(), equalTo("mvalue"));
        assertThat(mFieldData.multiValued(), equalTo(true));

        // svalue
        assertThat(sFieldData.hasValue(0), equalTo(true));
        assertThat(sFieldData.value(0).utf8ToString(), equalTo("zzz"));
        assertThat(sFieldData.docFieldData(0).getValue().utf8ToString(), equalTo("zzz"));
        assertThat(sFieldData.values(0).length, equalTo(1));
        assertThat(sFieldData.docFieldData(0).getValues().length, equalTo(1));
        assertThat(sFieldData.values(0)[0].utf8ToString(), equalTo("zzz"));
        assertThat(sFieldData.docFieldData(0).getValues()[0].utf8ToString(), equalTo("zzz"));

        assertThat(sFieldData.hasValue(1), equalTo(true));
        assertThat(sFieldData.value(1).utf8ToString(), equalTo("xxx"));
        assertThat(sFieldData.values(1).length, equalTo(1));
        assertThat(sFieldData.values(1)[0].utf8ToString(), equalTo("xxx"));

        assertThat(sFieldData.hasValue(2), equalTo(false));

        assertThat(sFieldData.hasValue(3), equalTo(true));
        assertThat(sFieldData.value(3).utf8ToString(), equalTo("aaa"));
        assertThat(sFieldData.values(3).length, equalTo(1));
        assertThat(sFieldData.values(3)[0].utf8ToString(), equalTo("aaa"));

        assertThat(sFieldData.hasValue(4), equalTo(true));
        assertThat(sFieldData.value(4).utf8ToString(), equalTo("aaa"));
        assertThat(sFieldData.values(4).length, equalTo(1));
        assertThat(sFieldData.values(4)[0].utf8ToString(), equalTo("aaa"));

        // check order is correct
        final ArrayList<BytesRef> values = new ArrayList<BytesRef>();
        sFieldData.forEachValue(new FieldData.StringValueProc() {
            @Override
            public void onValue(BytesRef value) {
                values.add(value);
            }
        });
        assertThat(values.size(), equalTo(3));

        assertThat(values.get(0).utf8ToString(), equalTo("aaa"));
        assertThat(values.get(1).utf8ToString(), equalTo("xxx"));
        assertThat(values.get(2).utf8ToString(), equalTo("zzz"));

        // mvalue
        assertThat(mFieldData.hasValue(0), equalTo(true));
        assertThat(mFieldData.value(0).utf8ToString(), equalTo("111"));
        assertThat(mFieldData.values(0).length, equalTo(1));
        assertThat(mFieldData.values(0)[0].utf8ToString(), equalTo("111"));

        assertThat(mFieldData.hasValue(1), equalTo(true));
        assertThat(mFieldData.value(1).utf8ToString(), equalTo("222"));
        assertThat(mFieldData.values(1).length, equalTo(2));
        assertThat(mFieldData.values(1)[0].utf8ToString(), equalTo("222"));
        assertThat(mFieldData.values(1)[1].utf8ToString(), equalTo("333"));

        assertThat(mFieldData.hasValue(2), equalTo(true));
        assertThat(mFieldData.value(2).utf8ToString(), equalTo("333"));
        assertThat(mFieldData.values(2).length, equalTo(2));
        assertThat(mFieldData.values(2)[0].utf8ToString(), equalTo("333"));
        assertThat(mFieldData.values(2)[1].utf8ToString(), equalTo("444"));

        assertThat(mFieldData.hasValue(3), equalTo(false));

        assertThat(mFieldData.hasValue(4), equalTo(false));

        values.clear();
        mFieldData.forEachValue(new FieldData.StringValueProc() {
            @Override
            public void onValue(BytesRef value) {
                values.add(value);
            }
        });
        assertThat(values.size(), equalTo(4));

        assertThat(values.get(0).utf8ToString(), equalTo("111"));
        assertThat(values.get(1).utf8ToString(), equalTo("222"));
        assertThat(values.get(2).utf8ToString(), equalTo("333"));
        assertThat(values.get(3).utf8ToString(), equalTo("444"));

        indexWriter.close();
    }
}
