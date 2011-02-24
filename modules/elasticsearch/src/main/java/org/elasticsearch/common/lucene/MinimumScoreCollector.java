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

package org.elasticsearch.common.lucene;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.ScoreCachingWrappingScorer;
import org.apache.lucene.search.Scorer;

import java.io.IOException;

/**
 *
 */
public class MinimumScoreCollector extends Collector {

    private final Collector collector;

    private final float minimumScore;

    private Scorer scorer;

    public MinimumScoreCollector(Collector collector, float minimumScore) {
        this.collector = collector;
        this.minimumScore = minimumScore;
    }

    @Override public void setScorer(Scorer scorer) throws IOException {
        if (!(scorer instanceof ScoreCachingWrappingScorer)) {
            scorer = new ScoreCachingWrappingScorer(scorer);
        }
        this.scorer = scorer;
        collector.setScorer(scorer);
    }

    @Override public void collect(int doc) throws IOException {
        if (scorer.score() > minimumScore) {
            collector.collect(doc);
        }
    }

    @Override public void setNextReader(IndexReader reader, int docBase) throws IOException {
        collector.setNextReader(reader, docBase);
    }

    @Override public boolean acceptsDocsOutOfOrder() {
        return collector.acceptsDocsOutOfOrder();
    }
}