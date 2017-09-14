/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.cli.net.protocol;

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.sql.protocol.shared.TimeoutInfo;

import java.io.IOException;
import java.util.TimeZone;

import static org.elasticsearch.xpack.sql.cli.net.protocol.CliRoundTripTestUtils.assertRoundTripCurrentVersion;
import static org.elasticsearch.xpack.sql.cli.net.protocol.CliRoundTripTestUtils.randomTimeoutInfo;

public class QueryInitRequestTests extends ESTestCase {
    static QueryInitRequest randomQueryInitRequest() {
        return new QueryInitRequest(randomAlphaOfLength(5), between(0, Integer.MAX_VALUE), randomTimeZone(random()), randomTimeoutInfo());
    }

    public void testRoundTrip() throws IOException {
        assertRoundTripCurrentVersion(randomQueryInitRequest());
    }

    public void testToString() {
        assertEquals("QueryInitRequest<query=[SELECT * FROM test.doc]>",
                new QueryInitRequest("SELECT * FROM test.doc", 10, TimeZone.getTimeZone("UTC"), new TimeoutInfo(1, 1, 1)).toString());
        assertEquals("QueryInitRequest<query=[SELECT * FROM test.doc] timeZone=[GMT-05:00]>",
                new QueryInitRequest("SELECT * FROM test.doc", 10, TimeZone.getTimeZone("GMT-5"), new TimeoutInfo(1, 1, 1)).toString());
    }
}
