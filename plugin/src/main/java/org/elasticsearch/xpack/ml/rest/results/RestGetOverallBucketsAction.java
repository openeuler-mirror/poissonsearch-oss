/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.rest.results;

import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.RestToXContentListener;
import org.elasticsearch.xpack.ml.MachineLearning;
import org.elasticsearch.xpack.ml.action.GetOverallBucketsAction;
import org.elasticsearch.xpack.ml.action.GetOverallBucketsAction.Request;
import org.elasticsearch.xpack.ml.job.config.Job;

import java.io.IOException;

public class RestGetOverallBucketsAction extends BaseRestHandler {

    public RestGetOverallBucketsAction(Settings settings, RestController controller) {
        super(settings);
        controller.registerHandler(RestRequest.Method.GET,
                MachineLearning.BASE_PATH + "anomaly_detectors/{" + Job.ID.getPreferredName() + "}/results/overall_buckets", this);
        controller.registerHandler(RestRequest.Method.POST,
                MachineLearning.BASE_PATH + "anomaly_detectors/{" + Job.ID.getPreferredName() + "}/results/overall_buckets", this);
    }

    @Override
    public String getName() {
        return "xpack_ml_get_overall_buckets_action";
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest restRequest, NodeClient client) throws IOException {
        String jobId = restRequest.param(Job.ID.getPreferredName());
        final Request request;
        if (restRequest.hasContentOrSourceParam()) {
            XContentParser parser = restRequest.contentOrSourceParamParser();
            request = Request.parseRequest(jobId, parser);
        } else {
            request = new Request(jobId);
            request.setTopN(restRequest.paramAsInt(Request.TOP_N.getPreferredName(), request.getTopN()));
            if (restRequest.hasParam(Request.BUCKET_SPAN.getPreferredName())) {
                request.setBucketSpan(restRequest.param(Request.BUCKET_SPAN.getPreferredName()));
            }
            request.setOverallScore(Double.parseDouble(restRequest.param(Request.OVERALL_SCORE.getPreferredName(), "0.0")));
            request.setExcludeInterim(restRequest.paramAsBoolean(Request.EXCLUDE_INTERIM.getPreferredName(), request.isExcludeInterim()));
            if (restRequest.hasParam(Request.START.getPreferredName())) {
                request.setStart(restRequest.param(Request.START.getPreferredName()));
            }
            if (restRequest.hasParam(Request.END.getPreferredName())) {
                request.setEnd(restRequest.param(Request.END.getPreferredName()));
            }
            request.setAllowNoJobs(restRequest.paramAsBoolean(Request.ALLOW_NO_JOBS.getPreferredName(), request.allowNoJobs()));
        }

        return channel -> client.execute(GetOverallBucketsAction.INSTANCE, request, new RestToXContentListener<>(channel));
    }
}
