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

package org.elasticsearch.index.fielddata.plain;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.PagedBytes;
import org.apache.lucene.util.packed.PackedInts;
import org.elasticsearch.common.RamUsage;
import org.elasticsearch.common.lucene.HashedBytesRef;
import org.elasticsearch.index.fielddata.*;
import org.elasticsearch.index.fielddata.ordinals.Ordinals;
import org.elasticsearch.index.fielddata.util.BytesRefArrayRef;
import org.elasticsearch.index.fielddata.util.IntArrayRef;
import org.elasticsearch.index.fielddata.util.StringArrayRef;

/**
 */
public class PackedBytesAtomicFieldData implements AtomicOrdinalFieldData<ScriptDocValues.Strings> {

    // 0 ordinal in values means no value (its null)
    private final PagedBytes.Reader bytes;
    private final PackedInts.Reader termOrdToBytesOffset;
    private final Ordinals ordinals;

    private int[] hashes;
    private long size = -1;

    public PackedBytesAtomicFieldData(PagedBytes.Reader bytes, PackedInts.Reader termOrdToBytesOffset, Ordinals ordinals) {
        this.bytes = bytes;
        this.termOrdToBytesOffset = termOrdToBytesOffset;
        this.ordinals = ordinals;
    }

    @Override
    public boolean isMultiValued() {
        return ordinals.isMultiValued();
    }

    @Override
    public int getNumDocs() {
        return ordinals.getNumDocs();
    }

    @Override
    public boolean isValuesOrdered() {
        return true;
    }

    @Override
    public long getMemorySizeInBytes() {
        if (size == -1) {
            long size = ordinals.getMemorySizeInBytes();
            // PackedBytes
            size += RamUsage.NUM_BYTES_ARRAY_HEADER + bytes.getBlocks().length;
            for (byte[] b : bytes.getBlocks()) {
                size += b.length;
            }
            // PackedInts
            size += termOrdToBytesOffset.ramBytesUsed();
            this.size = size;
        }
        return size;
    }

    @Override
    public OrdinalsBytesValues getBytesValues() {
        return ordinals.isMultiValued() ? new BytesValues.Multi(bytes, termOrdToBytesOffset, ordinals.ordinals()) : new BytesValues.Single(bytes, termOrdToBytesOffset, ordinals.ordinals());
    }

    @Override
    public OrdinalsHashedBytesValues getHashedBytesValues() {
        if (hashes == null) {
            int numberOfValues = termOrdToBytesOffset.size();
            int[] hashes = new int[numberOfValues];
            BytesRef scratch = new BytesRef();
            for (int i = 0; i < numberOfValues; i++) {
                BytesRef value = bytes.fill(scratch, termOrdToBytesOffset.get(i));
                hashes[i] = value == null ? 0 : value.hashCode();
            }
            this.hashes = hashes;
        }
        return ordinals.isMultiValued() ? new HashedBytesValues.Multi(bytes, termOrdToBytesOffset, hashes, ordinals.ordinals()) : new HashedBytesValues.Single(bytes, termOrdToBytesOffset, hashes, ordinals.ordinals());
    }

    @Override
    public OrdinalsStringValues getStringValues() {
        return ordinals.isMultiValued() ? new StringValues.Multi(bytes, termOrdToBytesOffset, ordinals.ordinals()) : new StringValues.Single(bytes, termOrdToBytesOffset, ordinals.ordinals());
    }

    @Override
    public ScriptDocValues.Strings getScriptValues() {
        return new ScriptDocValues.Strings(getStringValues());
    }

    static abstract class BytesValues implements org.elasticsearch.index.fielddata.OrdinalsBytesValues {

        protected final PagedBytes.Reader bytes;
        protected final PackedInts.Reader termOrdToBytesOffset;
        protected final Ordinals.Docs ordinals;

        protected final BytesRef scratch = new BytesRef();

        BytesValues(PagedBytes.Reader bytes, PackedInts.Reader termOrdToBytesOffset, Ordinals.Docs ordinals) {
            this.bytes = bytes;
            this.termOrdToBytesOffset = termOrdToBytesOffset;
            this.ordinals = ordinals;
        }

        @Override
        public Ordinals.Docs ordinals() {
            return this.ordinals;
        }

