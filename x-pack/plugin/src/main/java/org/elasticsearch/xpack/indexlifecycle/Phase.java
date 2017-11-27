/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.indexlifecycle;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.ObjectParser.ValueType;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.xpack.indexlifecycle.IndexLifecycleContext.Listener;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Represents set of {@link LifecycleAction}s which should be executed at a
 * particular point in the lifecycle of an index.
 */
public class Phase implements ToXContentObject, Writeable {
    public static final String PHASE_COMPLETED = "ACTIONS COMPLETED";

    private static final Logger logger = ESLoggerFactory.getLogger(Phase.class);

    public static final ParseField AFTER_FIELD = new ParseField("after");
    public static final ParseField ACTIONS_FIELD = new ParseField("actions");

    @SuppressWarnings("unchecked")
    private static final ConstructingObjectParser<Phase, Tuple<String, NamedXContentRegistry>> PARSER = new ConstructingObjectParser<>(
            "phase", false, (a, c) -> new Phase(c.v1(), (TimeValue) a[0], (List<LifecycleAction>) a[1]));
    static {
        PARSER.declareField(ConstructingObjectParser.constructorArg(),
                (p, c) -> TimeValue.parseTimeValue(p.text(), AFTER_FIELD.getPreferredName()), AFTER_FIELD, ValueType.VALUE);
        PARSER.declareNamedObjects(ConstructingObjectParser.constructorArg(),
                (p, c, n) -> c.v2().parseNamedObject(LifecycleAction.class, n, p, c.v2()), v -> {
                    throw new IllegalArgumentException("ordered " + ACTIONS_FIELD.getPreferredName() + " are not supported");
                }, ACTIONS_FIELD);
    }

    public static Phase parse(XContentParser parser, Tuple<String, NamedXContentRegistry> context) {
        return PARSER.apply(parser, context);
    }

    private String name;
    private List<LifecycleAction> actions;
    private TimeValue after;

    /**
     * @param name
     *            the name of this {@link Phase}.
     * @param after
     *            the age of the index when the index should move to this
     *            {@link Phase}.
     * @param actions
     *            a {@link List} of the {@link LifecycleAction}s to run when
     *            during his {@link Phase}. The order of this list defines the
     *            order in which the {@link LifecycleAction}s will be run.
     */
    public Phase(String name, TimeValue after, List<LifecycleAction> actions) {
        this.name = name;
        this.after = after;
        this.actions = actions;
    }

    /**
     * For Serialization
     */
    public Phase(StreamInput in) throws IOException {
        this.name = in.readString();
        this.after = new TimeValue(in);
        this.actions = in.readNamedWriteableList(LifecycleAction.class);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(name);
        after.writeTo(out);
        out.writeNamedWriteableList(actions);
    }

    /**
     * @return the age of the index when the index should move to this
     *         {@link Phase}.
     */
    public TimeValue getAfter() {
        return after;
    }

    /**
     * @return the name of this {@link Phase}
     */
    public String getName() {
        return name;
    }

    /**
     * @return a {@link List} of the {@link LifecycleAction}s to run when during
     *         his {@link Phase}. The order of this list defines the order in
     *         which the {@link LifecycleAction}s will be run.
     */
    public List<LifecycleAction> getActions() {
        return actions;
    }

