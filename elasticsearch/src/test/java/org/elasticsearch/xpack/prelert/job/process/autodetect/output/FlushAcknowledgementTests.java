/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.job.process.autodetect.output;

import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.io.stream.Writeable.Reader;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.xpack.prelert.support.AbstractSerializingTestCase;

public class FlushAcknowledgementTests extends AbstractSerializingTestCase<FlushAcknowledgement> {

    @Override
    protected FlushAcknowledgement parseInstance(XContentParser parser, ParseFieldMatcher matcher) {
        return FlushAcknowledgement.PARSER.apply(parser, () -> matcher);
    }

    @Override
    protected FlushAcknowledgement createTestInstance() {
        return new FlushAcknowledgement(randomAsciiOfLengthBetween(1, 20));
    }

    @Override
    protected Reader<FlushAcknowledgement> instanceReader() {
        return FlushAcknowledgement::new;
    }

}
