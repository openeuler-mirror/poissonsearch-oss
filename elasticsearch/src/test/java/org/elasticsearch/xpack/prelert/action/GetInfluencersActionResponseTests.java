/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.action;

import org.elasticsearch.xpack.prelert.action.GetInfluencersAction.Response;
import org.elasticsearch.xpack.prelert.job.persistence.QueryPage;
import org.elasticsearch.xpack.prelert.job.results.Influencer;
import org.elasticsearch.xpack.prelert.support.AbstractStreamableTestCase;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class GetInfluencersActionResponseTests extends AbstractStreamableTestCase<GetInfluencersAction.Response> {

    @Override
    protected Response createTestInstance() {
        int listSize = randomInt(10);
        List<Influencer> hits = new ArrayList<>(listSize);
        for (int j = 0; j < listSize; j++) {
            Influencer influencer = new Influencer(randomAsciiOfLengthBetween(1, 20), randomAsciiOfLengthBetween(1, 20),
                    randomAsciiOfLengthBetween(1, 20));
            influencer.setAnomalyScore(randomDouble());
            influencer.setInitialAnomalyScore(randomDouble());
            influencer.setProbability(randomDouble());
            influencer.setId(randomAsciiOfLengthBetween(1, 20));
            influencer.setInterim(randomBoolean());
            influencer.setTimestamp(new Date(randomLong()));
            hits.add(influencer);
        }
        QueryPage<Influencer> buckets = new QueryPage<>(hits, listSize);
        return new Response(buckets);
    }

    @Override
    protected Response createBlankInstance() {
        return new Response();
    }

}
