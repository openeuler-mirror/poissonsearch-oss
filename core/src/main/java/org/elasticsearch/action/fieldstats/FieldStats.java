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

package org.elasticsearch.action.fieldstats;

import org.apache.lucene.document.InetAddressPoint;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.StringHelper;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Streamable;
import org.elasticsearch.common.joda.FormatDateTimeFormatter;
import org.elasticsearch.common.joda.Joda;
import org.elasticsearch.common.network.NetworkAddress;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

public abstract class FieldStats<T> implements Streamable, ToXContent {

    private final byte type;
    private long maxDoc;
    private long docCount;
    private long sumDocFreq;
    private long sumTotalTermFreq;
    protected T minValue;
    protected T maxValue;

    protected FieldStats(int type) {
        this.type = (byte) type;
    }

    protected FieldStats(int type, long maxDoc, long docCount, long sumDocFreq, long sumTotalTermFreq) {
        this.type = (byte) type;
        this.maxDoc = maxDoc;
        this.docCount = docCount;
        this.sumDocFreq = sumDocFreq;
        this.sumTotalTermFreq = sumTotalTermFreq;
    }

    byte getType() {
        return type;
    }

    /**
     * @return the total number of documents.
     *
     * Note that, documents marked as deleted that haven't yet been merged way aren't taken into account.
     */
    public long getMaxDoc() {
        return maxDoc;
    }

    /**
     * @return the number of documents that have at least one term for this field, or -1 if this measurement isn't available.
     *
     * Note that, documents marked as deleted that haven't yet been merged way aren't taken into account.
     */
    public long getDocCount() {
        return docCount;
    }

    /**
     * @return The percentage of documents that have at least one value for this field.
     *
     * This is a derived statistic and is based on: 'doc_count / max_doc'
     */
    public int getDensity() {
        if (docCount < 0 || maxDoc <= 0) {
            return -1;
        }
        return (int) (docCount * 100 / maxDoc);
    }

    /**
     * @return the sum of each term's document frequency in this field, or -1 if this measurement isn't available.
     * Document frequency is the number of documents containing a particular term.
     *
     * Note that, documents marked as deleted that haven't yet been merged way aren't taken into account.
     */
    public long getSumDocFreq() {
        return sumDocFreq;
    }

    /**
     * @return the sum of the term frequencies of all terms in this field across all documents, or -1 if this measurement
     * isn't available. Term frequency is the total number of occurrences of a term in a particular document and field.
     *
     * Note that, documents marked as deleted that haven't yet been merged way aren't taken into account.
     */
    public long getSumTotalTermFreq() {
        return sumTotalTermFreq;
    }

    /**
     * @return the lowest value in the field.
     *
     * Note that, documents marked as deleted that haven't yet been merged way aren't taken into account.
     */
    public T getMinValue() {
        return minValue;
    }

    /**
     * @return the highest value in the field.
     *
     * Note that, documents marked as deleted that haven't yet been merged way aren't taken into account.
     */
    public T getMaxValue() {
        return maxValue;
    }

    /**
     * @return the lowest value in the field represented as a string.
     *
     * Note that, documents marked as deleted that haven't yet been merged way aren't taken into account.
     */
    public abstract String getMinValueAsString();

    /**
     * @return the highest value in the field represented as a string.
     *
     * Note that, documents marked as deleted that haven't yet been merged way aren't taken into account.
     */
    public abstract String getMaxValueAsString();

    /**
     * @param value The string to be parsed
     * @param optionalFormat A string describing how to parse the specified value. Whether this parameter is supported
     *                       depends on the implementation. If optionalFormat is specified and the implementation
     *                       doesn't support it an {@link UnsupportedOperationException} is thrown
     */
    protected abstract T valueOf(String value, String optionalFormat);

    /**
     * Merges the provided stats into this stats instance.
     */
    public void append(FieldStats stats) {
        this.maxDoc += stats.maxDoc;
        if (stats.docCount == -1) {
            this.docCount = -1;
        } else if (this.docCount != -1) {
            this.docCount += stats.docCount;
        }
        if (stats.sumDocFreq == -1) {
            this.sumDocFreq = -1;
        } else if (this.sumDocFreq != -1) {
            this.sumDocFreq += stats.sumDocFreq;
        }
        if (stats.sumTotalTermFreq == -1) {
            this.sumTotalTermFreq = -1;
        } else if (this.sumTotalTermFreq != -1) {
            this.sumTotalTermFreq += stats.sumTotalTermFreq;
        }
    }

    protected abstract int compare(T a, T b);

