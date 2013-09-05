/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.elasticsearch.test.unit.index.fielddata.ordinals;

import org.apache.lucene.util.LongsRef;
import org.apache.lucene.util.packed.PackedInts;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.index.fielddata.ordinals.MultiOrdinals;
import org.elasticsearch.index.fielddata.ordinals.Ordinals;
import org.elasticsearch.index.fielddata.ordinals.OrdinalsBuilder;
import org.elasticsearch.test.integration.ElasticsearchTestCase;
import org.junit.Test;

import java.io.IOException;
import java.util.*;

import static org.hamcrest.Matchers.equalTo;

/**
 */
public class MultiOrdinalsTests extends ElasticsearchTestCase {

    protected final Ordinals creationMultiOrdinals(OrdinalsBuilder builder) {
        return this.creationMultiOrdinals(builder, ImmutableSettings.builder());
    }


    protected Ordinals creationMultiOrdinals(OrdinalsBuilder builder, ImmutableSettings.Builder settings) {
        return builder.build(settings.build());
    }


    @Test
    public void testRandomValues() throws IOException {
        Random random = getRandom();
        int numDocs = 100 + random.nextInt(1000);
        int numOrdinals = 1 + random.nextInt(200);
        int numValues = 100 + random.nextInt(100000);
        OrdinalsBuilder builder = new OrdinalsBuilder(numDocs);
        Set<OrdAndId> ordsAndIdSet = new HashSet<OrdAndId>();
        for (int i = 0; i < numValues; i++) {
            ordsAndIdSet.add(new OrdAndId(1 + random.nextInt(numOrdinals), random.nextInt(numDocs)));
        }
        List<OrdAndId> ordsAndIds = new ArrayList<OrdAndId>(ordsAndIdSet);
        Collections.sort(ordsAndIds, new Comparator<OrdAndId>() {

            @Override
            public int compare(OrdAndId o1, OrdAndId o2) {
                if (o1.ord < o2.ord) {
                    return -1;
                }
                if (o1.ord == o2.ord) {
                    if (o1.id < o2.id) {
                        return -1;
                    }
                    if (o1.id > o2.id) {
                        return 1;
                    }
                    return 0;
                }
                return 1;
            }
        });
        long lastOrd = -1;
        for (OrdAndId ordAndId : ordsAndIds) {
            if (lastOrd != ordAndId.ord) {
                lastOrd = ordAndId.ord;
                builder.nextOrdinal();
            }
            ordAndId.ord = builder.currentOrdinal(); // remap the ordinals in case we have gaps?
            builder.addDoc(ordAndId.id);
        }

        Collections.sort(ordsAndIds, new Comparator<OrdAndId>() {

            @Override
            public int compare(OrdAndId o1, OrdAndId o2) {
                if (o1.id < o2.id) {
                    return -1;
                }
                if (o1.id == o2.id) {
                    if (o1.ord < o2.ord) {
                        return -1;
                    }
                    if (o1.ord > o2.ord) {
                        return 1;
                    }
                    return 0;
                }
                return 1;
            }
        });
        Ordinals ords = creationMultiOrdinals(builder);
        Ordinals.Docs docs = ords.ordinals();
        int docId = ordsAndIds.get(0).id;
        List<Long> docOrds = new ArrayList<Long>();
        for (OrdAndId ordAndId : ordsAndIds) {
            if (docId == ordAndId.id) {
                docOrds.add(ordAndId.ord);
            } else {
                if (!docOrds.isEmpty()) {
                    assertThat(docs.getOrd(docId), equalTo(docOrds.get(0)));
                    LongsRef ref = docs.getOrds(docId);
                    assertThat(ref.offset, equalTo(0));

                    for (int i = ref.offset; i < ref.length; i++) {
                        assertThat("index: " + i + " offset: " + ref.offset + " len: " + ref.length, ref.longs[i], equalTo(docOrds.get(i)));
                    }
                    final long[] array = new long[docOrds.size()];
                    for (int i = 0; i < array.length; i++) {
                        array[i] = docOrds.get(i);
                    }
                    assertIter(docs.getIter(docId), array);
                }
                for (int i = docId + 1; i < ordAndId.id; i++) {
                    assertThat(docs.getOrd(i), equalTo(0L));
                }
                docId = ordAndId.id;
                docOrds.clear();
                docOrds.add(ordAndId.ord);

            }
        }

    }

    public static class OrdAndId {
        long ord;
        final int id;

