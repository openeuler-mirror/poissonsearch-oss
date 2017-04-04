/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.action;

import org.elasticsearch.xpack.ml.action.GetInfluencersAction.Response;
import org.elasticsearch.xpack.ml.action.util.QueryPage;
import org.elasticsearch.xpack.ml.job.results.Influencer;
import org.elasticsearch.xpack.ml.support.AbstractStreamableTestCase;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class GetInfluencersActionResponseTests extends AbstractStreamableTestCase<GetInfluencersAction.Response> {

    @Override
    protected Response createTestInstance() {
        int listSize = randomInt(10);
        List<Influencer> hits = new ArrayList<>(listSize);
        for (int j = 0; j < listSize; j++) {
            Influencer influencer = new Influencer(randomAlphaOfLengthBetween(1, 20), randomAlphaOfLengthBetween(1, 20),
                    randomAlphaOfLengthBetween(1, 20), new Date(randomNonNegativeLong()), randomNonNegativeLong(), j + 1);
            influencer.setInfluencerScore(randomDouble());
            influencer.setInitialInfluencerScore(randomDouble());
            influencer.setProbability(randomDouble());
            influencer.setInterim(randomBoolean());
            hits.add(influencer);
        }
        QueryPage<Influencer> buckets = new QueryPage<>(hits, listSize, Influencer.RESULTS_FIELD);
        return new Response(buckets);
    }

    @Override
    protected Response createBlankInstance() {
        return new Response();
    }

}
