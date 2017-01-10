/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.action;

import org.elasticsearch.xpack.ml.action.DeleteSchedulerAction.Request;
import org.elasticsearch.xpack.ml.support.AbstractStreamableTestCase;

public class DeleteSchedulerRequestTests extends AbstractStreamableTestCase<Request> {

    @Override
    protected Request createTestInstance() {
        return new Request(randomAsciiOfLengthBetween(1, 20));
    }

    @Override
    protected Request createBlankInstance() {
        return new Request();
    }
}