    /**
     * @return <code>true</code> if this instance matches with the provided index constraint, otherwise <code>false</code> is returned
     */
    public boolean match(IndexConstraint constraint) {
        int cmp;
        T value  = valueOf(constraint.getValue(), constraint.getOptionalFormat());
        if (constraint.getProperty() == IndexConstraint.Property.MIN) {
            cmp = compare(minValue, value);
        } else if (constraint.getProperty() == IndexConstraint.Property.MAX) {
            cmp = compare(maxValue, value);
        } else {
            throw new IllegalArgumentException("Unsupported property [" + constraint.getProperty() + "]");
        }

        switch (constraint.getComparison()) {
            case GT:
                return cmp > 0;
            case GTE:
                return cmp >= 0;
            case LT:
                return cmp < 0;
            case LTE:
                return cmp <= 0;
            default:
                throw new IllegalArgumentException("Unsupported comparison [" + constraint.getComparison() + "]");
        }
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(Fields.MAX_DOC, maxDoc);
        builder.field(Fields.DOC_COUNT, docCount);
        builder.field(Fields.DENSITY, getDensity());
        builder.field(Fields.SUM_DOC_FREQ, sumDocFreq);
        builder.field(Fields.SUM_TOTAL_TERM_FREQ, sumTotalTermFreq);
        toInnerXContent(builder);
        builder.endObject();
        return builder;
    }

    protected void toInnerXContent(XContentBuilder builder) throws IOException {
        builder.field(Fields.MIN_VALUE, getMinValue());
        builder.field(Fields.MIN_VALUE_AS_STRING, getMinValueAsString());
        builder.field(Fields.MAX_VALUE, getMaxValue());
        builder.field(Fields.MAX_VALUE_AS_STRING, getMaxValueAsString());
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        maxDoc = in.readVLong();
        docCount = in.readLong();
        sumDocFreq = in.readLong();
        sumTotalTermFreq = in.readLong();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeByte(type);
        out.writeVLong(maxDoc);
        out.writeLong(docCount);
        out.writeLong(sumDocFreq);
        out.writeLong(sumTotalTermFreq);
    }

    private static abstract class ComparableFieldStats<T extends Comparable<? super T>> extends FieldStats<T> {
        protected ComparableFieldStats(int type) {
            super(type);
        }

        protected ComparableFieldStats(int type, long maxDoc, long docCount, long sumDocFreq, long sumTotalTermFreq) {
            super(type, maxDoc, docCount, sumDocFreq, sumTotalTermFreq);
        }

        @Override
        protected int compare(T a, T b) {
            return a.compareTo(b);
        }
    }

    public static class Long extends ComparableFieldStats<java.lang.Long> {

        public Long() {
            super(0);
        }

        public Long(long maxDoc, long docCount, long sumDocFreq, long sumTotalTermFreq, long minValue, long maxValue) {
            this(0, maxDoc, docCount, sumDocFreq, sumTotalTermFreq, minValue, maxValue);
        }

        protected Long(int type, long maxDoc, long docCount, long sumDocFreq, long sumTotalTermFreq, long minValue, long maxValue) {
            super(type, maxDoc, docCount, sumDocFreq, sumTotalTermFreq);
            this.minValue = minValue;
            this.maxValue = maxValue;
        }

        @Override
        public String getMinValueAsString() {
            return String.valueOf(minValue.longValue());
        }

        @Override
        public String getMaxValueAsString() {
            return String.valueOf(maxValue.longValue());
        }

        @Override
        public void append(FieldStats stats) {
            super.append(stats);
            Long other = (Long) stats;
            this.minValue = Math.min(other.minValue, minValue);
            this.maxValue = Math.max(other.maxValue, maxValue);
        }

        @Override
        protected java.lang.Long valueOf(String value, String optionalFormat) {
            if (optionalFormat != null) {
                throw new UnsupportedOperationException("custom format isn't supported");
            }
            return java.lang.Long.valueOf(value);
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            minValue = in.readLong();
            maxValue = in.readLong();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeLong(minValue);
            out.writeLong(maxValue);
        }

    }

    public static final class Double extends ComparableFieldStats<java.lang.Double> {

        public Double() {
            super(2);
        }

        public Double(long maxDoc, long docCount, long sumDocFreq, long sumTotalTermFreq, double minValue, double maxValue) {
            super(2, maxDoc, docCount, sumDocFreq, sumTotalTermFreq);
            this.minValue = minValue;
            this.maxValue = maxValue;
        }

        @Override
        public String getMinValueAsString() {
            return String.valueOf(minValue.doubleValue());
        }

        @Override
        public String getMaxValueAsString() {
            return String.valueOf(maxValue.doubleValue());
        }

        @Override
        public void append(FieldStats stats) {
            super.append(stats);
            Double other = (Double) stats;
            this.minValue = Math.min(other.minValue, minValue);
            this.maxValue = Math.max(other.maxValue, maxValue);
        }

        @Override
        protected java.lang.Double valueOf(String value, String optionalFormat) {
            if (optionalFormat != null) {
                throw new UnsupportedOperationException("custom format isn't supported");
            }
            return java.lang.Double.valueOf(value);
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            minValue = in.readDouble();
            maxValue = in.readDouble();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeDouble(minValue);
            out.writeDouble(maxValue);
        }

    }

    public static final class Text extends ComparableFieldStats<BytesRef> {

        public Text() {
            super(3);
        }

