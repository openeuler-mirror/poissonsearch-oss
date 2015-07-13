/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.transport.actions.get;

import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.watcher.support.xcontent.XContentSource;
import org.elasticsearch.watcher.watch.WatchStatus;

import java.io.IOException;

public class GetWatchResponse extends ActionResponse {

    private String id;
    private WatchStatus status;
    private boolean found = false;
    private XContentSource source;

    GetWatchResponse() {
    }

    /**
     * ctor for missing watch
     */
    public GetWatchResponse(String id) {
        this.id = id;
        this.found = false;
        this.source = null;
    }

    /**
     * ctor for found watch
     */
    public GetWatchResponse(String id, WatchStatus status, BytesReference source, XContentType contentType) {
        this.id = id;
        this.status = status;
        this.found = true;
        this.source = new XContentSource(source, contentType);
    }

    public String getId() {
        return id;
    }

    public WatchStatus getStatus() {
        return status;
    }

    public boolean isFound() {
        return found;
    }

    public XContentSource getSource() {
        return source;
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        id = in.readString();
        found = in.readBoolean();
        if (found) {
            status = WatchStatus.read(in);
            source = XContentSource.readFrom(in);
        }
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(id);
        out.writeBoolean(found);
        if (found) {
            status.writeTo(out);
            XContentSource.writeTo(source, out);
        }
    }
}
