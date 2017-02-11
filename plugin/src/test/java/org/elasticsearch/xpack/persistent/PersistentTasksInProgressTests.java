/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.persistent;

import org.elasticsearch.cluster.Diff;
import org.elasticsearch.cluster.NamedDiff;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.metadata.MetaData.Custom;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry.Entry;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.test.AbstractDiffableSerializationTestCase;
import org.elasticsearch.xpack.persistent.PersistentTasksInProgress.Builder;
import org.elasticsearch.xpack.persistent.PersistentTasksInProgress.PersistentTaskInProgress;
import org.elasticsearch.xpack.persistent.TestPersistentActionPlugin.Status;
import org.elasticsearch.xpack.persistent.TestPersistentActionPlugin.TestPersistentAction;
import org.elasticsearch.xpack.persistent.TestPersistentActionPlugin.TestRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.elasticsearch.cluster.metadata.MetaData.CONTEXT_MODE_GATEWAY;
import static org.elasticsearch.cluster.metadata.MetaData.CONTEXT_MODE_SNAPSHOT;

public class PersistentTasksInProgressTests extends AbstractDiffableSerializationTestCase<Custom> {

    @Override
    protected PersistentTasksInProgress createTestInstance() {
        int numberOfTasks = randomInt(10);
        PersistentTasksInProgress.Builder tasks = PersistentTasksInProgress.builder();
        for (int i = 0; i < numberOfTasks; i++) {
            tasks.addTask(TestPersistentAction.NAME, new TestRequest(randomAsciiOfLength(10)),
                    randomBoolean(), randomBoolean(), randomAsciiOfLength(10));
            if (randomBoolean()) {
                // From time to time update status
                tasks.updateTaskStatus(tasks.getCurrentId(), new Status(randomAsciiOfLength(10)));
            }
        }
        return tasks.build();
    }

    @Override
    protected Writeable.Reader<Custom> instanceReader() {
        return PersistentTasksInProgress::new;
    }

    @Override
    protected NamedWriteableRegistry getNamedWriteableRegistry() {
        return new NamedWriteableRegistry(Arrays.asList(
                new Entry(MetaData.Custom.class, PersistentTasksInProgress.TYPE, PersistentTasksInProgress::new),
                new Entry(NamedDiff.class, PersistentTasksInProgress.TYPE, PersistentTasksInProgress::readDiffFrom),
                new Entry(PersistentActionRequest.class, TestPersistentAction.NAME, TestRequest::new),
                new Entry(Task.Status.class, Status.NAME, Status::new)
        ));
    }

    @Override
    protected Custom makeTestChanges(Custom testInstance) {
        PersistentTasksInProgress tasksInProgress = (PersistentTasksInProgress) testInstance;
        Builder builder = new Builder();
        switch (randomInt(3)) {
            case 0:
                addRandomTask(builder);
                break;
            case 1:
                if (tasksInProgress.tasks().isEmpty()) {
                    addRandomTask(builder);
                } else {
                    builder.reassignTask(pickRandomTask(tasksInProgress), randomAsciiOfLength(10));
                }
                break;
            case 2:
                if (tasksInProgress.tasks().isEmpty()) {
                    addRandomTask(builder);
                } else {
                    builder.updateTaskStatus(pickRandomTask(tasksInProgress), randomBoolean() ? new Status(randomAsciiOfLength(10)) : null);
                }
                break;
            case 3:
                if (tasksInProgress.tasks().isEmpty()) {
                    addRandomTask(builder);
                } else {
                    builder.removeTask(pickRandomTask(tasksInProgress));
                }
                break;
        }
        return builder.build();
    }

    @Override
    protected Writeable.Reader<Diff<Custom>> diffReader() {
        return PersistentTasksInProgress::readDiffFrom;
    }

    @Override
    protected PersistentTasksInProgress doParseInstance(XContentParser parser) throws IOException {
        return PersistentTasksInProgress.fromXContent(parser);
    }

    @Override
    protected XContentBuilder toXContent(Custom instance, XContentType contentType) throws IOException {
        return toXContent(instance, contentType, new ToXContent.MapParams(
                Collections.singletonMap(MetaData.CONTEXT_MODE_PARAM, MetaData.XContentContext.API.toString())));
    }

    protected XContentBuilder toXContent(Custom instance, XContentType contentType, ToXContent.MapParams params) throws IOException {
        // We need all attribute to be serialized/de-serialized for testing
        XContentBuilder builder = XContentFactory.contentBuilder(contentType);
        if (randomBoolean()) {
            builder.prettyPrint();
        }
        if (instance.isFragment()) {
            builder.startObject();
        }
        instance.toXContent(builder, params);
        if (instance.isFragment()) {
            builder.endObject();
        }
        return builder;
    }

    private Builder addRandomTask(Builder builder) {
        builder.addTask(TestPersistentAction.NAME, new TestRequest(randomAsciiOfLength(10)),
                randomBoolean(), randomBoolean(), randomAsciiOfLength(10));
        return builder;
    }