        public Text(long maxDoc, long docCount, long sumDocFreq, long sumTotalTermFreq, BytesRef minValue, BytesRef maxValue) {
            super(3, maxDoc, docCount, sumDocFreq, sumTotalTermFreq);
            this.minValue = minValue;
            this.maxValue = maxValue;
        }

        @Override
        public String getMinValueAsString() {
            return minValue.utf8ToString();
        }

        @Override
        public String getMaxValueAsString() {
            return maxValue.utf8ToString();
        }

        @Override
        public void append(FieldStats stats) {
            super.append(stats);
            Text other = (Text) stats;
            if (other.minValue.compareTo(minValue) < 0) {
                minValue = other.minValue;
            }
            if (other.maxValue.compareTo(maxValue) > 0) {
                maxValue = other.maxValue;
            }
        }

        @Override
        protected BytesRef valueOf(String value, String optionalFormat) {
            if (optionalFormat != null) {
                throw new UnsupportedOperationException("custom format isn't supported");
            }
            return new BytesRef(value);
        }

        @Override
        protected void toInnerXContent(XContentBuilder builder) throws IOException {
            builder.field(Fields.MIN_VALUE, getMinValueAsString());
            builder.field(Fields.MAX_VALUE, getMaxValueAsString());
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            minValue = in.readBytesRef();
            maxValue = in.readBytesRef();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeBytesRef(minValue);
            out.writeBytesRef(maxValue);
        }

    }

    public static final class Date extends Long {

        private FormatDateTimeFormatter dateFormatter;

        public Date() {
        }

        public Date(long maxDoc, long docCount, long sumDocFreq, long sumTotalTermFreq, long minValue, long maxValue, FormatDateTimeFormatter dateFormatter) {
            super(1, maxDoc, docCount, sumDocFreq, sumTotalTermFreq, minValue, maxValue);
            this.dateFormatter = dateFormatter;
        }

        @Override
        public String getMinValueAsString() {
            return dateFormatter.printer().print(minValue);
        }

        @Override
        public String getMaxValueAsString() {
            return dateFormatter.printer().print(maxValue);
        }

        @Override
        protected java.lang.Long valueOf(String value, String optionalFormat) {
            FormatDateTimeFormatter dateFormatter = this.dateFormatter;
            if (optionalFormat != null) {
                dateFormatter = Joda.forPattern(optionalFormat);
            }
            return dateFormatter.parser().parseMillis(value);
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            dateFormatter =  Joda.forPattern(in.readString());
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeString(dateFormatter.format());
        }

    }

    public static class Ip extends FieldStats<InetAddress> {

        private InetAddress minValue, maxValue;

        public Ip(int maxDoc, int docCount, long sumDocFreq, long sumTotalTermFreq,
                InetAddress minValue, InetAddress maxValue) {
            super(4, maxDoc, docCount, sumDocFreq, sumTotalTermFreq);
            this.minValue = minValue;
            this.maxValue = maxValue;
        }

        public Ip() {
            super(4);
        }

        @Override
        public String getMinValueAsString() {
            return NetworkAddress.format(minValue);
        }

        @Override
        public String getMaxValueAsString() {
            return NetworkAddress.format(maxValue);
        }

        @Override
        protected InetAddress valueOf(String value, String optionalFormat) {
            try {
                return InetAddress.getByName(value);
            } catch (UnknownHostException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        protected int compare(InetAddress a, InetAddress b) {
            byte[] ab = InetAddressPoint.encode(a);
            byte[] bb = InetAddressPoint.encode(b);
            return StringHelper.compare(ab.length, ab, 0, bb, 0);
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            minValue = valueOf(in.readString(), null);
            maxValue = valueOf(in.readString(), null);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeString(NetworkAddress.format(minValue));
            out.writeString(NetworkAddress.format(maxValue));
        }
    }

    public static FieldStats read(StreamInput in) throws IOException {
        FieldStats stats;
        byte type = in.readByte();
        switch (type) {
            case 0:
                stats = new Long();
                break;
            case 1:
                stats = new Date();
                break;
            case 2:
                stats = new Double();
                break;
            case 3:
                stats = new Text();
                break;
            case 4:
                stats = new Ip();
                break;
            default:
                throw new IllegalArgumentException("Illegal type [" + type + "]");
        }
        stats.readFrom(in);
        return stats;
    }

    private final static class Fields {

        final static String MAX_DOC = new String("max_doc");
        final static String DOC_COUNT = new String("doc_count");
        final static String DENSITY = new String("density");
        final static String SUM_DOC_FREQ = new String("sum_doc_freq");
        final static String SUM_TOTAL_TERM_FREQ = new String("sum_total_term_freq");
        final static String MIN_VALUE = new String("min_value");
        final static String MIN_VALUE_AS_STRING = new String("min_value_as_string");
        final static String MAX_VALUE = new String("max_value");
        final static String MAX_VALUE_AS_STRING = new String("max_value_as_string");

    }

}
