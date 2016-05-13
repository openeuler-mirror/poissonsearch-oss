/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.marvel.agent.resolver.bulk;

import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.marvel.action.MonitoringBulkDoc;
import org.elasticsearch.marvel.agent.resolver.MonitoringIndexNameResolver;

import java.io.IOException;

public class MonitoringBulkDataResolver extends MonitoringIndexNameResolver.Data<MonitoringBulkDoc> {

    @Override
    public String type(MonitoringBulkDoc document) {
        return document.getType();
    }

    @Override
    public String id(MonitoringBulkDoc document) {
        return document.getId();
    }

    @Override
    protected void buildXContent(MonitoringBulkDoc document, XContentBuilder builder, ToXContent.Params params) throws IOException {
        BytesReference source = document.getSource();
        if (source != null && source.length() > 0) {
            builder.rawField(type(document), source);
        }
    }
}