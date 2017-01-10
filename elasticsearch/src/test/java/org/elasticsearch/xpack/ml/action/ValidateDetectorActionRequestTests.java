/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.action;

import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.xpack.ml.action.ValidateDetectorAction.Request;
import org.elasticsearch.xpack.ml.job.Detector;
import org.elasticsearch.xpack.ml.support.AbstractStreamableXContentTestCase;

public class ValidateDetectorActionRequestTests extends AbstractStreamableXContentTestCase<ValidateDetectorAction.Request> {

    @Override
    protected Request createTestInstance() {
        Detector.Builder detector;
        if (randomBoolean()) {
            detector = new Detector.Builder(randomFrom(Detector.COUNT_WITHOUT_FIELD_FUNCTIONS), null);
        } else {
            detector = new Detector.Builder(randomFrom(Detector.FIELD_NAME_FUNCTIONS), randomAsciiOfLengthBetween(1, 20));
        }
        return new Request(detector.build());
    }

    @Override
    protected Request createBlankInstance() {
        return new Request();
    }

    @Override
    protected Request parseInstance(XContentParser parser, ParseFieldMatcher matcher) {
        return Request.parseRequest(parser, () -> matcher);
    }

}
