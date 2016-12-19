/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.job.process.normalizer;

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.prelert.job.results.BucketInfluencer;
import org.junit.Before;

import java.util.Date;


public class BucketInfluencerNormalizableTests extends ESTestCase {
    private static final double EPSILON = 0.0001;
    private BucketInfluencer bucketInfluencer;

    @Before
    public void setUpBucketInfluencer() {
        bucketInfluencer = new BucketInfluencer("foo", new Date(), 600, 1);
        bucketInfluencer.setInfluencerFieldName("airline");
        bucketInfluencer.setProbability(0.05);
        bucketInfluencer.setRawAnomalyScore(3.14);
        bucketInfluencer.setInitialAnomalyScore(2.0);
        bucketInfluencer.setAnomalyScore(1.0);
    }

    public void testIsContainerOnly() {
        assertFalse(new BucketInfluencerNormalizable(bucketInfluencer).isContainerOnly());
    }

    public void testGetLevel() {
        assertEquals(Level.BUCKET_INFLUENCER, new BucketInfluencerNormalizable(bucketInfluencer).getLevel());

        BucketInfluencer timeInfluencer = new BucketInfluencer("foo", new Date(), 600, 1);
        timeInfluencer.setInfluencerFieldName(BucketInfluencer.BUCKET_TIME);
        assertEquals(Level.ROOT, new BucketInfluencerNormalizable(timeInfluencer).getLevel());
    }

    public void testGetPartitionFieldName() {
        assertNull(new BucketInfluencerNormalizable(bucketInfluencer).getPartitionFieldName());
    }

    public void testGetPersonFieldName() {
        assertEquals("airline", new BucketInfluencerNormalizable(bucketInfluencer).getPersonFieldName());
    }

    public void testGetFunctionName() {
        assertNull(new BucketInfluencerNormalizable(bucketInfluencer).getFunctionName());
    }

    public void testGetValueFieldName() {
        assertNull(new BucketInfluencerNormalizable(bucketInfluencer).getValueFieldName());
    }

    public void testGetProbability() {
        assertEquals(0.05, new BucketInfluencerNormalizable(bucketInfluencer).getProbability(), EPSILON);
    }

    public void testGetNormalizedScore() {
        assertEquals(1.0, new BucketInfluencerNormalizable(bucketInfluencer).getNormalizedScore(), EPSILON);
    }

    public void testSetNormalizedScore() {
        BucketInfluencerNormalizable normalizable = new BucketInfluencerNormalizable(bucketInfluencer);

        normalizable.setNormalizedScore(99.0);

        assertEquals(99.0, normalizable.getNormalizedScore(), EPSILON);
        assertEquals(99.0, bucketInfluencer.getAnomalyScore(), EPSILON);
    }

    public void testGetChildrenTypes() {
        assertTrue(new BucketInfluencerNormalizable(bucketInfluencer).getChildrenTypes().isEmpty());
    }

    public void testGetChildren_ByType() {
        expectThrows(IllegalStateException.class, () -> new BucketInfluencerNormalizable(bucketInfluencer).getChildren(0));
    }

    public void testGetChildren() {
        assertTrue(new BucketInfluencerNormalizable(bucketInfluencer).getChildren().isEmpty());
    }

    public void testSetMaxChildrenScore() {
        expectThrows(IllegalStateException.class, () -> new BucketInfluencerNormalizable(bucketInfluencer).setMaxChildrenScore(0, 42.0));
    }

    public void testSetParentScore() {
        new BucketInfluencerNormalizable(bucketInfluencer).setParentScore(42.0);

        assertEquals("airline", bucketInfluencer.getInfluencerFieldName());
        assertEquals(1.0, bucketInfluencer.getAnomalyScore(), EPSILON);
        assertEquals(3.14, bucketInfluencer.getRawAnomalyScore(), EPSILON);
        assertEquals(2.0, bucketInfluencer.getInitialAnomalyScore(), EPSILON);
        assertEquals(0.05, bucketInfluencer.getProbability(), EPSILON);
    }

    public void testResetBigChangeFlag() {
        new BucketInfluencerNormalizable(bucketInfluencer).resetBigChangeFlag();
    }

    public void testRaiseBigChangeFlag() {
        new BucketInfluencerNormalizable(bucketInfluencer).raiseBigChangeFlag();
    }
}
