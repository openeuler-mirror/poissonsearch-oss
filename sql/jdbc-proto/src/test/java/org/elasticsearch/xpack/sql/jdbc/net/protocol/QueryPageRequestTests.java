/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.jdbc.net.protocol;

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.sql.protocol.shared.TimeoutInfo;

import java.io.IOException;

import static org.elasticsearch.xpack.sql.jdbc.net.protocol.JdbcRoundTripTestUtils.assertRoundTripCurrentVersion;
import static org.elasticsearch.xpack.sql.jdbc.net.protocol.JdbcRoundTripTestUtils.randomTimeoutInfo;

public class QueryPageRequestTests extends ESTestCase {
    static QueryPageRequest randomQueryPageRequest(Page page) {
        String cursor = randomAlphaOfLength(10);
        return new QueryPageRequest(cursor, randomTimeoutInfo(), page);
    }

    public void testRoundTrip() throws IOException {
        assertRoundTripCurrentVersion(randomQueryPageRequest(null));
    }

    public void testToString() {
        assertEquals("QueryPageRequest<0320>", new QueryPageRequest("0320", new TimeoutInfo(1, 1, 1), null).toString());
    }
}
