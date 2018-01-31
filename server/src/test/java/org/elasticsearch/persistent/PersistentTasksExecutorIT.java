/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.persistent;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.BaseFuture;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.tasks.TaskInfo;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.persistent.PersistentTasksService.PersistentTaskOperationListener;
import org.elasticsearch.persistent.TestPersistentTasksPlugin.TestPersistentTasksExecutor;
import org.elasticsearch.persistent.TestPersistentTasksPlugin.TestRequest;
import org.elasticsearch.persistent.TestPersistentTasksPlugin.TestTasksRequestBuilder;
import org.junit.After;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.SUITE, minNumDataNodes = 2)
public class PersistentTasksExecutorIT extends ESIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Collections.singletonList(TestPersistentTasksPlugin.class);
    }

    @Override
    protected Collection<Class<? extends Plugin>> transportClientPlugins() {
        return nodePlugins();
    }

    protected boolean ignoreExternalCluster() {
        return true;
    }

    @After
    public void cleanup() throws Exception {
        assertNoRunningTasks();
    }

    public static class PersistentTaskOperationFuture extends BaseFuture<Long> implements PersistentTaskOperationListener {

        @Override
        public void onResponse(long taskId) {
            set(taskId);
        }

        @Override
        public void onFailure(Exception e) {
            setException(e);
        }
    }

    public void testPersistentActionRestart() throws Exception {
        PersistentTasksService persistentTasksService = internalCluster().getInstance(PersistentTasksService.class);
        PersistentTaskOperationFuture future = new PersistentTaskOperationFuture();
        persistentTasksService.createPersistentActionTask(TestPersistentTasksExecutor.NAME, new TestRequest("Blah"), future);
        long taskId = future.get();
        assertBusy(() -> {
            // Wait for the task to start
            assertThat(client().admin().cluster().prepareListTasks().setActions(TestPersistentTasksExecutor.NAME + "[c]").get()
                            .getTasks().size(), equalTo(1));
        });
        TaskInfo firstRunningTask = client().admin().cluster().prepareListTasks().setActions(TestPersistentTasksExecutor.NAME + "[c]")
                .get().getTasks().get(0);
        logger.info("Found running task with id {} and parent {}", firstRunningTask.getId(), firstRunningTask.getParentTaskId());
        // Verifying parent
        assertThat(firstRunningTask.getParentTaskId().getId(), equalTo(taskId));
        assertThat(firstRunningTask.getParentTaskId().getNodeId(), equalTo("cluster"));

        logger.info("Failing the running task");
        // Fail the running task and make sure it restarts properly
        assertThat(new TestTasksRequestBuilder(client()).setOperation("fail").setTaskId(firstRunningTask.getTaskId())
                .get().getTasks().size(), equalTo(1));

        assertBusy(() -> {
            // Wait for the task to restart
            List<TaskInfo> tasks = client().admin().cluster().prepareListTasks().setActions(TestPersistentTasksExecutor.NAME + "[c]").get()
                    .getTasks();
            logger.info("Found {} tasks", tasks.size());
            assertThat(tasks.size(), equalTo(1));
            // Make sure that restarted task is different
            assertThat(tasks.get(0).getTaskId(), not(equalTo(firstRunningTask.getTaskId())));
        });

        logger.info("Removing persistent task with id {}", firstRunningTask.getId());
        // Remove the persistent task
        PersistentTaskOperationFuture removeFuture = new PersistentTaskOperationFuture();
        persistentTasksService.removeTask(taskId, removeFuture);
        assertEquals(removeFuture.get(), (Long) taskId);

        logger.info("Waiting for persistent task with id {} to disappear", firstRunningTask.getId());
        assertBusy(() -> {
            // Wait for the task to disappear completely
            assertThat(client().admin().cluster().prepareListTasks().setActions(TestPersistentTasksExecutor.NAME + "[c]").get().getTasks(),
                    empty());
        });
    }

    public void testPersistentActionCompletion() throws Exception {
        PersistentTasksService persistentTasksService = internalCluster().getInstance(PersistentTasksService.class);
        PersistentTaskOperationFuture future = new PersistentTaskOperationFuture();
        persistentTasksService.createPersistentActionTask(TestPersistentTasksExecutor.NAME, new TestRequest("Blah"), future);
        long taskId = future.get();
        assertBusy(() -> {
            // Wait for the task to start
            assertThat(client().admin().cluster().prepareListTasks().setActions(TestPersistentTasksExecutor.NAME + "[c]").get()
                            .getTasks().size(), equalTo(1));
        });
        TaskInfo firstRunningTask = client().admin().cluster().prepareListTasks().setActions(TestPersistentTasksExecutor.NAME + "[c]")
                .get().getTasks().get(0);
        logger.info("Found running task with id {} and parent {}", firstRunningTask.getId(), firstRunningTask.getParentTaskId());
        // Verifying parent
        assertThat(firstRunningTask.getParentTaskId().getId(), equalTo(taskId));
        assertThat(firstRunningTask.getParentTaskId().getNodeId(), equalTo("cluster"));
        stopOrCancelTask(firstRunningTask.getTaskId());
    }

    public void testPersistentActionCompletionWithoutRemoval() throws Exception {
        boolean stopped = randomBoolean();
        PersistentTasksService persistentTasksService = internalCluster().getInstance(PersistentTasksService.class);
        PersistentTaskOperationFuture future = new PersistentTaskOperationFuture();
        persistentTasksService.createPersistentActionTask(TestPersistentTasksExecutor.NAME, new TestRequest("Blah"), stopped, false,
                future);
        long taskId = future.get();

        PersistentTasksCustomMetaData tasksInProgress = internalCluster().clusterService().state().getMetaData()
                .custom(PersistentTasksCustomMetaData.TYPE);
        assertThat(tasksInProgress.tasks().size(), equalTo(1));
        assertThat(tasksInProgress.getTask(taskId).isStopped(), equalTo(stopped));
        assertThat(tasksInProgress.getTask(taskId).getExecutorNode(), stopped ? nullValue() : notNullValue());
        assertThat(tasksInProgress.getTask(taskId).shouldRemoveOnCompletion(), equalTo(false));

        int numberOfIters = randomIntBetween(1, 5); // we will start/stop the action a few times before removing it
        logger.info("start/stop the task {} times stating with stopped {}", numberOfIters, stopped);
        for (int i = 0; i < numberOfIters; i++) {
            logger.info("iteration {}", i);
            if (stopped) {
                assertThat(client().admin().cluster().prepareListTasks().setActions(TestPersistentTasksExecutor.NAME + "[c]").get()
                                .getTasks(), empty());
                PersistentTaskOperationFuture startFuture = new PersistentTaskOperationFuture();
                persistentTasksService.startTask(taskId, startFuture);
                assertEquals(startFuture.get(), (Long) taskId);
            }
            assertBusy(() -> {
                // Wait for the task to start
                assertThat(client().admin().cluster().prepareListTasks().setActions(TestPersistentTasksExecutor.NAME + "[c]").get()
                        .getTasks().size(), equalTo(1));
            });
            TaskInfo firstRunningTask = client().admin().cluster().prepareListTasks().setActions(TestPersistentTasksExecutor.NAME + "[c]")
                    .get().getTasks().get(0);

            stopOrCancelTask(firstRunningTask.getTaskId());

            assertBusy(() -> {
                // Wait for the task to finish
                List<TaskInfo> tasks = client().admin().cluster().prepareListTasks().setActions(TestPersistentTasksExecutor.NAME + "[c]")
                        .get().getTasks();
                logger.info("Found {} tasks", tasks.size());
                assertThat(tasks.size(), equalTo(0));
            });
            stopped = true;
        }

        assertBusy(() -> {
            // Wait for the task to be marked as stopped
            PersistentTasksCustomMetaData tasks = internalCluster().clusterService().state().getMetaData()
                    .custom(PersistentTasksCustomMetaData.TYPE);
            assertThat(tasks.tasks().size(), equalTo(1));
            assertThat(tasks.getTask(taskId).isStopped(), equalTo(true));
            assertThat(tasks.getTask(taskId).shouldRemoveOnCompletion(), equalTo(false));
        });

        logger.info("Removing action record from cluster state");
        PersistentTaskOperationFuture removeFuture = new PersistentTaskOperationFuture();
        persistentTasksService.removeTask(taskId, removeFuture);
        assertEquals(removeFuture.get(), (Long) taskId);
    }

    public void testPersistentActionWithNoAvailableNode() throws Exception {
        PersistentTasksService persistentTasksService = internalCluster().getInstance(PersistentTasksService.class);
        PersistentTaskOperationFuture future = new PersistentTaskOperationFuture();
        TestRequest testRequest = new TestRequest("Blah");
        testRequest.setExecutorNodeAttr("test");
        persistentTasksService.createPersistentActionTask(TestPersistentTasksExecutor.NAME, testRequest, future);
        long taskId = future.get();

        Settings nodeSettings = Settings.builder().put(nodeSettings(0)).put("node.attr.test_attr", "test").build();
        String newNode = internalCluster().startNode(nodeSettings);
        String newNodeId = internalCluster().clusterService(newNode).localNode().getId();
        assertBusy(() -> {
            // Wait for the task to start
            assertThat(client().admin().cluster().prepareListTasks().setActions(TestPersistentTasksExecutor.NAME + "[c]").get().getTasks()
                            .size(), equalTo(1));
        });
        TaskInfo taskInfo = client().admin().cluster().prepareListTasks().setActions(TestPersistentTasksExecutor.NAME + "[c]")
                .get().getTasks().get(0);

        // Verifying the the task runs on the new node
        assertThat(taskInfo.getTaskId().getNodeId(), equalTo(newNodeId));

        internalCluster().stopRandomNode(settings -> "test".equals(settings.get("node.attr.test_attr")));

        assertBusy(() -> {
            // Wait for the task to disappear completely
            assertThat(client().admin().cluster().prepareListTasks().setActions(TestPersistentTasksExecutor.NAME + "[c]").get().getTasks(),
                    empty());
        });

        // Remove the persistent task
        PersistentTaskOperationFuture removeFuture = new PersistentTaskOperationFuture();
        persistentTasksService.removeTask(taskId, removeFuture);
        assertEquals(removeFuture.get(), (Long) taskId);
    }

    public void testPersistentActionStatusUpdate() throws Exception {
        PersistentTasksService persistentTasksService = internalCluster().getInstance(PersistentTasksService.class);
        PersistentTaskOperationFuture future = new PersistentTaskOperationFuture();
        persistentTasksService.createPersistentActionTask(TestPersistentTasksExecutor.NAME, new TestRequest("Blah"), future);
        future.get();

        assertBusy(() -> {
            // Wait for the task to start
            assertThat(client().admin().cluster().prepareListTasks().setActions(TestPersistentTasksExecutor.NAME + "[c]").get().getTasks()
                            .size(), equalTo(1));
        });
        TaskInfo firstRunningTask = client().admin().cluster().prepareListTasks().setActions(TestPersistentTasksExecutor.NAME + "[c]")
                .get().getTasks().get(0);

        PersistentTasksCustomMetaData tasksInProgress = internalCluster().clusterService().state().getMetaData()
                .custom(PersistentTasksCustomMetaData.TYPE);
        assertThat(tasksInProgress.tasks().size(), equalTo(1));
        assertThat(tasksInProgress.tasks().iterator().next().getStatus(), nullValue());

        int numberOfUpdates = randomIntBetween(1, 10);
        for (int i = 0; i < numberOfUpdates; i++) {
            logger.info("Updating the task status");
            // Complete the running task and make sure it finishes properly
            assertThat(new TestTasksRequestBuilder(client()).setOperation("update_status").setTaskId(firstRunningTask.getTaskId())
                    .get().getTasks().size(), equalTo(1));

            int finalI = i;
            assertBusy(() -> {
                PersistentTasksCustomMetaData tasks = internalCluster().clusterService().state().getMetaData()
                        .custom(PersistentTasksCustomMetaData.TYPE);
                assertThat(tasks.tasks().size(), equalTo(1));
                assertThat(tasks.tasks().iterator().next().getStatus(), notNullValue());
                assertThat(tasks.tasks().iterator().next().getStatus().toString(), equalTo("{\"phase\":\"phase " + (finalI + 1) + "\"}"));
            });

        }

        logger.info("Completing the running task");
        // Complete the running task and make sure it finishes properly
        assertThat(new TestTasksRequestBuilder(client()).setOperation("finish").setTaskId(firstRunningTask.getTaskId())
                .get().getTasks().size(), equalTo(1));
    }


    private void stopOrCancelTask(TaskId taskId) {
        if (randomBoolean()) {
            logger.info("Completing the running task");
            // Complete the running task and make sure it finishes properly
            assertThat(new TestTasksRequestBuilder(client()).setOperation("finish").setTaskId(taskId)
                    .get().getTasks().size(), equalTo(1));

        } else {
            logger.info("Cancelling the running task");
            // Cancel the running task and make sure it finishes properly
            assertThat(client().admin().cluster().prepareCancelTasks().setTaskId(taskId)
                    .get().getTasks().size(), equalTo(1));
        }


    }

    private void assertNoRunningTasks() throws Exception {
        assertBusy(() -> {
            // Wait for the task to finish
            List<TaskInfo> tasks = client().admin().cluster().prepareListTasks().setActions(TestPersistentTasksExecutor.NAME + "[c]").get()
                    .getTasks();
            logger.info("Found {} tasks", tasks.size());
            assertThat(tasks.size(), equalTo(0));

            // Make sure the task is removed from the cluster state
            assertThat(((PersistentTasksCustomMetaData) internalCluster().clusterService().state().getMetaData()
                    .custom(PersistentTasksCustomMetaData.TYPE)).tasks(), empty());
        });
    }

}