        @Override
        public BytesRef getValueByOrd(int ord) {
            return bytes.fill(scratch, termOrdToBytesOffset.get(ord));
        }

        @Override
        public BytesRef getValueScratchByOrd(int ord, BytesRef ret) {
            return bytes.fill(ret, termOrdToBytesOffset.get(ord));
        }

        @Override
        public BytesRef getSafeValueByOrd(int ord) {
            return bytes.fill(new BytesRef(), termOrdToBytesOffset.get(ord));
        }

        @Override
        public boolean hasValue(int docId) {
            return ordinals.getOrd(docId) != 0;
        }

        @Override
        public BytesRef makeSafe(BytesRef bytes) {
            return BytesRef.deepCopyOf(bytes);
        }

        @Override
        public BytesRef getValue(int docId) {
            int ord = ordinals.getOrd(docId);
            if (ord == 0) return null;
            return bytes.fill(scratch, termOrdToBytesOffset.get(ord));
        }

        @Override
        public BytesRef getValueScratch(int docId, BytesRef ret) {
            return bytes.fill(ret, termOrdToBytesOffset.get(ordinals.getOrd(docId)));
        }

        @Override
        public BytesRef getValueSafe(int docId) {
            int ord = ordinals.getOrd(docId);
            if (ord == 0) return null;
            return bytes.fill(new BytesRef(), termOrdToBytesOffset.get(ord));
        }

        static class Single extends BytesValues {

            private final BytesRefArrayRef arrayScratch = new BytesRefArrayRef(new BytesRef[1], 1);
            private final Iter.Single iter = new Iter.Single();

            Single(PagedBytes.Reader bytes, PackedInts.Reader termOrdToBytesOffset, Ordinals.Docs ordinals) {
                super(bytes, termOrdToBytesOffset, ordinals);
            }

            @Override
            public boolean isMultiValued() {
                return false;
            }

            @Override
            public BytesRefArrayRef getValues(int docId) {
                int ord = ordinals.getOrd(docId);
                if (ord == 0) return BytesRefArrayRef.EMPTY;
                arrayScratch.values[0] = bytes.fill(new BytesRef(), termOrdToBytesOffset.get(ord));
                return arrayScratch;
            }

            @Override
            public Iter getIter(int docId) {
                int ord = ordinals.getOrd(docId);
                if (ord == 0) return Iter.Empty.INSTANCE;
                return iter.reset(bytes.fill(scratch, termOrdToBytesOffset.get(ord)));
            }

            @Override
            public Iter getIterSafe(int docId) {
                int ord = ordinals.getOrd(docId);
                if (ord == 0) return Iter.Empty.INSTANCE;
                return iter.reset(bytes.fill(new BytesRef(), termOrdToBytesOffset.get(ord)));
            }

            @Override
            public void forEachValueInDoc(int docId, ValueInDocProc proc) {
                int ord = ordinals.getOrd(docId);
                if (ord == 0) {
                    proc.onMissing(docId);
                } else {
                    proc.onValue(docId, bytes.fill(scratch, termOrdToBytesOffset.get(ord)));
                }
            }

            @Override
            public void forEachSafeValueInDoc(int docId, ValueInDocProc proc) {
                int ord = ordinals.getOrd(docId);
                if (ord == 0) {
                    proc.onMissing(docId);
                } else {
                    proc.onValue(docId, bytes.fill(new BytesRef(), termOrdToBytesOffset.get(ord)));
                }
            }
        }

        static class Multi extends BytesValues {

            private final BytesRefArrayRef arrayScratch = new BytesRefArrayRef(new BytesRef[10], 0);
            private final ValuesIter iter;
            private final SafeValuesIter safeIter;

            Multi(PagedBytes.Reader bytes, PackedInts.Reader termOrdToBytesOffset, Ordinals.Docs ordinals) {
                super(bytes, termOrdToBytesOffset, ordinals);
                this.iter = new ValuesIter(bytes, termOrdToBytesOffset);
                this.safeIter = new SafeValuesIter(bytes, termOrdToBytesOffset);
            }

            @Override
            public boolean isMultiValued() {
                return true;
            }

