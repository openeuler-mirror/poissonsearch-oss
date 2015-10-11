/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authz.accesscontrol;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.TestUtil;
import org.elasticsearch.index.cache.bitset.BitsetFilterCache;
import org.elasticsearch.test.ESTestCase;
import org.junit.After;
import org.junit.Before;
import org.mockito.Matchers;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DocumentSubsetReaderTests extends ESTestCase {

    private Directory directory;
    private DirectoryReader directoryReader;
    private BitsetFilterCache bitsetFilterCache;

    @Before
    public void before() {
        directory = newDirectory();
        bitsetFilterCache = mock(BitsetFilterCache.class);
        when(bitsetFilterCache.getBitSetProducer(Matchers.any(Query.class))).then(invocationOnMock -> {
            final Query query = (Query) invocationOnMock.getArguments()[0];
            return (BitSetProducer) context -> {
                IndexReaderContext topLevelContext = ReaderUtil.getTopLevelContext(context);
                IndexSearcher searcher = new IndexSearcher(topLevelContext);
                searcher.setQueryCache(null);
                Weight weight = searcher.createNormalizedWeight(query, false);
                DocIdSetIterator it = weight.scorer(context);
                return BitSet.of(it, context.reader().maxDoc());
            };
        });
    }

    @After
    public void after() throws Exception {
        if (directoryReader != null) {
            directoryReader.close();
        }
        directory.close();
    }

    public void testSearch() throws Exception {
        IndexWriter iw = new IndexWriter(directory, newIndexWriterConfig());

        Document document = new Document();
        document.add(new StringField("field", "value1", Field.Store.NO));
        iw.addDocument(document);

        document = new Document();
        document.add(new StringField("field", "value2", Field.Store.NO));
        iw.addDocument(document);

        document = new Document();
        document.add(new StringField("field", "value3", Field.Store.NO));
        iw.addDocument(document);

        document = new Document();
        document.add(new StringField("field", "value4", Field.Store.NO));
        iw.addDocument(document);

        iw.forceMerge(1);
        iw.deleteDocuments(new Term("field", "value3"));
        iw.close();
        directoryReader = DirectoryReader.open(directory);

        IndexSearcher indexSearcher = new IndexSearcher(DocumentSubsetReader.wrap(directoryReader, bitsetFilterCache, new TermQuery(new Term("field", "value1"))));
        assertThat(indexSearcher.getIndexReader().numDocs(), equalTo(1));
        TopDocs result = indexSearcher.search(new MatchAllDocsQuery(), 1);
        assertThat(result.totalHits, equalTo(1));
        assertThat(result.scoreDocs[0].doc, equalTo(0));

        indexSearcher = new IndexSearcher(DocumentSubsetReader.wrap(directoryReader, bitsetFilterCache, new TermQuery(new Term("field", "value2"))));
        assertThat(indexSearcher.getIndexReader().numDocs(), equalTo(1));
        result = indexSearcher.search(new MatchAllDocsQuery(), 1);
        assertThat(result.totalHits, equalTo(1));
        assertThat(result.scoreDocs[0].doc, equalTo(1));

        // this doc has been marked as deleted:
        indexSearcher = new IndexSearcher(DocumentSubsetReader.wrap(directoryReader, bitsetFilterCache, new TermQuery(new Term("field", "value3"))));
        assertThat(indexSearcher.getIndexReader().numDocs(), equalTo(0));
        result = indexSearcher.search(new MatchAllDocsQuery(), 1);
        assertThat(result.totalHits, equalTo(0));

        indexSearcher = new IndexSearcher(DocumentSubsetReader.wrap(directoryReader, bitsetFilterCache, new TermQuery(new Term("field", "value4"))));
        assertThat(indexSearcher.getIndexReader().numDocs(), equalTo(1));
        result = indexSearcher.search(new MatchAllDocsQuery(), 1);
        assertThat(result.totalHits, equalTo(1));
        assertThat(result.scoreDocs[0].doc, equalTo(3));
    }

    public void testLiveDocs() throws Exception {
        int numDocs = scaledRandomIntBetween(16, 128);
        IndexWriter iw = new IndexWriter(
                directory,
                new IndexWriterConfig(new StandardAnalyzer()).setMergePolicy(NoMergePolicy.INSTANCE)
        );

        for (int i = 0; i < numDocs; i++) {
            Document document = new Document();
            document.add(new StringField("field", "value" + i, Field.Store.NO));
            iw.addDocument(document);
        }

        iw.forceMerge(1);
        iw.close();

        directoryReader = DirectoryReader.open(directory);
        assertThat("should have one segment after force merge", directoryReader.leaves().size(), equalTo(1));

        for (int i = 0; i < numDocs; i++) {
            Query roleQuery = new TermQuery(new Term("field", "value" + i));
            DirectoryReader wrappedReader = DocumentSubsetReader.wrap(directoryReader, bitsetFilterCache, roleQuery);

            LeafReader leafReader = wrappedReader.leaves().get(0).reader();
            assertThat(leafReader.hasDeletions(), is(true));
            assertThat(leafReader.numDocs(), equalTo(1));
            Bits liveDocs = leafReader.getLiveDocs();
            assertThat(liveDocs.length(), equalTo(numDocs));
            for (int docId = 0; docId < numDocs; docId++) {
                if (docId == i) {
                    assertThat("docId [" + docId +"] should match", liveDocs.get(docId), is(true));
                } else {
                    assertThat("docId [" + docId +"] should not match", liveDocs.get(docId), is(false));
                }
            }
        }
    }

    public void testWrapTwice() throws Exception {
        Directory dir = newDirectory();
        IndexWriterConfig iwc = new IndexWriterConfig(null);
        IndexWriter iw = new IndexWriter(dir, iwc);
        iw.close();
        BitsetFilterCache bitsetFilterCache = mock(BitsetFilterCache.class);

        DirectoryReader directoryReader = DocumentSubsetReader.wrap(DirectoryReader.open(dir), bitsetFilterCache, new MatchAllDocsQuery());
        try {
            DocumentSubsetReader.wrap(directoryReader, bitsetFilterCache, new MatchAllDocsQuery());
            fail("shouldn't be able to wrap DocumentSubsetDirectoryReader twice");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), equalTo("Can't wrap [class org.elasticsearch.shield.authz.accesscontrol.DocumentSubsetReader$DocumentSubsetDirectoryReader] twice"));
        }

        directoryReader.close();
        dir.close();
    }

    /** Same test as in FieldSubsetReaderTests, test that core cache key (needed for NRT) is working */
    public void testCoreCacheKey() throws Exception {
        Directory dir = newDirectory();
        IndexWriterConfig iwc = new IndexWriterConfig(null);
        iwc.setMaxBufferedDocs(100);
        iwc.setMergePolicy(NoMergePolicy.INSTANCE);
        IndexWriter iw = new IndexWriter(dir, iwc);

        // add two docs, id:0 and id:1
        Document doc = new Document();
        Field idField = new StringField("id", "", Field.Store.NO);
        doc.add(idField);
        idField.setStringValue("0");
        iw.addDocument(doc);
        idField.setStringValue("1");
        iw.addDocument(doc);

        // open reader
        DirectoryReader ir = DocumentSubsetReader.wrap(DirectoryReader.open(iw, true), bitsetFilterCache, new MatchAllDocsQuery());
        assertEquals(2, ir.numDocs());
        assertEquals(1, ir.leaves().size());

        // delete id:0 and reopen
        iw.deleteDocuments(new Term("id", "0"));
        DirectoryReader ir2 = DirectoryReader.openIfChanged(ir);

        // we should have the same cache key as before
        assertEquals(1, ir2.numDocs());
        assertEquals(1, ir2.leaves().size());
        assertSame(ir.leaves().get(0).reader().getCoreCacheKey(), ir2.leaves().get(0).reader().getCoreCacheKey());

        // this is kind of stupid, but for now its here
        assertNotSame(ir.leaves().get(0).reader().getCombinedCoreAndDeletesKey(), ir2.leaves().get(0).reader().getCombinedCoreAndDeletesKey());

        TestUtil.checkReader(ir);
        IOUtils.close(ir, ir2, iw, dir);
    }
}
