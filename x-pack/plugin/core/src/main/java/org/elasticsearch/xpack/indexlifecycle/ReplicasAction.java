/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.indexlifecycle;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsResponse;
import org.elasticsearch.action.support.ActiveShardCount;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.Index;

import java.io.IOException;
import java.util.Objects;

import static org.elasticsearch.common.xcontent.ConstructingObjectParser.constructorArg;

/**
 * A {@link LifecycleAction} that changes the number of replicas for the index.
 */
public class ReplicasAction implements LifecycleAction {
    public static final String NAME = "replicas";

    public static final ParseField NUMBER_OF_REPLICAS_FIELD = new ParseField("number_of_replicas");
    private static final ConstructingObjectParser<ReplicasAction, Void> PARSER = new ConstructingObjectParser<>(NAME,
        false, a -> new ReplicasAction((Integer) a[0]));

    static {
        PARSER.declareInt(constructorArg(), NUMBER_OF_REPLICAS_FIELD);
    }

    private int numberOfReplicas;

    public static ReplicasAction parse(XContentParser parser) {
        return PARSER.apply(parser, null);
    }

    public ReplicasAction(int numberOfReplicas) {
        if (numberOfReplicas < 0) {
            throw new IllegalArgumentException("[" + NUMBER_OF_REPLICAS_FIELD.getPreferredName() + "] must be >= 0");
        }
        this.numberOfReplicas = numberOfReplicas;
    }

    public ReplicasAction(StreamInput in) throws IOException {
        this.numberOfReplicas = in.readVInt();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVInt(numberOfReplicas);
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(NUMBER_OF_REPLICAS_FIELD.getPreferredName(), numberOfReplicas);
        builder.endObject();
        return builder;
    }

    @Override
    public void execute(Index index, Client client, ClusterService clusterService, Listener listener) {
        IndexMetaData idxMeta = clusterService.state().metaData().getIndexSafe(index);
        int currentNumberReplicas = idxMeta.getNumberOfReplicas();
        if (currentNumberReplicas == numberOfReplicas) {
            boolean isAllocationCompleted = ActiveShardCount.ALL.enoughShardsActive(clusterService.state(), index.getName());
            listener.onSuccess(isAllocationCompleted);
        } else {
            UpdateSettingsRequest updateSettingsRequest = new UpdateSettingsRequest(index.getName())
                    .settings(Settings.builder().put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, numberOfReplicas));
            client.admin().indices().updateSettings(updateSettingsRequest, new ActionListener<UpdateSettingsResponse>() {

                @Override
                public void onResponse(UpdateSettingsResponse response) {
                    listener.onSuccess(false);
                }

                @Override
                public void onFailure(Exception e) {
                    listener.onFailure(e);
                }
            });
        }
    }

    public int getNumberOfReplicas() {
        return numberOfReplicas;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(numberOfReplicas);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (obj.getClass() != getClass()) {
            return false;
        }
        ReplicasAction other = (ReplicasAction) obj;
        return Objects.equals(numberOfReplicas, other.numberOfReplicas);
    }

    @Override
    public String toString() {
        return Strings.toString(this);
    }

}
