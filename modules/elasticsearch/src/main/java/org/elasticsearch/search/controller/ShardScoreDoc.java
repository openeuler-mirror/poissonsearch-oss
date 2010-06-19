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

package org.elasticsearch.search.controller;

import org.apache.lucene.search.ScoreDoc;
import org.elasticsearch.search.SearchShardTarget;

/**
 * @author kimchy (Shay Banon)
 */
public class ShardScoreDoc extends ScoreDoc implements ShardDoc {

    private final SearchShardTarget shardTarget;

    public ShardScoreDoc(SearchShardTarget shardTarget, int doc, float score) {
        super(doc, score);
        this.shardTarget = shardTarget;
    }

    @Override public SearchShardTarget shardTarget() {
        return this.shardTarget;
    }

    @Override public int docId() {
        return doc;
    }

    @Override public float score() {
        return score;
    }
}
