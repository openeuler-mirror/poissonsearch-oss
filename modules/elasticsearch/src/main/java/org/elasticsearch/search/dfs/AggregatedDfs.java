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

package org.elasticsearch.search.dfs;

import org.apache.lucene.index.Term;
import org.elasticsearch.util.gnu.trove.TObjectIntProcedure;
import org.elasticsearch.util.io.stream.StreamInput;
import org.elasticsearch.util.io.stream.StreamOutput;
import org.elasticsearch.util.io.stream.Streamable;
import org.elasticsearch.util.trove.ExtTObjectIntHasMap;

import java.io.IOException;

/**
 * @author kimchy (Shay Banon)
 */
public class AggregatedDfs implements Streamable {

    private ExtTObjectIntHasMap<Term> dfMap;

    private long maxDoc;

    private AggregatedDfs() {

    }

    public AggregatedDfs(ExtTObjectIntHasMap<Term> dfMap, long maxDoc) {
        this.dfMap = dfMap.defaultReturnValue(-1);
        this.maxDoc = maxDoc;
    }

    public ExtTObjectIntHasMap<Term> dfMap() {
        return dfMap;
    }

    public long maxDoc() {
        return maxDoc;
    }

    public static AggregatedDfs readAggregatedDfs(StreamInput in) throws IOException {
        AggregatedDfs result = new AggregatedDfs();
        result.readFrom(in);
        return result;
    }

    @Override public void readFrom(StreamInput in) throws IOException {
        int size = in.readVInt();
        dfMap = new ExtTObjectIntHasMap<Term>(size).defaultReturnValue(-1);
        for (int i = 0; i < size; i++) {
            dfMap.put(new Term(in.readUTF(), in.readUTF()), in.readVInt());
        }
        maxDoc = in.readVLong();
    }

    @Override public void writeTo(final StreamOutput out) throws IOException {
        out.writeVInt(dfMap.size());
        WriteToProcedure writeToProcedure = new WriteToProcedure(out);
        if (!dfMap.forEachEntry(writeToProcedure)) {
            throw writeToProcedure.exception;
        }
        out.writeVLong(maxDoc);
    }

    private static class WriteToProcedure implements TObjectIntProcedure<Term> {

        private final StreamOutput out;

        IOException exception;

        private WriteToProcedure(StreamOutput out) {
            this.out = out;
        }

        @Override public boolean execute(Term a, int b) {
            try {
                out.writeUTF(a.field());
                out.writeUTF(a.text());
                out.writeVInt(b);
                return true;
            } catch (IOException e) {
                exception = e;
            }
            return false;
        }
    }
}
