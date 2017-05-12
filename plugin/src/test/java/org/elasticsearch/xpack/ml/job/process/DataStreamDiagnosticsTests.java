/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.job.process;

import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.ml.job.config.AnalysisConfig;
import org.elasticsearch.xpack.ml.job.config.DataDescription;
import org.elasticsearch.xpack.ml.job.config.Detector;
import org.elasticsearch.xpack.ml.job.config.Job;
import org.junit.Before;

import java.io.IOException;
import java.util.Arrays;
import java.util.Date;

public class DataStreamDiagnosticsTests extends ESTestCase {

    private Job job;
    
    @Before
    public void setUpMocks() throws IOException {
        AnalysisConfig.Builder acBuilder = new AnalysisConfig.Builder(Arrays.asList(new Detector.Builder("metric", "field").build()));
        acBuilder.setBucketSpan(TimeValue.timeValueSeconds(60));
        acBuilder.setLatency(TimeValue.ZERO);
        acBuilder.setDetectors(Arrays.asList(new Detector.Builder("metric", "field").build()));

        Job.Builder builder = new Job.Builder("job_id");
        builder.setAnalysisConfig(acBuilder);
        builder.setDataDescription(new DataDescription.Builder());
        job = builder.build(new Date());
    }

    public void testSimple() {
        DataStreamDiagnostics d = new DataStreamDiagnostics(job);

        d.checkRecord(70000);
        d.checkRecord(130000);
        d.checkRecord(190000);
        d.checkRecord(250000);
        d.checkRecord(310000);
        d.checkRecord(370000);
        d.checkRecord(430000);
        d.checkRecord(490000);
        d.checkRecord(550000);
        d.checkRecord(610000);

        d.flush();
        assertEquals(10, d.getBucketCount());
        assertEquals(0, d.getEmptyBucketCount());
        assertEquals(0, d.getSparseBucketCount());
        assertEquals(null, d.getLatestSparseBucketTime());
        assertEquals(null, d.getLatestEmptyBucketTime());
    }

    public void testEmptyBuckets() {
        DataStreamDiagnostics d = new DataStreamDiagnostics(job);

        d.checkRecord(10000);
        d.checkRecord(70000);
        // empty bucket
        d.checkRecord(190000);
        d.checkRecord(250000);
        d.checkRecord(310000);
        d.checkRecord(370000);
        // empty bucket
        d.checkRecord(490000);
        d.checkRecord(550000);

        d.flush();
        assertEquals(10, d.getBucketCount());
        assertEquals(2, d.getEmptyBucketCount());
        assertEquals(0, d.getSparseBucketCount());
        assertEquals(null, d.getLatestSparseBucketTime());
        assertEquals(new Date(420000), d.getLatestEmptyBucketTime());
    }

    public void testEmptyBucketsStartLater() {
        DataStreamDiagnostics d = new DataStreamDiagnostics(job);

        d.checkRecord(1110000);
        d.checkRecord(1170000);
        // empty bucket
        d.checkRecord(1290000);
        d.checkRecord(1350000);
        d.checkRecord(1410000);
        d.checkRecord(1470000);
        // empty bucket
        d.checkRecord(1590000);
        d.checkRecord(1650000);

        d.flush();
        assertEquals(10, d.getBucketCount());
        assertEquals(2, d.getEmptyBucketCount());
        assertEquals(0, d.getSparseBucketCount());
        assertEquals(null, d.getLatestSparseBucketTime());
        assertEquals(new Date(1500000), d.getLatestEmptyBucketTime());
    }

    public void testSparseBuckets() {
        DataStreamDiagnostics d = new DataStreamDiagnostics(job);

        sendManyDataPoints(d, 10000, 69000, 1000);
        sendManyDataPoints(d, 70000, 129000, 1200);
        // sparse bucket
        sendManyDataPoints(d, 130000, 189000, 1);
        sendManyDataPoints(d, 190000, 249000, 1100);
        sendManyDataPoints(d, 250000, 309000, 1300);
        sendManyDataPoints(d, 310000, 369000, 1050);
        sendManyDataPoints(d, 370000, 429000, 1022);
        // sparse bucket
        sendManyDataPoints(d, 430000, 489000, 10);
        sendManyDataPoints(d, 490000, 549000, 1333);
        sendManyDataPoints(d, 550000, 609000, 1400);

        d.flush();
        assertEquals(10, d.getBucketCount());
        assertEquals(0, d.getEmptyBucketCount());
        assertEquals(2, d.getSparseBucketCount());
        assertEquals(new Date(420000), d.getLatestSparseBucketTime());
        assertEquals(null, d.getLatestEmptyBucketTime());
    }

    /**
     * Test for sparsity on the last bucket should not create a sparse bucket
     * signal
     */
    public void testSparseBucketsLast() {
        DataStreamDiagnostics d = new DataStreamDiagnostics(job);

        sendManyDataPoints(d, 10000, 69000, 1000);
        sendManyDataPoints(d, 70000, 129000, 1200);
        // sparse bucket
        sendManyDataPoints(d, 130000, 189000, 1);
        sendManyDataPoints(d, 190000, 249000, 1100);
        sendManyDataPoints(d, 250000, 309000, 1300);
        sendManyDataPoints(d, 310000, 369000, 1050);
        sendManyDataPoints(d, 370000, 429000, 1022);
        sendManyDataPoints(d, 430000, 489000, 1400);
        sendManyDataPoints(d, 490000, 549000, 1333);
        // sparse bucket (but last one)
        sendManyDataPoints(d, 550000, 609000, 10);

        d.flush();
        assertEquals(10, d.getBucketCount());
        assertEquals(0, d.getEmptyBucketCount());
        assertEquals(1, d.getSparseBucketCount());
        assertEquals(new Date(120000), d.getLatestSparseBucketTime());
        assertEquals(null, d.getLatestEmptyBucketTime());
    }

