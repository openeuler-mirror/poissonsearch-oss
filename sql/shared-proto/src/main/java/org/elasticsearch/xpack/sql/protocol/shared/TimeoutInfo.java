/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.protocol.shared;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Objects;

public class TimeoutInfo {
    public final long clientTime, timeout, requestTimeout;

    public TimeoutInfo(long clientTime, long timeout, long requestTimeout) {
        this.clientTime = clientTime;
        this.timeout = timeout;
        this.requestTimeout = requestTimeout;
    }

    TimeoutInfo(DataInput in) throws IOException {
        clientTime = in.readLong();
        timeout = in.readLong();
        requestTimeout = in.readLong();
    }

    void writeTo(DataOutput out) throws IOException {
        out.writeLong(clientTime);
        out.writeLong(timeout);
        out.writeLong(requestTimeout);
    }

    @Override
    public String toString() {
        return "client=" + clientTime + ",timeout=" + timeout + ",request=" + requestTimeout;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || obj.getClass() != getClass()) {
            return false;
        }
        TimeoutInfo other = (TimeoutInfo) obj;
        return clientTime == other.clientTime
                && timeout == other.timeout
                && requestTimeout == other.requestTimeout;
    }

    @Override
    public int hashCode() {
        return Objects.hash(clientTime, timeout, requestTimeout);
    }
}
