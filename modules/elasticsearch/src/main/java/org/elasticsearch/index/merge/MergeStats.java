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

package org.elasticsearch.index.merge;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Streamable;
import org.elasticsearch.common.unit.TimeValue;

import java.io.IOException;

/**
 *
 */
public class MergeStats implements Streamable {

    private long totalMerges;

    private long currentMerges;

    private long totalMergeTime;

    public MergeStats() {

    }

    public MergeStats(long totalMerges, long currentMerges, long totalMergeTime) {
        this.totalMerges = totalMerges;
        this.currentMerges = currentMerges;
        this.totalMergeTime = totalMergeTime;
    }

    public void add(long totalMerges, long currentMerges, long totalMergeTime) {
        this.totalMerges += totalMerges;
        this.currentMerges += currentMerges;
        this.totalMergeTime += totalMergeTime;
    }

    public void add(MergeStats mergeStats) {
        this.totalMerges += mergeStats.totalMerges;
        this.currentMerges += mergeStats.currentMerges;
        this.totalMergeTime += mergeStats.totalMergeTime;
    }

    /**
     * The total number of merges executed.
     */
    public long totalMerges() {
        return this.totalMerges;
    }

    /**
     * The current number of merges executing.
     */
    public long currentMerges() {
        return this.currentMerges;
    }

    /**
     * The total time merges have been executed (in milliseconds).
     */
    public long totalMergeTimeInMillis() {
        return this.totalMergeTime;
    }

    /**
     * The total time merges have been executed.
     */
    public TimeValue totalMergeTime() {
        return new TimeValue(totalMergeTime);
    }

    public static MergeStats readMergeStats(StreamInput in) throws IOException {
        MergeStats stats = new MergeStats();
        stats.readFrom(in);
        return stats;
    }

    @Override public void readFrom(StreamInput in) throws IOException {
        totalMerges = in.readVLong();
        currentMerges = in.readVLong();
        totalMergeTime = in.readVLong();
    }

    @Override public void writeTo(StreamOutput out) throws IOException {
        out.writeVLong(totalMerges);
        out.writeVLong(currentMerges);
        out.writeVLong(totalMergeTime);
    }
}