            @Override
            public BytesRefArrayRef getValues(int docId) {
                IntArrayRef ords = ordinals.getOrds(docId);
                int size = ords.size();
                if (size == 0) return BytesRefArrayRef.EMPTY;

                arrayScratch.reset(size);
                for (int i = ords.start; i < ords.end; i++) {
                    arrayScratch.values[arrayScratch.end++] = bytes.fill(new BytesRef(), termOrdToBytesOffset.get(ords.values[i]));
                }
                return arrayScratch;
            }

            @Override
            public Iter getIter(int docId) {
                return iter.reset(ordinals.getIter(docId));
            }

            @Override
            public Iter getIterSafe(int docId) {
                return safeIter.reset(ordinals.getIter(docId));
            }

            @Override
            public void forEachValueInDoc(int docId, ValueInDocProc proc) {
                Ordinals.Docs.Iter iter = ordinals.getIter(docId);
                int ord = iter.next();
                if (ord == 0) {
                    proc.onMissing(docId);
                    return;
                }
                do {
                    proc.onValue(docId, bytes.fill(scratch, termOrdToBytesOffset.get(ord)));
                } while ((ord = iter.next()) != 0);
            }

            @Override
            public void forEachSafeValueInDoc(int docId, ValueInDocProc proc) {
                Ordinals.Docs.Iter iter = ordinals.getIter(docId);
                int ord = iter.next();
                if (ord == 0) {
                    proc.onMissing(docId);
                    return;
                }
                do {
                    proc.onValue(docId, bytes.fill(new BytesRef(), termOrdToBytesOffset.get(ord)));
                } while ((ord = iter.next()) != 0);
            }

            static class ValuesIter implements Iter {

                private final PagedBytes.Reader bytes;
                private final PackedInts.Reader termOrdToBytesOffset;
                private final BytesRef scratch = new BytesRef();
                private Ordinals.Docs.Iter ordsIter;
                private int ord;

                ValuesIter(PagedBytes.Reader bytes, PackedInts.Reader termOrdToBytesOffset) {
                    this.bytes = bytes;
                    this.termOrdToBytesOffset = termOrdToBytesOffset;
                }

                public ValuesIter reset(Ordinals.Docs.Iter ordsIter) {
                    this.ordsIter = ordsIter;
                    this.ord = ordsIter.next();
                    return this;
                }

                @Override
                public boolean hasNext() {
                    return ord != 0;
                }

                @Override
                public BytesRef next() {
                    BytesRef value = bytes.fill(scratch, termOrdToBytesOffset.get(ord));
                    ord = ordsIter.next();
                    return value;
                }
            }

            static class SafeValuesIter implements Iter {

                private final PagedBytes.Reader bytes;
                private final PackedInts.Reader termOrdToBytesOffset;
                private Ordinals.Docs.Iter ordsIter;
                private int ord;

                SafeValuesIter(PagedBytes.Reader bytes, PackedInts.Reader termOrdToBytesOffset) {
                    this.bytes = bytes;
                    this.termOrdToBytesOffset = termOrdToBytesOffset;
                }

                public SafeValuesIter reset(Ordinals.Docs.Iter ordsIter) {
                    this.ordsIter = ordsIter;
                    this.ord = ordsIter.next();
                    return this;
                }

                @Override
                public boolean hasNext() {
                    return ord != 0;
                }

                @Override
                public BytesRef next() {
                    BytesRef value = bytes.fill(new BytesRef(), termOrdToBytesOffset.get(ord));
                    ord = ordsIter.next();
                    return value;
                }
            }
        }
    }

    static abstract class HashedBytesValues implements org.elasticsearch.index.fielddata.OrdinalsHashedBytesValues {

        protected final PagedBytes.Reader bytes;
        protected final PackedInts.Reader termOrdToBytesOffset;
        protected final int[] hashes;
        protected final Ordinals.Docs ordinals;

        protected final BytesRef scratch1 = new BytesRef();
        protected final HashedBytesRef scratch = new HashedBytesRef();

        HashedBytesValues(PagedBytes.Reader bytes, PackedInts.Reader termOrdToBytesOffset, int[] hashes, Ordinals.Docs ordinals) {
            this.bytes = bytes;
            this.termOrdToBytesOffset = termOrdToBytesOffset;
            this.hashes = hashes;
            this.ordinals = ordinals;
        }

