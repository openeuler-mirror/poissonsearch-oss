/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.job.process.normalizer;

import org.elasticsearch.xpack.prelert.job.results.Influencer;

import java.util.Objects;

class InfluencerNormalizable extends AbstractLeafNormalizable {
    private final Influencer influencer;

    public InfluencerNormalizable(Influencer influencer) {
        this.influencer = Objects.requireNonNull(influencer);
    }

    @Override
    public Level getLevel() {
        return Level.INFLUENCER;
    }

    @Override
    public String getPartitionFieldName() {
        return null;
    }

    @Override
    public String getPartitionFieldValue() {
        return null;
    }

    @Override
    public String getPersonFieldName() {
        return influencer.getInfluencerFieldName();
    }

    @Override
    public String getFunctionName() {
        return null;
    }

    @Override
    public String getValueFieldName() {
        return null;
    }

    @Override
    public double getProbability() {
        return influencer.getProbability();
    }

    @Override
    public double getNormalizedScore() {
        return influencer.getAnomalyScore();
    }

    @Override
    public void setNormalizedScore(double normalizedScore) {
        influencer.setAnomalyScore(normalizedScore);
    }

    @Override
    public void setParentScore(double parentScore) {
        throw new IllegalStateException("Influencer has no parent");
    }

    @Override
    public void resetBigChangeFlag() {
        influencer.resetBigNormalizedUpdateFlag();
    }

    @Override
    public void raiseBigChangeFlag() {
        influencer.raiseBigNormalizedUpdateFlag();
    }
}
