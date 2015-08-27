/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authz.accesscontrol;

import org.apache.lucene.index.*;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.FilterIterator;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.index.mapper.internal.FieldNamesFieldMapper;
import org.elasticsearch.index.mapper.internal.SourceFieldMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * A {@link FilterLeafReader} that exposes only a subset
 * of fields from the underlying wrapped reader.
 */
// based on lucene/test-framework's FieldFilterLeafReader.
public final class FieldSubsetReader extends FilterLeafReader {
    
    /**
     * Wraps a provided DirectoryReader, exposing a subset of fields. 
     * <p>
     * Note that for convenience, the returned reader
     * can be used normally (e.g. passed to {@link DirectoryReader#openIfChanged(DirectoryReader)})
     * and so on. 
     * @param in reader to filter
     * @param fieldNames fields to filter.
     */
    public static DirectoryReader wrap(DirectoryReader in, Set<String> fieldNames) throws IOException {
        return new FieldSubsetDirectoryReader(in, fieldNames);
    }
    
    // wraps subreaders with fieldsubsetreaders.
    static class FieldSubsetDirectoryReader extends FilterDirectoryReader {

        private final Set<String> fieldNames;

        FieldSubsetDirectoryReader(DirectoryReader in, final Set<String> fieldNames) throws IOException {
            super(in, new FilterDirectoryReader.SubReaderWrapper() {
                @Override
                public LeafReader wrap(LeafReader reader) {
                    return new FieldSubsetReader(reader, fieldNames);
                }
            });
            this.fieldNames = fieldNames;
        }
        
        @Override
        protected DirectoryReader doWrapDirectoryReader(DirectoryReader in) throws IOException {
            return new FieldSubsetDirectoryReader(in, fieldNames);
        }
    }
    
    /** List of filtered fields */
    private final FieldInfos fieldInfos;
    /** List of filtered fields. this is used for _source filtering */
    private final String[] fieldNames;

    /**
     * Wrap a single segment, exposing a subset of its fields.
     */
    FieldSubsetReader(LeafReader in, Set<String> fieldNames) {
        super(in);
        ArrayList<FieldInfo> filteredInfos = new ArrayList<>();
        for (FieldInfo fi : in.getFieldInfos()) {
            if (fieldNames.contains(fi.name)) {
                filteredInfos.add(fi);
            }
        }
        fieldInfos = new FieldInfos(filteredInfos.toArray(new FieldInfo[filteredInfos.size()]));
        this.fieldNames = fieldNames.toArray(new String[fieldNames.size()]);
    }
    
    /** returns true if this field is allowed. */
    boolean hasField(String field) {
        return fieldInfos.fieldInfo(field) != null;
    }
    
    @Override
    public FieldInfos getFieldInfos() {
        return fieldInfos;
    }
    
    @Override
    public Fields getTermVectors(int docID) throws IOException {
        Fields f = super.getTermVectors(docID);
        if (f == null) {
            return null;
        }
        f = new FieldFilterFields(f);
        // we need to check for emptyness, so we can return null:
        return f.iterator().hasNext() ? f : null;
    }
    
    @Override
    public void document(final int docID, final StoredFieldVisitor visitor) throws IOException {
        super.document(docID, new StoredFieldVisitor() {
            @Override
            public void binaryField(FieldInfo fieldInfo, byte[] value) throws IOException {
                if (SourceFieldMapper.NAME.equals(fieldInfo.name)) {
                    // for _source, parse, filter out the fields we care about, and serialize back downstream
                    BytesReference bytes = new BytesArray(value);
                    Tuple<XContentType, Map<String, Object>> result = XContentHelper.convertToMap(bytes, true);
                    Map<String, Object> transformedSource = XContentMapValues.filter(result.v2(), fieldNames, null);
                    XContentBuilder xContentBuilder = XContentBuilder.builder(result.v1().xContent()).map(transformedSource);
                    visitor.binaryField(fieldInfo, xContentBuilder.bytes().toBytes());
                } else {
                    visitor.binaryField(fieldInfo, value);
                }
            }
            
            @Override
            public void stringField(FieldInfo fieldInfo, byte[] value) throws IOException {
                visitor.stringField(fieldInfo, value);
            }
            
            @Override
            public void intField(FieldInfo fieldInfo, int value) throws IOException {
                visitor.intField(fieldInfo, value);
            }
            
            @Override
            public void longField(FieldInfo fieldInfo, long value) throws IOException {
                visitor.longField(fieldInfo, value);
            }
            
            @Override
            public void floatField(FieldInfo fieldInfo, float value) throws IOException {
                visitor.floatField(fieldInfo, value);
            }
            
            @Override
            public void doubleField(FieldInfo fieldInfo, double value) throws IOException {
                visitor.doubleField(fieldInfo, value);
            }
            
            @Override
            public Status needsField(FieldInfo fieldInfo) throws IOException {
                return hasField(fieldInfo.name) ? visitor.needsField(fieldInfo) : Status.NO;
            }
        });
    }
    
    @Override
    public Fields fields() throws IOException {
        return new FieldFilterFields(super.fields());
    }    
    
