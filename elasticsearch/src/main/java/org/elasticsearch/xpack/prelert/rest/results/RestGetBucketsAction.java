/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.rest.results;

import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.RestStatusToXContentListener;
import org.elasticsearch.xpack.prelert.PrelertPlugin;
import org.elasticsearch.xpack.prelert.action.GetBucketsAction;
import org.elasticsearch.xpack.prelert.job.Job;
import org.elasticsearch.xpack.prelert.job.results.Bucket;
import org.elasticsearch.xpack.prelert.job.results.PageParams;

import java.io.IOException;

public class RestGetBucketsAction extends BaseRestHandler {

    private final GetBucketsAction.TransportAction transportAction;

    @Inject
    public RestGetBucketsAction(Settings settings, RestController controller, GetBucketsAction.TransportAction transportAction) {
        super(settings);
        this.transportAction = transportAction;
        controller.registerHandler(RestRequest.Method.GET,
                PrelertPlugin.BASE_PATH + "anomaly_detectors/{" + Job.ID.getPreferredName()
                        + "}/results/buckets/{" + Bucket.TIMESTAMP.getPreferredName() + "}", this);
        controller.registerHandler(RestRequest.Method.POST,
                PrelertPlugin.BASE_PATH + "anomaly_detectors/{" + Job.ID.getPreferredName()
                        + "}/results/buckets/{" + Bucket.TIMESTAMP.getPreferredName() + "}", this);

        controller.registerHandler(RestRequest.Method.GET,
                PrelertPlugin.BASE_PATH + "anomaly_detectors/{" + Job.ID.getPreferredName() + "}/results/buckets", this);
        controller.registerHandler(RestRequest.Method.POST,
                PrelertPlugin.BASE_PATH + "anomaly_detectors/{" + Job.ID.getPreferredName() + "}/results/buckets", this);
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest restRequest, NodeClient client) throws IOException {
        String jobId = restRequest.param(Job.ID.getPreferredName());
        final GetBucketsAction.Request request;
        if (restRequest.hasContent()) {
            XContentParser parser = restRequest.contentParser();
            request = GetBucketsAction.Request.parseRequest(jobId, parser, () -> parseFieldMatcher);
        } else {
            request = new GetBucketsAction.Request(jobId);
            String timestamp = restRequest.param(GetBucketsAction.Request.TIMESTAMP.getPreferredName());

            // Single bucket
            if (timestamp != null && !timestamp.isEmpty()) {
                request.setTimestamp(timestamp);
            }
            if (restRequest.hasParam(PageParams.FROM.getPreferredName())
                    || restRequest.hasParam(PageParams.SIZE.getPreferredName())
                    || restRequest.hasParam(GetBucketsAction.Request.START.getPreferredName())
                    || restRequest.hasParam(GetBucketsAction.Request.END.getPreferredName())
                    || restRequest.hasParam(GetBucketsAction.Request.ANOMALY_SCORE.getPreferredName())
                    || restRequest.hasParam(GetBucketsAction.Request.MAX_NORMALIZED_PROBABILITY.getPreferredName())
                    || timestamp == null) {

                // Multiple buckets
                request.setStart(restRequest.param(GetBucketsAction.Request.START.getPreferredName()));
                request.setEnd(restRequest.param(GetBucketsAction.Request.END.getPreferredName()));
                request.setPageParams(new PageParams(restRequest.paramAsInt(PageParams.FROM.getPreferredName(), PageParams.DEFAULT_FROM),
                        restRequest.paramAsInt(PageParams.SIZE.getPreferredName(), PageParams.DEFAULT_SIZE)));
                request.setAnomalyScore(
                        Double.parseDouble(restRequest.param(GetBucketsAction.Request.ANOMALY_SCORE.getPreferredName(), "0.0")));
                request.setMaxNormalizedProbability(
                        Double.parseDouble(restRequest.param(
                                GetBucketsAction.Request.MAX_NORMALIZED_PROBABILITY.getPreferredName(), "0.0")));
                request.setPartitionValue(restRequest.param(GetBucketsAction.Request.PARTITION_VALUE.getPreferredName()));
            }

            // Common options
            request.setExpand(restRequest.paramAsBoolean(GetBucketsAction.Request.EXPAND.getPreferredName(), false));
            request.setIncludeInterim(restRequest.paramAsBoolean(GetBucketsAction.Request.INCLUDE_INTERIM.getPreferredName(), false));
        }

        return channel -> transportAction.execute(request, new RestStatusToXContentListener<>(channel));
    }
}
