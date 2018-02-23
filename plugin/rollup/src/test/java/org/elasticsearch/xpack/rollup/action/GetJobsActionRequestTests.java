/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.rollup.action;


import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.test.AbstractStreamableTestCase;
import org.elasticsearch.xpack.core.rollup.action.GetRollupJobsAction.Request;


public class GetJobsActionRequestTests extends AbstractStreamableTestCase<Request> {

    @Override
    protected Request createTestInstance() {
        if (randomBoolean()) {
            return new Request(MetaData.ALL);
        }
        return new Request(randomAlphaOfLengthBetween(1, 20));
    }

    @Override
    protected Request createBlankInstance() {
        return new Request();
    }
}