    @Override
    public NumericDocValues getNumericDocValues(String field) throws IOException {
        return hasField(field) ? super.getNumericDocValues(field) : null;
    }
    
    @Override
    public BinaryDocValues getBinaryDocValues(String field) throws IOException {
        return hasField(field) ? super.getBinaryDocValues(field) : null;
    }
    
    @Override
    public SortedDocValues getSortedDocValues(String field) throws IOException {
        return hasField(field) ? super.getSortedDocValues(field) : null;
    }
    
    @Override
    public SortedNumericDocValues getSortedNumericDocValues(String field) throws IOException {
        return hasField(field) ? super.getSortedNumericDocValues(field) : null;
    }
    
    @Override
    public SortedSetDocValues getSortedSetDocValues(String field) throws IOException {
        return hasField(field) ? super.getSortedSetDocValues(field) : null;
    }
    
    @Override
    public NumericDocValues getNormValues(String field) throws IOException {
        return hasField(field) ? super.getNormValues(field) : null;
    }
    
    @Override
    public Bits getDocsWithField(String field) throws IOException {
        return hasField(field) ? super.getDocsWithField(field) : null;
    }
    
    // we share core cache keys (for e.g. fielddata)
    
    @Override
    public Object getCombinedCoreAndDeletesKey() {
        return in.getCombinedCoreAndDeletesKey();
    }

    @Override
    public Object getCoreCacheKey() {
        return in.getCoreCacheKey();
    }

    /**
     * Filters the Fields instance from the postings.
     * <p>
     * In addition to only returning fields allowed in this subset,
     * the ES internal _field_names (used by exists filter) has special handling, 
     * to hide terms for fields that don't exist.
     */
    class FieldFilterFields extends FilterFields {
        
        public FieldFilterFields(Fields in) {
            super(in);
        }
        
        @Override
        public int size() {
            // this information is not cheap, return -1 like MultiFields does:
            return -1;
        }
        
        @Override
        public Iterator<String> iterator() {
            return new FilterIterator<String, String>(super.iterator()) {
                @Override
                protected boolean predicateFunction(String field) {
                    return hasField(field);
                }
            };
        }
        
        @Override
        public Terms terms(String field) throws IOException {
            if (!hasField(field)) {
                return null;
            } else if (FieldNamesFieldMapper.NAME.equals(field)) {
                // for the _field_names field, fields for the document
                // are encoded as postings, where term is the field.
                // so we hide terms for fields we filter out.
                Terms terms = super.terms(field);
                if (terms != null) {
                    // check for null, in case term dictionary is not a ghostbuster
                    // So just because its in fieldinfos and "indexed=true" doesn't mean you can go grab a Terms for it.
                    // It just means at one point there was a document with that field indexed...
                    // The fields infos isn't updates/removed even if no docs refer to it
                    terms = new FieldNamesTerms(terms);
                }
                return terms;
            } else {
                return super.terms(field);
            }
        }
    }
    
    /**
     * Terms impl for _field_names (used by exists filter) that filters out terms
     * representing fields that should not be visible in this reader.
     */
    class FieldNamesTerms extends FilterTerms {
        
        FieldNamesTerms(Terms in) {
            super(in);
        }
        
        @Override
        public TermsEnum iterator() throws IOException {
            return new FieldNamesTermsEnum(in.iterator());
        }
        
        // we don't support field statistics (since we filter out terms)
        // but this isn't really a big deal: _field_names is not used for ranking.

        @Override
        public int getDocCount() throws IOException {
            return -1;
        }

        @Override
        public long getSumDocFreq() throws IOException {
            return -1;
        }

        @Override
        public long getSumTotalTermFreq() throws IOException {
            return -1;
        }

        @Override
        public long size() throws IOException {
            return -1;
        }
    }
    
    /**
     * TermsEnum impl for _field_names (used by exists filter) that filters out terms
     * representing fields that should not be visible in this reader.
     */
    class FieldNamesTermsEnum extends FilterTermsEnum {
        
        FieldNamesTermsEnum(TermsEnum in) {
            super(in);
        }
        
        /** Return true if term is accepted (matches a field name in this reader). */
        boolean accept(BytesRef term) {
            return hasField(term.utf8ToString());
        }

        @Override
        public boolean seekExact(BytesRef term) throws IOException {
            return accept(term) && in.seekExact(term);
        }

        @Override
        public SeekStatus seekCeil(BytesRef term) throws IOException {
            SeekStatus status = in.seekCeil(term);
            if (status == SeekStatus.END || accept(term())) {
                return status;
            }
            return next() == null ? SeekStatus.END : SeekStatus.NOT_FOUND;
        }

        @Override
        public BytesRef next() throws IOException {
            BytesRef next;
            while ((next = in.next()) != null) {
                if (accept(next)) {
                    break;
                }
            }
            return next;
        }
        
        // we don't support ordinals, but _field_names is not used in this way

        @Override
        public void seekExact(long ord) throws IOException {
          throw new UnsupportedOperationException();
        }

        @Override
        public long ord() throws IOException {
          throw new UnsupportedOperationException();
        }
    }
}