    /**
     * Test for sparsity on the last 2 buckets, should create a sparse bucket
     * signal on the 2nd to last
     */
    public void testSparseBucketsLastTwo() {
        DataStreamDiagnostics d = new DataStreamDiagnostics(job);

        sendManyDataPoints(d, 10000, 69000, 1000);
        sendManyDataPoints(d, 70000, 129000, 1200);
        // sparse bucket
        sendManyDataPoints(d, 130000, 189000, 1);
        sendManyDataPoints(d, 190000, 249000, 1100);
        sendManyDataPoints(d, 250000, 309000, 1300);
        sendManyDataPoints(d, 310000, 369000, 1050);
        sendManyDataPoints(d, 370000, 429000, 1022);
        sendManyDataPoints(d, 430000, 489000, 1400);
        // sparse bucket (2nd to last one)
        sendManyDataPoints(d, 490000, 549000, 9);
        // sparse bucket (but last one)
        sendManyDataPoints(d, 550000, 609000, 10);

        d.flush();
        assertEquals(10, d.getBucketCount());
        assertEquals(0, d.getEmptyBucketCount());
        assertEquals(2, d.getSparseBucketCount());
        assertEquals(new Date(480000), d.getLatestSparseBucketTime());
        assertEquals(null, d.getLatestEmptyBucketTime());
    }

    public void testMixedEmptyAndSparseBuckets() {
        DataStreamDiagnostics d = new DataStreamDiagnostics(job);

        sendManyDataPoints(d, 10000, 69000, 1000);
        sendManyDataPoints(d, 70000, 129000, 1200);
        // sparse bucket
        sendManyDataPoints(d, 130000, 189000, 1);
        // empty bucket
        sendManyDataPoints(d, 250000, 309000, 1300);
        sendManyDataPoints(d, 310000, 369000, 1050);
        sendManyDataPoints(d, 370000, 429000, 1022);
        // sparse bucket
        sendManyDataPoints(d, 430000, 489000, 10);
        // empty bucket
        sendManyDataPoints(d, 550000, 609000, 1400);

        d.flush();
        assertEquals(10, d.getBucketCount());
        assertEquals(2, d.getSparseBucketCount());
        assertEquals(new Date(420000), d.getLatestSparseBucketTime());
        assertEquals(2, d.getEmptyBucketCount());
        assertEquals(new Date(480000), d.getLatestEmptyBucketTime());
    }

    /**
     * Send signals, then make a long pause, send another signal and then check
     * whether counts are right.
     */
    public void testEmptyBucketsLongerOutage() {
        DataStreamDiagnostics d = new DataStreamDiagnostics(job);

        d.checkRecord(10000);
        d.checkRecord(70000);
        // empty bucket
        d.checkRecord(190000);
        d.checkRecord(250000);
        d.checkRecord(310000);
        d.checkRecord(370000);
        // empty bucket
        d.checkRecord(490000);
        d.checkRecord(550000);
        // 98 empty buckets
        d.checkRecord(6490000);
        d.flush();
        assertEquals(109, d.getBucketCount());
        assertEquals(100, d.getEmptyBucketCount());
        assertEquals(0, d.getSparseBucketCount());
        assertEquals(null, d.getLatestSparseBucketTime());
        assertEquals(new Date(6420000), d.getLatestEmptyBucketTime());
    }

    /**
     * Send signals, make a longer period of sparse signals, then go up again
     * 
     * The number of sparse buckets should not be to much, it could be normal.
     */
    public void testSparseBucketsLongerPeriod() {
        DataStreamDiagnostics d = new DataStreamDiagnostics(job);

        sendManyDataPoints(d, 10000, 69000, 1000);
        sendManyDataPoints(d, 70000, 129000, 1200);
        // sparse bucket
        sendManyDataPoints(d, 130000, 189000, 1);
        sendManyDataPoints(d, 190000, 249000, 1100);
        sendManyDataPoints(d, 250000, 309000, 1300);
        sendManyDataPoints(d, 310000, 369000, 1050);
        sendManyDataPoints(d, 370000, 429000, 1022);
        // sparse bucket
        sendManyDataPoints(d, 430000, 489000, 10);
        sendManyDataPoints(d, 490000, 549000, 1333);
        sendManyDataPoints(d, 550000, 609000, 1400);

        d.flush();
        assertEquals(10, d.getBucketCount());
        assertEquals(0, d.getEmptyBucketCount());
        assertEquals(2, d.getSparseBucketCount());
        assertEquals(new Date(420000), d.getLatestSparseBucketTime());
        assertEquals(null, d.getLatestEmptyBucketTime());
    }

    public void testFlushAfterZeroRecords() {
        DataStreamDiagnostics d = new DataStreamDiagnostics(job);
        d.flush();
        assertEquals(0, d.getBucketCount());
    }

    private void sendManyDataPoints(DataStreamDiagnostics d, long recordTimestampInMsMin, long recordTimestampInMsMax, long howMuch) {

        long range = recordTimestampInMsMax - recordTimestampInMsMin;

        for (int i = 0; i < howMuch; ++i) {
            d.checkRecord(recordTimestampInMsMin + i % range);
        }
    }
}