    private long pickRandomTask(PersistentTasksInProgress testInstance) {
        return randomFrom(new ArrayList<>(testInstance.tasks())).getId();
    }

    @Override
    protected NamedXContentRegistry xContentRegistry() {
        return new NamedXContentRegistry(Arrays.asList(
                new NamedXContentRegistry.Entry(PersistentActionRequest.class, new ParseField(TestPersistentAction.NAME),
                        TestRequest::fromXContent),
                new NamedXContentRegistry.Entry(Task.Status.class, new ParseField(TestPersistentAction.NAME),
                        Status::fromXContent)
        ));
    }

    @SuppressWarnings("unchecked")
    public void testSerializationContext() throws Exception {
        PersistentTasksInProgress testInstance = createTestInstance();
        for (int i = 0; i < randomInt(10); i++) {
            testInstance = (PersistentTasksInProgress) makeTestChanges(testInstance);
        }

        ToXContent.MapParams params = new ToXContent.MapParams(
                Collections.singletonMap(MetaData.CONTEXT_MODE_PARAM, randomFrom(CONTEXT_MODE_SNAPSHOT, CONTEXT_MODE_GATEWAY)));

        XContentType xContentType = randomFrom(XContentType.values());
        XContentBuilder builder = toXContent(testInstance, xContentType, params);
        XContentBuilder shuffled = shuffleXContent(builder);

        XContentParser parser = createParser(XContentFactory.xContent(xContentType), shuffled.bytes());
        PersistentTasksInProgress newInstance = doParseInstance(parser);
        assertNotSame(newInstance, testInstance);

        assertEquals(testInstance.tasks().size(), newInstance.tasks().size());
        for (PersistentTaskInProgress<?> testTask : testInstance.tasks()) {
            PersistentTaskInProgress<TestRequest> newTask = (PersistentTaskInProgress<TestRequest>) newInstance.getTask(testTask.getId());
            assertNotNull(newTask);

            // Things that should be serialized
            assertEquals(testTask.getAction(), newTask.getAction());
            assertEquals(testTask.getId(), newTask.getId());
            assertEquals(testTask.getStatus(), newTask.getStatus());
            assertEquals(testTask.getRequest(), newTask.getRequest());
            assertEquals(testTask.isStopped(), newTask.isStopped());

            // Things that shouldn't be serialized
            assertEquals(0, newTask.getAllocationId());
            assertNull(newTask.getExecutorNode());
        }
    }

    public void testBuilder() {
        PersistentTasksInProgress persistentTasksInProgress = null;
        long lastKnownTask = -1;
        for (int i = 0; i < randomIntBetween(10, 100); i++) {
            final Builder builder;
            if (randomBoolean()) {
                builder = new Builder();
            } else {
                builder = new Builder(persistentTasksInProgress);
            }
            boolean changed = false;
            for (int j = 0; j < randomIntBetween(1, 10); j++) {
                switch (randomInt(5)) {
                    case 0:
                        lastKnownTask = addRandomTask(builder).getCurrentId();
                        changed = true;
                        break;
                    case 1:
                        if (builder.hasTask(lastKnownTask)) {
                            changed = true;
                        }
                        if (randomBoolean()) {
                            builder.reassignTask(lastKnownTask, randomAsciiOfLength(10));
                        } else {
                            builder.reassignTask(lastKnownTask, (s, request) -> randomAsciiOfLength(10));
                        }
                        break;
                    case 2:
                        if (builder.hasTask(lastKnownTask)) {
                            PersistentTaskInProgress<?> task = builder.build().getTask(lastKnownTask);
                            if (randomBoolean()) {
                                // Trying to reassign to the same node
                                builder.assignTask(lastKnownTask, (s, request) -> task.getExecutorNode());
                                // should change if the task was stopped AND unassigned
                                if (task.getExecutorNode() == null && task.isStopped()) {
                                    changed = true;
                                }
                            } else {
                                // Trying to reassign to a different node
                                builder.assignTask(lastKnownTask, (s, request) -> randomAsciiOfLength(10));
                                // should change if the task was unassigned
                                if (task.getExecutorNode() == null) {
                                    changed = true;
                                }
                            }
                        } else {
                            // task doesn't exist - shouldn't change
                            builder.assignTask(lastKnownTask, (s, request) -> randomAsciiOfLength(10));
                        }
                        break;
                    case 3:
                        if (builder.hasTask(lastKnownTask)) {
                            changed = true;
                        }
                        builder.updateTaskStatus(lastKnownTask, randomBoolean() ? new Status(randomAsciiOfLength(10)) : null);
                        break;
                    case 4:
                        if (builder.hasTask(lastKnownTask)) {
                            changed = true;
                        }
                        builder.removeTask(lastKnownTask);
                        break;
                    case 5:
                        if (builder.hasTask(lastKnownTask)) {
                            changed = true;
                        }
                        builder.finishTask(lastKnownTask);
                        break;
                }
            }
            assertEquals(changed, builder.isChanged());
            persistentTasksInProgress = builder.build();
        }

    }

}