        @Override
        public Ordinals.Docs ordinals() {
            return this.ordinals;
        }

        @Override
        public HashedBytesRef getValueByOrd(int ord) {
            return scratch.reset(bytes.fill(scratch1, termOrdToBytesOffset.get(ord)), hashes[ord]);
        }

        @Override
        public HashedBytesRef getSafeValueByOrd(int ord) {
            return new HashedBytesRef(bytes.fill(new BytesRef(), termOrdToBytesOffset.get(ord)), hashes[ord]);
        }

        @Override
        public boolean hasValue(int docId) {
            return ordinals.getOrd(docId) != 0;
        }

        @Override
        public HashedBytesRef makeSafe(HashedBytesRef bytes) {
            return new HashedBytesRef(BytesRef.deepCopyOf(bytes.bytes), bytes.hash);
        }

        @Override
        public HashedBytesRef getValue(int docId) {
            int ord = ordinals.getOrd(docId);
            if (ord == 0) return null;
            return scratch.reset(bytes.fill(scratch1, termOrdToBytesOffset.get(ord)), hashes[ord]);
        }

        @Override
        public HashedBytesRef getValueSafe(int docId) {
            int ord = ordinals.getOrd(docId);
            if (ord == 0) return null;
            return new HashedBytesRef(bytes.fill(new BytesRef(), termOrdToBytesOffset.get(ord)), hashes[ord]);
        }

        static class Single extends HashedBytesValues {

            private final Iter.Single iter = new Iter.Single();

            Single(PagedBytes.Reader bytes, PackedInts.Reader termOrdToBytesOffset, int[] hashes, Ordinals.Docs ordinals) {
                super(bytes, termOrdToBytesOffset, hashes, ordinals);
            }

            @Override
            public boolean isMultiValued() {
                return false;
            }

            @Override
            public Iter getIter(int docId) {
                int ord = ordinals.getOrd(docId);
                if (ord == 0) return Iter.Empty.INSTANCE;
                return iter.reset(scratch.reset(bytes.fill(scratch1, termOrdToBytesOffset.get(ord)), hashes[ord]));
            }

            @Override
            public Iter getIterSafe(int docId) {
                int ord = ordinals.getOrd(docId);
                if (ord == 0) return Iter.Empty.INSTANCE;
                return iter.reset(new HashedBytesRef(bytes.fill(new BytesRef(), termOrdToBytesOffset.get(ord)), hashes[ord]));
            }

            @Override
            public void forEachValueInDoc(int docId, ValueInDocProc proc) {
                int ord = ordinals.getOrd(docId);
                if (ord == 0) {
                    proc.onMissing(docId);
                } else {
                    proc.onValue(docId, scratch.reset(bytes.fill(scratch1, termOrdToBytesOffset.get(ord)), hashes[ord]));
                }
            }

            @Override
            public void forEachSafeValueInDoc(int docId, ValueInDocProc proc) {
                int ord = ordinals.getOrd(docId);
                if (ord == 0) {
                    proc.onMissing(docId);
                } else {
                    proc.onValue(docId, new HashedBytesRef(bytes.fill(new BytesRef(), termOrdToBytesOffset.get(ord)), hashes[ord]));
                }
            }
        }

        static class Multi extends HashedBytesValues {

            private final ValuesIter iter;
            private final SafeValuesIter safeIter;

            Multi(PagedBytes.Reader bytes, PackedInts.Reader termOrdToBytesOffset, int[] hashes, Ordinals.Docs ordinals) {
                super(bytes, termOrdToBytesOffset, hashes, ordinals);
                this.iter = new ValuesIter(bytes, termOrdToBytesOffset, hashes);
                this.safeIter = new SafeValuesIter(bytes, termOrdToBytesOffset, hashes);
            }

            @Override
            public boolean isMultiValued() {
                return true;
            }

            @Override
            public Iter getIter(int docId) {
                return iter.reset(ordinals.getIter(docId));
            }

            @Override
            public Iter getIterSafe(int docId) {
                return safeIter.reset(ordinals.getIter(docId));
            }