    /**
     * Checks the current state and executes the appropriate
     * {@link LifecycleAction}.
     * 
     * @param context
     *            the {@link IndexLifecycleContext} to use to execute the
     *            {@link Phase}.
     */
    protected void execute(IndexLifecycleContext context) {
        String currentActionName = context.getAction();
        String indexName = context.getLifecycleTarget();
        if (Strings.isNullOrEmpty(currentActionName)) {
            // This is is the first time this phase has been called so get the first action and execute it.
            String firstActionName;
            LifecycleAction firstAction;
            if (actions.isEmpty()) {
                // There are no actions in this phase so use the PHASE_COMPLETE action name.
                firstAction = null;
                firstActionName = PHASE_COMPLETED;
            } else {
                firstAction = actions.get(0);
                firstActionName = firstAction.getWriteableName();
            }
            // Set the action on the context to this first action so we know where we are next time we execute
            context.setAction(firstActionName, new Listener() {

                @Override
                public void onSuccess() {
                    logger.info("Successfully initialised action [" + firstActionName + "] for index [" + indexName + "]");
                    // Now execute the action unless its PHASE_COMPLETED
                    if (firstActionName.equals(PHASE_COMPLETED) == false) {
                        executeAction(context, indexName, 0, firstAction);
                    }
                }

                @Override
                public void onFailure(Exception e) {
                    // Something went wrong so log the error and hopfully it will succeed next time execute is called. NOCOMMIT can we do better here?
                    logger.error("Failed to initialised action [" + firstActionName + "] for index [" + indexName + "]", e);
                }
            });
        } else if (currentActionName.equals(PHASE_COMPLETED) == false) {
            // We have an action name and its not PHASE COMPLETED so we need to execute the action
            int currentActionIndex = -1;
            // First find the action in the actions list.
            for (int i = 0; i < actions.size(); i++) {
                if (actions.get(i).getWriteableName().equals(currentActionName)) {
                    currentActionIndex = i;
                    break;
                }
            }
            if (currentActionIndex == -1) {
                throw new IllegalStateException("Current action [" + currentActionName + "] not found in phase ["
                        + getName() + "] for index [" + indexName + "]");
            }
            LifecycleAction currentAction = actions.get(currentActionIndex);
            executeAction(context, indexName, currentActionIndex, currentAction);
        }
    }

    private void executeAction(IndexLifecycleContext context, String indexName, int actionIndex, LifecycleAction action) {
        String actionName = action.getWriteableName();
        context.executeAction(action, new LifecycleAction.Listener() {

            @Override
            public void onSuccess(boolean completed) {
                if (completed) {
                    logger.info("Action [" + actionName + "] for index [" + indexName + "] complete, moving to next action");
                    // Since we completed the current action move to the next action
                    moveToAction(context, indexName, actionIndex + 1);
                } else {
                    logger.info("Action [" + actionName + "] for index [" + indexName + "] executed sucessfully but is not yet complete");
                }
            }

            @Override
            public void onFailure(Exception e) {
                logger.info("Action [" + actionName + "] for index [" + indexName + "] failed", e);
            }
        });
    }

    private void moveToAction(IndexLifecycleContext context, String indexName, final int nextActionIndex) {
        if (nextActionIndex < actions.size()) {
            LifecycleAction nextAction = actions.get(nextActionIndex);
            context.setAction(nextAction.getWriteableName(), new Listener() {

                @Override
                public void onSuccess() {
                    logger.info("Successfully initialised action [" + nextAction.getWriteableName() + "] in phase [" + getName()
                            + "] for index [" + indexName + "]");
                    // We might as well execute the new action now rather than waiting for execute to be called again
                    execute(context);
                }

                @Override
                public void onFailure(Exception e) {
                    logger.error("Failed to initialised action [" + nextAction.getWriteableName() + "] in phase [" + getName()
                            + "] for index [" + indexName + "]", e);
                }
            });
        } else {
            // There is no next action so set the action to PHASE_COMPLETED
            context.setAction(Phase.PHASE_COMPLETED, new Listener() {

                @Override
                public void onSuccess() {
                    logger.info("Successfully completed phase [" + getName() + "] for index [" + indexName + "]");
                }

                @Override
                public void onFailure(Exception e) {
                    logger.error("Failed to complete phase [" + getName() + "] for index [" + indexName + "]", e);
                }
            });
        }
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(AFTER_FIELD.getPreferredName(), after.seconds() + "s"); // Need a better way to get a parsable format out here
        builder.startObject(ACTIONS_FIELD.getPreferredName());
        for (LifecycleAction action : actions) {
            builder.field(action.getWriteableName(), action);
        }
        builder.endObject();
        builder.endObject();
        return builder;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(name, after, actions);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (obj.getClass() != getClass()) {
            return false;
        }
        Phase other = (Phase) obj;
        return Objects.equals(name, other.name) &&
                Objects.equals(after, other.after) &&
                Objects.equals(actions, other.actions);
    }

    @Override
    public String toString() {
        return Strings.toString(this, true, true);
    }

}
