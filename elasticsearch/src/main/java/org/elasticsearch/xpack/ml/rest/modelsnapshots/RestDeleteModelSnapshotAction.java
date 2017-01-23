/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.rest.modelsnapshots;

import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.AcknowledgedRestListener;
import org.elasticsearch.xpack.ml.MlPlugin;
import org.elasticsearch.xpack.ml.action.DeleteModelSnapshotAction;
import org.elasticsearch.xpack.ml.job.config.Job;
import org.elasticsearch.xpack.ml.job.process.autodetect.state.ModelSnapshot;

import java.io.IOException;

public class RestDeleteModelSnapshotAction extends BaseRestHandler {

    public RestDeleteModelSnapshotAction(Settings settings, RestController controller) {
        super(settings);
        controller.registerHandler(RestRequest.Method.DELETE, MlPlugin.BASE_PATH + "anomaly_detectors/{"
                + Job.ID.getPreferredName() + "}/model_snapshots/{" + ModelSnapshot.SNAPSHOT_ID.getPreferredName() + "}", this);
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest restRequest, NodeClient client) throws IOException {
        DeleteModelSnapshotAction.Request deleteModelSnapshot = new DeleteModelSnapshotAction.Request(
                restRequest.param(Job.ID.getPreferredName()),
                restRequest.param(ModelSnapshot.SNAPSHOT_ID.getPreferredName()));

        return channel -> client.execute(DeleteModelSnapshotAction.INSTANCE, deleteModelSnapshot, new AcknowledgedRestListener<>(channel));
    }
}
