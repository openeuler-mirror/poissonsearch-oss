/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.alerts.actions;

import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.alerts.Alert;
import org.elasticsearch.alerts.triggers.TriggerResult;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class AlertActionRegistry extends AbstractComponent {

    private static volatile ImmutableOpenMap<String, AlertActionFactory> actionImplemented;

    @Inject
    public AlertActionRegistry(Settings settings, Client client) {
        super(settings);
        actionImplemented = ImmutableOpenMap.<String, AlertActionFactory>builder()
                .fPut("email", new EmailAlertActionFactory())
                .fPut("index", new IndexAlertActionFactory(client))
                .build();
    }

    public void registerAction(String name, AlertActionFactory actionFactory){
        actionImplemented = ImmutableOpenMap.builder(actionImplemented)
                .fPut(name, actionFactory)
                .build();
    }


    public List<AlertAction> instantiateAlertActions(XContentParser parser) throws IOException {
        List<AlertAction> actions = new ArrayList<>();
        ImmutableOpenMap<String, AlertActionFactory> actionImplemented = this.actionImplemented;
        String actionFactoryName = null;
        XContentParser.Token token;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                actionFactoryName = parser.currentName();
            } else if (token == XContentParser.Token.START_OBJECT) {
                AlertActionFactory factory = actionImplemented.get(actionFactoryName);
                if (factory != null) {
                    actions.add(factory.createAction(parser));
                } else {
                    throw new ElasticsearchIllegalArgumentException("No action exists with the name [" + actionFactoryName + "]");
                }
            }
        }
        return actions;
    }

    public void doAction(Alert alert, TriggerResult actionEntry){
        for (AlertAction action : alert.actions()) {
            AlertActionFactory factory = actionImplemented.get(action.getActionName());
            if (factory != null) {
                factory.doAction(action, alert, actionEntry);
            } else {
                throw new ElasticsearchIllegalArgumentException("No action exists with the name [" + action.getActionName() + "]");
            }
        }
    }

}