            @Override
            public void forEachValueInDoc(int docId, ValueInDocProc proc) {
                Ordinals.Docs.Iter iter = ordinals.getIter(docId);
                int ord = iter.next();
                if (ord == 0) {
                    proc.onMissing(docId);
                    return;
                }
                do {
                    proc.onValue(docId, scratch.reset(bytes.fill(scratch1, termOrdToBytesOffset.get(ord)), hashes[ord]));
                } while ((ord = iter.next()) != 0);
            }

            @Override
            public void forEachSafeValueInDoc(int docId, ValueInDocProc proc) {
                Ordinals.Docs.Iter iter = ordinals.getIter(docId);
                int ord = iter.next();
                if (ord == 0) {
                    proc.onMissing(docId);
                    return;
                }
                do {
                    proc.onValue(docId, new HashedBytesRef(bytes.fill(new BytesRef(), termOrdToBytesOffset.get(ord)), hashes[ord]));
                } while ((ord = iter.next()) != 0);
            }

            static class ValuesIter implements Iter {

                private final PagedBytes.Reader bytes;
                private final PackedInts.Reader termOrdToBytesOffset;
                private final int[] hashes;
                private Ordinals.Docs.Iter ordsIter;
                private int ord;

                private final BytesRef scratch1 = new BytesRef();
                private final HashedBytesRef scratch = new HashedBytesRef();

                ValuesIter(PagedBytes.Reader bytes, PackedInts.Reader termOrdToBytesOffset, int[] hashes) {
                    this.bytes = bytes;
                    this.termOrdToBytesOffset = termOrdToBytesOffset;
                    this.hashes = hashes;
                }

                public ValuesIter reset(Ordinals.Docs.Iter ordsIter) {
                    this.ordsIter = ordsIter;
                    this.ord = ordsIter.next();
                    return this;
                }

                @Override
                public boolean hasNext() {
                    return ord != 0;
                }

                @Override
                public HashedBytesRef next() {
                    HashedBytesRef value = scratch.reset(bytes.fill(scratch1, termOrdToBytesOffset.get(ord)), hashes[ord]);
                    ord = ordsIter.next();
                    return value;
                }
            }

            static class SafeValuesIter implements Iter {

                private final PagedBytes.Reader bytes;
                private final PackedInts.Reader termOrdToBytesOffset;
                private final int[] hashes;
                private Ordinals.Docs.Iter ordsIter;
                private int ord;

                SafeValuesIter(PagedBytes.Reader bytes, PackedInts.Reader termOrdToBytesOffset, int[] hashes) {
                    this.bytes = bytes;
                    this.termOrdToBytesOffset = termOrdToBytesOffset;
                    this.hashes = hashes;
                }

                public SafeValuesIter reset(Ordinals.Docs.Iter ordsIter) {
                    this.ordsIter = ordsIter;
                    this.ord = ordsIter.next();
                    return this;
                }

                @Override
                public boolean hasNext() {
                    return ord != 0;
                }

                @Override
                public HashedBytesRef next() {
                    HashedBytesRef value = new HashedBytesRef(bytes.fill(new BytesRef(), termOrdToBytesOffset.get(ord)), hashes[ord]);
                    ord = ordsIter.next();
                    return value;
                }
            }
        }
    }

    static abstract class StringValues implements OrdinalsStringValues {

        protected final PagedBytes.Reader bytes;
        protected final PackedInts.Reader termOrdToBytesOffset;
        protected final Ordinals.Docs ordinals;

        protected final BytesRef scratch = new BytesRef();

        protected StringValues(PagedBytes.Reader bytes, PackedInts.Reader termOrdToBytesOffset, Ordinals.Docs ordinals) {
            this.bytes = bytes;
            this.termOrdToBytesOffset = termOrdToBytesOffset;
            this.ordinals = ordinals;
        }

        @Override
        public Ordinals.Docs ordinals() {
            return ordinals;
        }

        @Override
        public String getValueByOrd(int ord) {
            BytesRef value = bytes.fill(scratch, termOrdToBytesOffset.get(ord));
            return value.utf8ToString();
        }

        @Override
        public boolean hasValue(int docId) {
            return ordinals.getOrd(docId) != 0;
        }

        @Override
        public String getValue(int docId) {
            int ord = ordinals.getOrd(docId);
            if (ord == 0) return null;
            BytesRef value = bytes.fill(scratch, termOrdToBytesOffset.get(ord));
            return value.utf8ToString();
        }

