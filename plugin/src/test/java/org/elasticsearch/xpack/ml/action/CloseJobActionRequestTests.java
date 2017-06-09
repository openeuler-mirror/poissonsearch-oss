/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.action;

import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.ml.MlMetadata;
import org.elasticsearch.xpack.ml.action.CloseJobAction.Request;
import org.elasticsearch.xpack.ml.datafeed.DatafeedState;
import org.elasticsearch.xpack.ml.job.config.JobState;
import org.elasticsearch.xpack.ml.notifications.Auditor;
import org.elasticsearch.xpack.ml.support.AbstractStreamableXContentTestCase;
import org.elasticsearch.xpack.ml.support.BaseMlIntegTestCase;
import org.elasticsearch.xpack.persistent.PersistentTasksCustomMetaData;
import org.elasticsearch.xpack.persistent.PersistentTasksCustomMetaData.Assignment;
import org.elasticsearch.xpack.persistent.PersistentTasksService;
import org.elasticsearch.xpack.security.InternalClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.elasticsearch.xpack.ml.action.OpenJobActionTests.addJobTask;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CloseJobActionRequestTests extends AbstractStreamableXContentTestCase<Request> {

    @Override
    protected Request createTestInstance() {
        Request request = new Request(randomAlphaOfLengthBetween(1, 20));
        if (randomBoolean()) {
            request.setCloseTimeout(TimeValue.timeValueMillis(randomNonNegativeLong()));
        }
        if (randomBoolean()) {
            request.setForce(randomBoolean());
        }
        return request;
    }

    @Override
    protected Request createBlankInstance() {
        return new Request();
    }

    @Override
    protected Request parseInstance(XContentParser parser) {
        return Request.parseRequest(null, parser);
    }

    public void testValidate_datafeedIsStarted() {
        MlMetadata.Builder mlBuilder = new MlMetadata.Builder();
        mlBuilder.putJob(BaseMlIntegTestCase.createScheduledJob("job_id").build(new Date()), false);
        mlBuilder.putDatafeed(BaseMlIntegTestCase.createDatafeed("datafeed_id", "job_id",
                Collections.singletonList("*")));
        final PersistentTasksCustomMetaData.Builder startDataFeedTaskBuilder =  PersistentTasksCustomMetaData.builder();
        addJobTask("job_id", null, JobState.OPENED, startDataFeedTaskBuilder);
        addTask("datafeed_id", 0L, null, DatafeedState.STARTED, startDataFeedTaskBuilder);

        ElasticsearchStatusException e =
                expectThrows(ElasticsearchStatusException.class,
                        () -> CloseJobAction.validateJobAndTaskState("job_id", mlBuilder.build(), startDataFeedTaskBuilder.build()));
        assertEquals(RestStatus.CONFLICT, e.status());
        assertEquals("cannot close job [job_id], datafeed hasn't been stopped", e.getMessage());

        final PersistentTasksCustomMetaData.Builder dataFeedNotStartedTaskBuilder =  PersistentTasksCustomMetaData.builder();
        addJobTask("job_id", null, JobState.OPENED, dataFeedNotStartedTaskBuilder);
        if (randomBoolean()) {
            addTask("datafeed_id", 0L, null, DatafeedState.STOPPED, dataFeedNotStartedTaskBuilder);
        }

        CloseJobAction.validateJobAndTaskState("job_id", mlBuilder.build(), dataFeedNotStartedTaskBuilder.build());
    }

    public void testValidate_jobIsOpening() {
        MlMetadata.Builder mlBuilder = new MlMetadata.Builder();
        mlBuilder.putJob(BaseMlIntegTestCase.createFareQuoteJob("opening-job").build(new Date()), false);

        // An opening job has a null status field
        PersistentTasksCustomMetaData.Builder tasksBuilder = PersistentTasksCustomMetaData.builder();
        addJobTask("opening-job", null, null, tasksBuilder);

        ElasticsearchStatusException conflictException =
                expectThrows(ElasticsearchStatusException.class, () ->
                        CloseJobAction.validateJobAndTaskState("opening-job", mlBuilder.build(), tasksBuilder.build()));
        assertEquals(RestStatus.CONFLICT, conflictException.status());
    }

    public void testValidate_jobIsMissing() {
        MlMetadata.Builder mlBuilder = new MlMetadata.Builder();

        PersistentTasksCustomMetaData.Builder tasksBuilder = PersistentTasksCustomMetaData.builder();
        addJobTask("missing-job", null, null, tasksBuilder);

        expectThrows(ResourceNotFoundException.class, () ->
                CloseJobAction.validateJobAndTaskState("missing-job", mlBuilder.build(), tasksBuilder.build()));
    }

    public void testResolve_givenAll() {
        MlMetadata.Builder mlBuilder = new MlMetadata.Builder();
        mlBuilder.putJob(BaseMlIntegTestCase.createScheduledJob("job_id_1").build(new Date()), false);
        mlBuilder.putJob(BaseMlIntegTestCase.createScheduledJob("job_id_2").build(new Date()), false);
        mlBuilder.putJob(BaseMlIntegTestCase.createScheduledJob("job_id_3").build(new Date()), false);
        mlBuilder.putJob(BaseMlIntegTestCase.createScheduledJob("job_id_4").build(new Date()), false);
        mlBuilder.putJob(BaseMlIntegTestCase.createScheduledJob("job_id_5").build(new Date()), false);

        PersistentTasksCustomMetaData.Builder tasksBuilder =  PersistentTasksCustomMetaData.builder();
        addJobTask("job_id_1", null, JobState.OPENED, tasksBuilder);
        addJobTask("job_id_2", null, JobState.OPENED, tasksBuilder);
        addJobTask("job_id_3", null, JobState.FAILED, tasksBuilder);
        addJobTask("job_id_4", null, JobState.CLOSING, tasksBuilder);

        ClusterState cs1 = ClusterState.builder(new ClusterName("_name"))
                .metaData(new MetaData.Builder().putCustom(MlMetadata.TYPE, mlBuilder.build())
                        .putCustom(PersistentTasksCustomMetaData.TYPE,  tasksBuilder.build()))
                .build();

        List<String> openJobs = new ArrayList<>();
        List<String> closingJobs = new ArrayList<>();

        CloseJobAction.resolveAndValidateJobId("_all", cs1, openJobs, closingJobs, true);
        assertEquals(Arrays.asList("job_id_1", "job_id_2", "job_id_3"), openJobs);
        assertEquals(Arrays.asList("job_id_4"), closingJobs);

        expectThrows(ElasticsearchStatusException.class,
                () -> CloseJobAction.resolveAndValidateJobId("_all", cs1, openJobs, closingJobs, false));
    }

    public void testResolve_givenJobId() {
        MlMetadata.Builder mlBuilder = new MlMetadata.Builder();
        mlBuilder.putJob(BaseMlIntegTestCase.createFareQuoteJob("job_id_1").build(new Date()), false);

        PersistentTasksCustomMetaData.Builder tasksBuilder =  PersistentTasksCustomMetaData.builder();
        addJobTask("job_id_1", null, JobState.OPENED, tasksBuilder);

        ClusterState cs1 = ClusterState.builder(new ClusterName("_name"))
                .metaData(new MetaData.Builder().putCustom(MlMetadata.TYPE, mlBuilder.build())
                        .putCustom(PersistentTasksCustomMetaData.TYPE,  tasksBuilder.build()))
                .build();

        List<String> openJobs = new ArrayList<>();
        List<String> closingJobs = new ArrayList<>();

        CloseJobAction.resolveAndValidateJobId("job_id_1", cs1, openJobs, closingJobs, false);
        assertEquals(Arrays.asList("job_id_1"), openJobs);
        assertEquals(Collections.emptyList(), closingJobs);

        // Job without task is closed
        cs1 = ClusterState.builder(new ClusterName("_name"))
                .metaData(new MetaData.Builder().putCustom(MlMetadata.TYPE, mlBuilder.build()))
                .build();

        openJobs.clear();
        closingJobs.clear();
        CloseJobAction.resolveAndValidateJobId("job_id_1", cs1, openJobs, closingJobs, false);
        assertEquals(Collections.emptyList(), openJobs);
        assertEquals(Collections.emptyList(), closingJobs);
    }

    public void testResolve_throwsWithUnknownJobId() {
        MlMetadata.Builder mlBuilder = new MlMetadata.Builder();
        mlBuilder.putJob(BaseMlIntegTestCase.createFareQuoteJob("job_id_1").build(new Date()), false);

        ClusterState cs1 = ClusterState.builder(new ClusterName("_name"))
                .metaData(new MetaData.Builder().putCustom(MlMetadata.TYPE, mlBuilder.build()))
                .build();

        List<String> openJobs = new ArrayList<>();
        List<String> closingJobs = new ArrayList<>();

        expectThrows(ResourceNotFoundException.class,
                () -> CloseJobAction.resolveAndValidateJobId("missing-job", cs1, openJobs, closingJobs, false));
    }

    public void testResolve_givenJobIdFailed() {
        MlMetadata.Builder mlBuilder = new MlMetadata.Builder();
        mlBuilder.putJob(BaseMlIntegTestCase.createFareQuoteJob("job_id_failed").build(new Date()), false);

        PersistentTasksCustomMetaData.Builder tasksBuilder = PersistentTasksCustomMetaData.builder();
        addJobTask("job_id_failed", null, JobState.FAILED, tasksBuilder);

        ClusterState cs1 = ClusterState.builder(new ClusterName("_name")).metaData(new MetaData.Builder()
                .putCustom(MlMetadata.TYPE, mlBuilder.build()).putCustom(PersistentTasksCustomMetaData.TYPE, tasksBuilder.build())).build();

        List<String> openJobs = new ArrayList<>();
        List<String> closingJobs = new ArrayList<>();

        CloseJobAction.resolveAndValidateJobId("job_id_failed", cs1, openJobs, closingJobs, true);
        assertEquals(Arrays.asList("job_id_failed"), openJobs);
        assertEquals(Collections.emptyList(), closingJobs);

        openJobs.clear();
        closingJobs.clear();

        expectThrows(ElasticsearchStatusException.class,
                () -> CloseJobAction.resolveAndValidateJobId("job_id_failed", cs1, openJobs, closingJobs, false));
    }

    public void testResolve_withSpecificJobIds() {
        MlMetadata.Builder mlBuilder = new MlMetadata.Builder();
        mlBuilder.putJob(BaseMlIntegTestCase.createFareQuoteJob("job_id_closing").build(new Date()), false);
        mlBuilder.putJob(BaseMlIntegTestCase.createFareQuoteJob("job_id_open").build(new Date()), false);
        mlBuilder.putJob(BaseMlIntegTestCase.createFareQuoteJob("job_id_closed").build(new Date()), false);

        PersistentTasksCustomMetaData.Builder tasksBuilder =  PersistentTasksCustomMetaData.builder();
        addJobTask("job_id_closing", null, JobState.CLOSING, tasksBuilder);
        addJobTask("job_id_open", null, JobState.OPENED, tasksBuilder);
        // closed job has no task

        ClusterState cs1 = ClusterState.builder(new ClusterName("_name"))
                .metaData(new MetaData.Builder().putCustom(MlMetadata.TYPE, mlBuilder.build())
                        .putCustom(PersistentTasksCustomMetaData.TYPE,  tasksBuilder.build()))
                .build();

        List<String> openJobs = new ArrayList<>();
        List<String> closingJobs = new ArrayList<>();

        CloseJobAction.resolveAndValidateJobId("_all", cs1, openJobs, closingJobs, false);
        assertEquals(Arrays.asList("job_id_open"), openJobs);
        assertEquals(Arrays.asList("job_id_closing"), closingJobs);
        openJobs.clear();
        closingJobs.clear();

        CloseJobAction.resolveAndValidateJobId("job_id_closing", cs1, openJobs, closingJobs, false);
        assertEquals(Collections.emptyList(), openJobs);
        assertEquals(Arrays.asList("job_id_closing"), closingJobs);
        openJobs.clear();
        closingJobs.clear();

        CloseJobAction.resolveAndValidateJobId("job_id_open", cs1, openJobs, closingJobs, false);
        assertEquals(Arrays.asList("job_id_open"), openJobs);
        assertEquals(Collections.emptyList(), closingJobs);
        openJobs.clear();
        closingJobs.clear();
    }

    public void testDoExecute_whenNothingToClose() {
        MlMetadata.Builder mlBuilder = new MlMetadata.Builder();
        mlBuilder.putJob(BaseMlIntegTestCase.createFareQuoteJob("foo").build(new Date()), false);

        PersistentTasksCustomMetaData.Builder tasksBuilder =  PersistentTasksCustomMetaData.builder();
        addJobTask("foo", null, JobState.CLOSED, tasksBuilder);

        ClusterState clusterState = ClusterState.builder(new ClusterName("_name"))
                .metaData(new MetaData.Builder().putCustom(MlMetadata.TYPE, mlBuilder.build())
                        .putCustom(PersistentTasksCustomMetaData.TYPE,  tasksBuilder.build()))
                .build();

        ClusterService clusterService = mock(ClusterService.class);
        when(clusterService.state()).thenReturn(clusterState);

        CloseJobAction.TransportAction transportAction = new CloseJobAction.TransportAction(Settings.EMPTY,
                mock(TransportService.class), mock(ThreadPool.class), mock(ActionFilters.class), mock(IndexNameExpressionResolver.class),
                clusterService, mock(InternalClient.class), mock(Auditor.class), mock(PersistentTasksService.class));

        AtomicBoolean gotResponse = new AtomicBoolean(false);
        CloseJobAction.Request request = new Request("foo");
        request.setLocal(true); // hack but it saves a lot of mocking
        // This method should return immediately because the job is already closed.
        // Check that the listener is called. If a different code path was taken the
        // listener wouldn't be called without extensive mocking
        transportAction.doExecute(mock(Task.class), request, new ActionListener<CloseJobAction.Response>() {
            @Override
            public void onResponse(CloseJobAction.Response response) {
                gotResponse.set(response.isClosed());
            }

            @Override
            public void onFailure(Exception e) {
                fail();
            }
        });

        assertTrue(gotResponse.get());
    }

    public void testBuildWaitForCloseRequest() {
        List<String> openJobIds = Arrays.asList(new String[] {"openjob1", "openjob2"});
        List<String> closingJobIds = Arrays.asList(new String[] {"closingjob1"});

        PersistentTasksCustomMetaData.Builder tasksBuilder =  PersistentTasksCustomMetaData.builder();
        addJobTask("openjob1", null, JobState.OPENED, tasksBuilder);
        addJobTask("openjob2", null, JobState.OPENED, tasksBuilder);
        addJobTask("closingjob1", null, JobState.CLOSING, tasksBuilder);

        CloseJobAction.TransportAction.WaitForCloseRequest waitForCloseRequest =
                CloseJobAction.buildWaitForCloseRequest(openJobIds, closingJobIds, tasksBuilder.build(), mock(Auditor.class));
        assertEquals(waitForCloseRequest.jobsToFinalize, Arrays.asList("openjob1", "openjob2"));
        assertEquals(waitForCloseRequest.persistentTaskIds,
                Arrays.asList("job-openjob1", "job-openjob2", "job-closingjob1"));
        assertTrue(waitForCloseRequest.hasJobsToWaitFor());

        waitForCloseRequest = CloseJobAction.buildWaitForCloseRequest(Collections.emptyList(), Collections.emptyList(),
                tasksBuilder.build(), mock(Auditor.class));
        assertFalse(waitForCloseRequest.hasJobsToWaitFor());
    }

    public static void addTask(String datafeedId, long startTime, String nodeId, DatafeedState state,
                               PersistentTasksCustomMetaData.Builder tasks) {
        tasks.addTask(MlMetadata.datafeedTaskId(datafeedId), StartDatafeedAction.TASK_NAME,
                new StartDatafeedAction.DatafeedParams(datafeedId, startTime), new Assignment(nodeId, "test assignment"));
        tasks.updateTaskStatus(MlMetadata.datafeedTaskId(datafeedId), state);
    }

}