        public OrdAndId(long ord, int id) {
            this.ord = ord;
            this.id = id;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + id;
            result = prime * result + (int) ord;
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            OrdAndId other = (OrdAndId) obj;
            if (id != other.id) {
                return false;
            }
            if (ord != other.ord) {
                return false;
            }
            return true;
        }
    }

    @Test
    public void testOrdinals() throws Exception {
        int maxDoc = 7;
        long maxOrds = 32;
        OrdinalsBuilder builder = new OrdinalsBuilder(maxDoc);
        builder.nextOrdinal(); // 1
        builder.addDoc(1).addDoc(4).addDoc(5).addDoc(6);
        builder.nextOrdinal(); // 2
        builder.addDoc(0).addDoc(5).addDoc(6);
        builder.nextOrdinal(); // 3
        builder.addDoc(2).addDoc(4).addDoc(5).addDoc(6);
        builder.nextOrdinal(); // 4
        builder.addDoc(0).addDoc(4).addDoc(5).addDoc(6);
        builder.nextOrdinal(); // 5
        builder.addDoc(4).addDoc(5).addDoc(6);
        long ord = builder.nextOrdinal(); // 6
        builder.addDoc(4).addDoc(5).addDoc(6);
        for (long i = ord; i < maxOrds; i++) {
            builder.nextOrdinal();
            builder.addDoc(5).addDoc(6);
        }

        long[][] ordinalPlan = new long[][]{
                {2, 4},
                {1},
                {3},
                {},
                {1, 3, 4, 5, 6},
                {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32},
                {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32}
        };

        Ordinals ordinals = creationMultiOrdinals(builder);
        Ordinals.Docs docs = ordinals.ordinals();
        assertEquals(docs, ordinalPlan);
    }

    protected static void assertIter(Ordinals.Docs.Iter iter, long... expectedOrdinals) {
        for (long expectedOrdinal : expectedOrdinals) {
            assertThat(iter.next(), equalTo(expectedOrdinal));
        }
        assertThat(iter.next(), equalTo(0L)); // Last one should always be 0
        assertThat(iter.next(), equalTo(0L)); // Just checking it stays 0
    }

    @Test
    public void testMultiValuesDocsWithOverlappingStorageArrays() throws Exception {
        int maxDoc = 7;
        long maxOrds = 15;
        OrdinalsBuilder builder = new OrdinalsBuilder(maxDoc);
        for (int i = 0; i < maxOrds; i++) {
            builder.nextOrdinal();
            if (i < 10) {
                builder.addDoc(0);
            }
            builder.addDoc(1);
            if (i == 0) {
                builder.addDoc(2);
            }
            if (i < 5) {
                builder.addDoc(3);

            }
            if (i < 6) {
                builder.addDoc(4);

            }
            if (i == 1) {
                builder.addDoc(5);
            }
            if (i < 10) {
                builder.addDoc(6);
            }
        }

        long[][] ordinalPlan = new long[][]{
                {1, 2, 3, 4, 5, 6, 7, 8, 9, 10},
                {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
                {1},
                {1, 2, 3, 4, 5},
                {1, 2, 3, 4, 5, 6},
                {2},
                {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}
        };

        Ordinals ordinals = new MultiOrdinals(builder, PackedInts.FASTEST);
        Ordinals.Docs docs = ordinals.ordinals();
        assertEquals(docs, ordinalPlan);
    }

    private void assertEquals(Ordinals.Docs docs, long[][] ordinalPlan) {
        long numOrds = 0;
        for (int doc = 0; doc < ordinalPlan.length; ++doc) {
            if (ordinalPlan[doc].length > 0) {
                numOrds = Math.max(numOrds, ordinalPlan[doc][ordinalPlan[doc].length - 1]);
            }
        }
        assertThat(docs.getNumDocs(), equalTo(ordinalPlan.length));
        assertThat(docs.getNumOrds(), equalTo(numOrds)); // Includes null ord
        assertThat(docs.getMaxOrd(), equalTo(numOrds + 1));
        assertThat(docs.isMultiValued(), equalTo(true));
        for (int doc = 0; doc < ordinalPlan.length; ++doc) {
            LongsRef ref = docs.getOrds(doc);
            assertThat(ref.offset, equalTo(0));
            long[] ords = ordinalPlan[doc];
            assertThat(ref, equalTo(new LongsRef(ords, 0, ords.length)));
            assertIter(docs.getIter(doc), ords);
        }
    }

}