        static class Single extends StringValues {

            private final StringArrayRef arrayScratch = new StringArrayRef(new String[1], 1);
            private final Iter.Single iter = new Iter.Single();

            Single(PagedBytes.Reader bytes, PackedInts.Reader termOrdToBytesOffset, Ordinals.Docs ordinals) {
                super(bytes, termOrdToBytesOffset, ordinals);
            }

            @Override
            public boolean isMultiValued() {
                return false;
            }

            @Override
            public StringArrayRef getValues(int docId) {
                int ord = ordinals.getOrd(docId);
                if (ord == 0) return StringArrayRef.EMPTY;
                BytesRef value = bytes.fill(scratch, termOrdToBytesOffset.get(ord));
                arrayScratch.values[0] = value == null ? null : value.utf8ToString();
                return arrayScratch;
            }

            @Override
            public Iter getIter(int docId) {
                int ord = ordinals.getOrd(docId);
                if (ord == 0) return Iter.Empty.INSTANCE;
                return iter.reset(bytes.fill(scratch, termOrdToBytesOffset.get(ord)).utf8ToString());
            }

            @Override
            public void forEachValueInDoc(int docId, ValueInDocProc proc) {
                int ord = ordinals.getOrd(docId);
                if (ord == 0) {
                    proc.onMissing(docId);
                    return;
                }
                proc.onValue(docId, bytes.fill(scratch, termOrdToBytesOffset.get(ord)).utf8ToString());
            }
        }

        static class Multi extends StringValues {

            private final StringArrayRef arrayScratch = new StringArrayRef(new String[10], 0);
            private final ValuesIter iter;

            Multi(PagedBytes.Reader bytes, PackedInts.Reader termOrdToBytesOffset, Ordinals.Docs ordinals) {
                super(bytes, termOrdToBytesOffset, ordinals);
                iter = new ValuesIter(bytes, termOrdToBytesOffset);
            }

            @Override
            public boolean isMultiValued() {
                return true;
            }

            @Override
            public StringArrayRef getValues(int docId) {
                IntArrayRef ords = ordinals.getOrds(docId);
                int size = ords.size();
                if (size == 0) return StringArrayRef.EMPTY;

                arrayScratch.reset(size);
                for (int i = ords.start; i < ords.end; i++) {
                    BytesRef value = bytes.fill(scratch, termOrdToBytesOffset.get(ords.values[i]));
                    arrayScratch.values[arrayScratch.end++] = value == null ? null : value.utf8ToString();
                }
                return arrayScratch;
            }

            @Override
            public Iter getIter(int docId) {
                return iter.reset(ordinals.getIter(docId));
            }

            @Override
            public void forEachValueInDoc(int docId, ValueInDocProc proc) {
                Ordinals.Docs.Iter iter = ordinals.getIter(docId);
                int ord = iter.next();
                if (ord == 0) {
                    proc.onMissing(docId);
                    return;
                }
                do {
                    BytesRef value = bytes.fill(scratch, termOrdToBytesOffset.get(ord));
                    proc.onValue(docId, value == null ? null : value.utf8ToString());
                } while ((ord = iter.next()) != 0);
            }

            static class ValuesIter implements StringValues.Iter {

                private final PagedBytes.Reader bytes;
                private final PackedInts.Reader termOrdToBytesOffset;
                private final BytesRef scratch = new BytesRef();
                private Ordinals.Docs.Iter ordsIter;
                private int ord;

                ValuesIter(PagedBytes.Reader bytes, PackedInts.Reader termOrdToBytesOffset) {
                    this.bytes = bytes;
                    this.termOrdToBytesOffset = termOrdToBytesOffset;
                }

                public ValuesIter reset(Ordinals.Docs.Iter ordsIter) {
                    this.ordsIter = ordsIter;
                    this.ord = ordsIter.next();
                    return this;
                }

                @Override
                public boolean hasNext() {
                    return ord != 0;
                }

                @Override
                public String next() {
                    BytesRef value = bytes.fill(scratch, termOrdToBytesOffset.get(ord));
                    ord = ordsIter.next();
                    return value == null ? null : value.utf8ToString();
                }
            }
        }
    }
}
