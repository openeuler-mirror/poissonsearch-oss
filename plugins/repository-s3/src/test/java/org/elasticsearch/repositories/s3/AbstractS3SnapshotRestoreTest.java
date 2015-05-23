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

package org.elasticsearch.repositories.s3;

import com.amazonaws.Protocol;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.DeleteObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
<<<<<<< HEAD:plugins/repository-s3/src/test/java/org/elasticsearch/repositories/s3/AbstractS3SnapshotRestoreTest.java
=======

import com.amazonaws.util.Base64;
import com.carrotsearch.ant.tasks.junit4.dependencies.com.google.gson.stream.JsonReader;
import com.carrotsearch.ant.tasks.junit4.dependencies.com.google.gson.stream.MalformedJsonException;
>>>>>>> 98d508f... Add client-side encryption:src/test/java/org/elasticsearch/repositories/s3/AbstractS3SnapshotRestoreTest.java
import org.elasticsearch.action.admin.cluster.repositories.put.PutRepositoryResponse;
import org.elasticsearch.action.admin.cluster.snapshots.create.CreateSnapshotResponse;
import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.ClusterAdminClient;
import org.elasticsearch.cloud.aws.AbstractAwsTestCase;
import org.elasticsearch.cloud.aws.AwsS3Service;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.common.settings.Settings;
<<<<<<< HEAD:plugins/repository-s3/src/test/java/org/elasticsearch/repositories/s3/AbstractS3SnapshotRestoreTest.java
=======
import org.elasticsearch.plugins.PluginsService;
import org.elasticsearch.repositories.RepositoryException;
>>>>>>> 98d508f... Add client-side encryption:src/test/java/org/elasticsearch/repositories/s3/AbstractS3SnapshotRestoreTest.java
import org.elasticsearch.repositories.RepositoryMissingException;
import org.elasticsearch.repositories.RepositoryVerificationException;
import org.elasticsearch.snapshots.SnapshotMissingException;
import org.elasticsearch.snapshots.SnapshotState;
import org.elasticsearch.test.ESIntegTestCase.ClusterScope;
import org.elasticsearch.test.ESIntegTestCase.Scope;
import org.junit.After;
import org.junit.Before;

import javax.crypto.KeyGenerator;
import java.io.InputStreamReader;
import java.security.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 */
@ClusterScope(scope = Scope.SUITE, numDataNodes = 2, numClientNodes = 0, transportClientRatio = 0.0)
abstract public class AbstractS3SnapshotRestoreTest extends AbstractAwsTestCase {

    @Override
    public Settings nodeSettings(int nodeOrdinal) {
        // nodeSettings is called before `wipeBefore()` so we need to define basePath here
        globalBasePath = "repo-" + randomInt();
        return Settings.builder().put(super.nodeSettings(nodeOrdinal))
                .put(S3Repository.Repositories.BASE_PATH_SETTING.getKey(), globalBasePath)
                .build();
    }

    private String basePath;
    private String globalBasePath;

    @Before
    public final void wipeBefore() {
        wipeRepositories();
        basePath = "repo-" + randomInt();
        cleanRepositoryFiles(basePath);
        cleanRepositoryFiles(globalBasePath);
    }

    @After
    public final void wipeAfter() {
        wipeRepositories();
        cleanRepositoryFiles(basePath);
        cleanRepositoryFiles(globalBasePath);
    }

    @AwaitsFix(bugUrl = "https://github.com/elastic/elasticsearch-cloud-aws/issues/211")
    public void testSimpleWorkflow() {
        Client client = client();
        Settings.Builder settings = Settings.settingsBuilder()
                .put(S3Repository.Repository.CHUNK_SIZE_SETTING.getKey(), randomIntBetween(1000, 10000));

        // We sometime test getting the base_path from node settings using repositories.s3.base_path
        if (usually()) {
            settings.put(S3Repository.Repository.BASE_PATH_SETTING.getKey(), basePath);
        }

        logger.info("-->  creating s3 repository with bucket[{}] and path [{}]", internalCluster().getInstance(Settings.class).get("repositories.s3.bucket"), basePath);
        PutRepositoryResponse putRepositoryResponse = client.admin().cluster().preparePutRepository("test-repo")
                .setType("s3").setSettings(settings
                        ).get();
        assertThat(putRepositoryResponse.isAcknowledged(), equalTo(true));

        createIndex("test-idx-1", "test-idx-2", "test-idx-3");
        ensureGreen();

        logger.info("--> indexing some data");
        for (int i = 0; i < 100; i++) {
            index("test-idx-1", "doc", Integer.toString(i), "foo", "bar" + i);
            index("test-idx-2", "doc", Integer.toString(i), "foo", "baz" + i);
            index("test-idx-3", "doc", Integer.toString(i), "foo", "baz" + i);
        }
        refresh();
        assertThat(client.prepareSearch("test-idx-1").setSize(0).get().getHits().totalHits(), equalTo(100L));
        assertThat(client.prepareSearch("test-idx-2").setSize(0).get().getHits().totalHits(), equalTo(100L));
        assertThat(client.prepareSearch("test-idx-3").setSize(0).get().getHits().totalHits(), equalTo(100L));

        logger.info("--> snapshot");
        CreateSnapshotResponse createSnapshotResponse = client.admin().cluster().prepareCreateSnapshot("test-repo", "test-snap").setWaitForCompletion(true).setIndices("test-idx-*", "-test-idx-3").get();
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), greaterThan(0));
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), equalTo(createSnapshotResponse.getSnapshotInfo().totalShards()));

        assertThat(client.admin().cluster().prepareGetSnapshots("test-repo").setSnapshots("test-snap").get().getSnapshots().get(0).state(), equalTo(SnapshotState.SUCCESS));
        assertMetadataFileIsNotEncrypted("test-snap");

        logger.info("--> delete some data");
        for (int i = 0; i < 50; i++) {
            client.prepareDelete("test-idx-1", "doc", Integer.toString(i)).get();
        }
        for (int i = 50; i < 100; i++) {
            client.prepareDelete("test-idx-2", "doc", Integer.toString(i)).get();
        }
        for (int i = 0; i < 100; i += 2) {
            client.prepareDelete("test-idx-3", "doc", Integer.toString(i)).get();
        }
        refresh();
        assertThat(client.prepareSearch("test-idx-1").setSize(0).get().getHits().totalHits(), equalTo(50L));
        assertThat(client.prepareSearch("test-idx-2").setSize(0).get().getHits().totalHits(), equalTo(50L));
        assertThat(client.prepareSearch("test-idx-3").setSize(0).get().getHits().totalHits(), equalTo(50L));

        logger.info("--> close indices");
        client.admin().indices().prepareClose("test-idx-1", "test-idx-2").get();

        logger.info("--> restore all indices from the snapshot");
        RestoreSnapshotResponse restoreSnapshotResponse = client.admin().cluster().prepareRestoreSnapshot("test-repo", "test-snap").setWaitForCompletion(true).execute().actionGet();
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));

        ensureGreen();
        assertThat(client.prepareSearch("test-idx-1").setSize(0).get().getHits().totalHits(), equalTo(100L));
        assertThat(client.prepareSearch("test-idx-2").setSize(0).get().getHits().totalHits(), equalTo(100L));
        assertThat(client.prepareSearch("test-idx-3").setSize(0).get().getHits().totalHits(), equalTo(50L));

        // Test restore after index deletion
        logger.info("--> delete indices");
        cluster().wipeIndices("test-idx-1", "test-idx-2");
        logger.info("--> restore one index after deletion");
        restoreSnapshotResponse = client.admin().cluster().prepareRestoreSnapshot("test-repo", "test-snap").setWaitForCompletion(true).setIndices("test-idx-*", "-test-idx-2").execute().actionGet();
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));
        ensureGreen();
        assertThat(client.prepareSearch("test-idx-1").setSize(0).get().getHits().totalHits(), equalTo(100L));
        ClusterState clusterState = client.admin().cluster().prepareState().get().getState();
        assertThat(clusterState.getMetaData().hasIndex("test-idx-1"), equalTo(true));
        assertThat(clusterState.getMetaData().hasIndex("test-idx-2"), equalTo(false));
    }

    @AwaitsFix(bugUrl = "https://github.com/elastic/elasticsearch-cloud-aws/issues/211")
    public void testEncryption() {
        Client client = client();
        logger.info("-->  creating s3 repository with bucket[{}] and path [{}]", internalCluster().getInstance(Settings.class).get("repositories.s3.bucket"), basePath);
        PutRepositoryResponse putRepositoryResponse = client.admin().cluster().preparePutRepository("test-repo")
                .setType("s3").setSettings(Settings.settingsBuilder()
                        .put(S3Repository.Repository.BASE_PATH_SETTING.getKey(), basePath)
                        .put(S3Repository.Repository.CHUNK_SIZE_SETTING.getKey(), randomIntBetween(1000, 10000))
                        .put(S3Repository.Repository.SERVER_SIDE_ENCRYPTION_SETTING.getKey(), true)
                        ).get();
        assertThat(putRepositoryResponse.isAcknowledged(), equalTo(true));

        createIndex("test-idx-1", "test-idx-2", "test-idx-3");
        ensureGreen();

        logger.info("--> indexing some data");
        for (int i = 0; i < 100; i++) {
            index("test-idx-1", "doc", Integer.toString(i), "foo", "bar" + i);
            index("test-idx-2", "doc", Integer.toString(i), "foo", "baz" + i);
            index("test-idx-3", "doc", Integer.toString(i), "foo", "baz" + i);
        }
        refresh();
        assertThat(client.prepareSearch("test-idx-1").setSize(0).get().getHits().totalHits(), equalTo(100L));
        assertThat(client.prepareSearch("test-idx-2").setSize(0).get().getHits().totalHits(), equalTo(100L));
        assertThat(client.prepareSearch("test-idx-3").setSize(0).get().getHits().totalHits(), equalTo(100L));

        logger.info("--> snapshot");
        CreateSnapshotResponse createSnapshotResponse = client.admin().cluster().prepareCreateSnapshot("test-repo", "test-snap").setWaitForCompletion(true).setIndices("test-idx-*", "-test-idx-3").get();
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), greaterThan(0));
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), equalTo(createSnapshotResponse.getSnapshotInfo().totalShards()));

        assertThat(client.admin().cluster().prepareGetSnapshots("test-repo").setSnapshots("test-snap").get().getSnapshots().get(0).state(), equalTo(SnapshotState.SUCCESS));

        Settings settings = internalCluster().getInstance(Settings.class);
        Settings bucket = settings.getByPrefix("repositories.s3.");
        AmazonS3 s3Client = internalCluster().getInstance(AwsS3Service.class).client(
            null,
            S3Repository.Repositories.PROTOCOL_SETTING.get(settings),
            S3Repository.Repositories.REGION_SETTING.get(settings),
            S3Repository.Repositories.KEY_SETTING.get(settings),
            S3Repository.Repositories.SECRET_SETTING.get(settings),
            null);

        String bucketName = bucket.get("bucket");
        logger.info("--> verify encryption for bucket [{}], prefix [{}]", bucketName, basePath);
        List<S3ObjectSummary> summaries = s3Client.listObjects(bucketName, basePath).getObjectSummaries();
        for (S3ObjectSummary summary : summaries) {
            assertThat(s3Client.getObjectMetadata(bucketName, summary.getKey()).getSSEAlgorithm(), equalTo("AES256"));
        }

        logger.info("--> delete some data");
        for (int i = 0; i < 50; i++) {
            client.prepareDelete("test-idx-1", "doc", Integer.toString(i)).get();
        }
        for (int i = 50; i < 100; i++) {
            client.prepareDelete("test-idx-2", "doc", Integer.toString(i)).get();
        }
        for (int i = 0; i < 100; i += 2) {
            client.prepareDelete("test-idx-3", "doc", Integer.toString(i)).get();
        }
        refresh();
        assertThat(client.prepareSearch("test-idx-1").setSize(0).get().getHits().totalHits(), equalTo(50L));
        assertThat(client.prepareSearch("test-idx-2").setSize(0).get().getHits().totalHits(), equalTo(50L));
        assertThat(client.prepareSearch("test-idx-3").setSize(0).get().getHits().totalHits(), equalTo(50L));

        logger.info("--> close indices");
        client.admin().indices().prepareClose("test-idx-1", "test-idx-2").get();

        logger.info("--> restore all indices from the snapshot");
        RestoreSnapshotResponse restoreSnapshotResponse = client.admin().cluster().prepareRestoreSnapshot("test-repo", "test-snap").setWaitForCompletion(true).execute().actionGet();
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));

        ensureGreen();
        assertThat(client.prepareSearch("test-idx-1").setSize(0).get().getHits().totalHits(), equalTo(100L));
        assertThat(client.prepareSearch("test-idx-2").setSize(0).get().getHits().totalHits(), equalTo(100L));
        assertThat(client.prepareSearch("test-idx-3").setSize(0).get().getHits().totalHits(), equalTo(50L));

        // Test restore after index deletion
        logger.info("--> delete indices");
        cluster().wipeIndices("test-idx-1", "test-idx-2");
        logger.info("--> restore one index after deletion");
        restoreSnapshotResponse = client.admin().cluster().prepareRestoreSnapshot("test-repo", "test-snap").setWaitForCompletion(true).setIndices("test-idx-*", "-test-idx-2").execute().actionGet();
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));
        ensureGreen();
        assertThat(client.prepareSearch("test-idx-1").setSize(0).get().getHits().totalHits(), equalTo(100L));
        ClusterState clusterState = client.admin().cluster().prepareState().get().getState();
        assertThat(clusterState.getMetaData().hasIndex("test-idx-1"), equalTo(true));
        assertThat(clusterState.getMetaData().hasIndex("test-idx-2"), equalTo(false));
    }

    @Test @AwaitsFix(bugUrl = "https://github.com/elastic/elasticsearch-cloud-aws/issues/211")
    public void testClientSideEncryption() throws NoSuchAlgorithmException {

        KeyGenerator keyGenerator1 = KeyGenerator.getInstance("AES");
        keyGenerator1.init(128);
        String symmetricEncryptionKeyBase64 = Base64.encodeAsString(keyGenerator1.generateKey().getEncoded());

        KeyPairGenerator keyGenerator2 = KeyPairGenerator.getInstance("RSA");
        keyGenerator2.initialize(512, new SecureRandom());
        KeyPair keyPair = keyGenerator2.generateKeyPair();
        String publicEncryptionKeyBase64 = Base64.encodeAsString(keyPair.getPublic().getEncoded());
        String privateEncryptionKeyBase64 = Base64.encodeAsString(keyPair.getPrivate().getEncoded());

        Client client = client();
        try {
            PutRepositoryResponse putRepositoryResponse = client.admin().cluster().preparePutRepository("test-repo")
                    .setType("s3").setSettings(ImmutableSettings.settingsBuilder()
                                    .put("base_path", basePath)
                                    .put("client_side_encryption_key.symmetric", symmetricEncryptionKeyBase64)
                                    .put("client_side_encryption_key.public", publicEncryptionKeyBase64)
                                    .put("client_side_encryption_key.private", privateEncryptionKeyBase64)
                                    .put("chunk_size", randomIntBetween(1000, 10000))
                    ).get();
            fail("Symmetric and public/private key pairs are exclusive options. An exception should be thrown.");
        } catch(RepositoryException e) {
        }

        List<ImmutableSettings.Builder> allSettings = Arrays.asList(
            ImmutableSettings.settingsBuilder()
                    .put("base_path", basePath)
                    .put("client_side_encryption_key.symmetric", symmetricEncryptionKeyBase64)
                    .put("chunk_size", randomIntBetween(1000, 10000)),
            ImmutableSettings.settingsBuilder()
                    .put("base_path", basePath)
                    .put("client_side_encryption_key.public", publicEncryptionKeyBase64)
                    .put("client_side_encryption_key.private", privateEncryptionKeyBase64)
                    .put("chunk_size", randomIntBetween(1000, 10000))
        );
        for(ImmutableSettings.Builder settings: allSettings) {
            PutRepositoryResponse putRepositoryResponse = client.admin().cluster().preparePutRepository("test-repo")
                    .setType("s3").setSettings(settings).get();

            // Create the index and index some data
            createIndex("test-idx-1");
            for (int i = 0; i < 100; i++) {
                index("test-idx-1", "doc", Integer.toString(i), "foo", "bar" + i);
            }
            refresh();

            // Take the snapshot
            CreateSnapshotResponse createSnapshotResponse = client.admin().cluster().prepareCreateSnapshot("test-repo", "test-snap").setWaitForCompletion(true).setIndices("test-idx-1").get();
            assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), greaterThan(0));
            assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), equalTo(createSnapshotResponse.getSnapshotInfo().totalShards()));

            assertMetadataFileIsEncrypted("test-snap");

            // Restore
            cluster().wipeIndices("test-idx-1");
            RestoreSnapshotResponse restoreSnapshotResponse = client.admin().cluster().prepareRestoreSnapshot("test-repo", "test-snap").setWaitForCompletion(true).setIndices("test-idx-1").execute().actionGet();
            ensureGreen();
            assertThat(client.prepareCount("test-idx-1").get().getCount(), equalTo(100L));
            ClusterState clusterState = client.admin().cluster().prepareState().get().getState();
            assertThat(clusterState.getMetaData().hasIndex("test-idx-1"), equalTo(true));

            // Clean, the test will bbe run with different settings
            cluster().wipeIndices("test-idx-1");
            wipeRepositories();
            cleanRepositoryFiles(basePath);
        }
    }

    /**
     * This test verifies that the test configuration is set up in a manner that
     * does not make the test {@link #testRepositoryWithCustomCredentials()} pointless.
     */
    public void testRepositoryWithCustomCredentialsIsNotAccessibleByDefaultCredentials() {
        Client client = client();
        Settings bucketSettings = internalCluster().getInstance(Settings.class).getByPrefix("repositories.s3.private-bucket.");
        logger.info("-->  creating s3 repository with bucket[{}] and path [{}]", bucketSettings.get("bucket"), basePath);
        try {
            client.admin().cluster().preparePutRepository("test-repo")
                .setType("s3").setSettings(Settings.settingsBuilder()
                        .put(S3Repository.Repository.BASE_PATH_SETTING.getKey(), basePath)
                        .put(S3Repository.Repository.BUCKET_SETTING.getKey(), bucketSettings.get("bucket"))
                        ).get();
            fail("repository verification should have raise an exception!");
        } catch (RepositoryVerificationException e) {
        }
    }

    public void testRepositoryWithBasePath() {
        Client client = client();
        PutRepositoryResponse putRepositoryResponse = client.admin().cluster().preparePutRepository("test-repo")
            .setType("s3").setSettings(Settings.settingsBuilder()
                .put(S3Repository.Repository.BASE_PATH_SETTING.getKey(), basePath)
            ).get();
        assertThat(putRepositoryResponse.isAcknowledged(), equalTo(true));

        assertRepositoryIsOperational(client, "test-repo");
    }

    public void testRepositoryWithCustomCredentials() {
        Client client = client();
        Settings bucketSettings = internalCluster().getInstance(Settings.class).getByPrefix("repositories.s3.private-bucket.");
        logger.info("-->  creating s3 repository with bucket[{}] and path [{}]", bucketSettings.get("bucket"), basePath);
        PutRepositoryResponse putRepositoryResponse = client.admin().cluster().preparePutRepository("test-repo")
                .setType("s3").setSettings(Settings.settingsBuilder()
                    .put(S3Repository.Repository.BASE_PATH_SETTING.getKey(), basePath)
                    .put(S3Repository.Repository.REGION_SETTING.getKey(), bucketSettings.get("region"))
                    .put(S3Repository.Repository.KEY_SETTING.getKey(), bucketSettings.get("access_key"))
                    .put(S3Repository.Repository.SECRET_SETTING.getKey(), bucketSettings.get("secret_key"))
                    .put(S3Repository.Repository.BUCKET_SETTING.getKey(), bucketSettings.get("bucket"))
                    ).get();
        assertThat(putRepositoryResponse.isAcknowledged(), equalTo(true));

        assertRepositoryIsOperational(client, "test-repo");
    }

    @AwaitsFix(bugUrl = "https://github.com/elastic/elasticsearch-cloud-aws/issues/211")
    public void testRepositoryWithCustomEndpointProtocol() {
        Client client = client();
        Settings bucketSettings = internalCluster().getInstance(Settings.class).getByPrefix("repositories.s3.external-bucket.");
        logger.info("--> creating s3 repostoriy with endpoint [{}], bucket[{}] and path [{}]", bucketSettings.get("endpoint"), bucketSettings.get("bucket"), basePath);
        PutRepositoryResponse putRepositoryResponse = client.admin().cluster().preparePutRepository("test-repo")
                .setType("s3").setSettings(Settings.settingsBuilder()
                    .put(S3Repository.Repository.BUCKET_SETTING.getKey(), bucketSettings.get("bucket"))
                    .put(S3Repository.Repository.ENDPOINT_SETTING.getKey(), bucketSettings.get("endpoint"))
                    .put(S3Repository.Repository.KEY_SETTING.getKey(), bucketSettings.get("access_key"))
                    .put(S3Repository.Repository.SECRET_SETTING.getKey(), bucketSettings.get("secret_key"))
                    .put(S3Repository.Repository.BASE_PATH_SETTING.getKey(), basePath)
                    ).get();
        assertThat(putRepositoryResponse.isAcknowledged(), equalTo(true));
        assertRepositoryIsOperational(client, "test-repo");
    }

    /**
     * This test verifies that the test configuration is set up in a manner that
     * does not make the test {@link #testRepositoryInRemoteRegion()} pointless.
     */
    public void testRepositoryInRemoteRegionIsRemote() {
        Client client = client();
        Settings bucketSettings = internalCluster().getInstance(Settings.class).getByPrefix("repositories.s3.remote-bucket.");
        logger.info("-->  creating s3 repository with bucket[{}] and path [{}]", bucketSettings.get("bucket"), basePath);
        try {
            client.admin().cluster().preparePutRepository("test-repo")
                .setType("s3").setSettings(Settings.settingsBuilder()
                    .put(S3Repository.Repository.BASE_PATH_SETTING.getKey(), basePath)
                    .put(S3Repository.Repository.BUCKET_SETTING.getKey(), bucketSettings.get("bucket"))
                    // Below setting intentionally omitted to assert bucket is not available in default region.
                    //                        .put("region", privateBucketSettings.get("region"))
                    ).get();
            fail("repository verification should have raise an exception!");
        } catch (RepositoryVerificationException e) {
        }
    }

    @AwaitsFix(bugUrl = "https://github.com/elastic/elasticsearch-cloud-aws/issues/211")
    public void testRepositoryInRemoteRegion() {
        Client client = client();
        Settings settings = internalCluster().getInstance(Settings.class);
        Settings bucketSettings = settings.getByPrefix("repositories.s3.remote-bucket.");
        logger.info("-->  creating s3 repository with bucket[{}] and path [{}]", bucketSettings.get("bucket"), basePath);
        PutRepositoryResponse putRepositoryResponse = client.admin().cluster().preparePutRepository("test-repo")
                .setType("s3").setSettings(Settings.settingsBuilder()
                    .put(S3Repository.Repository.BASE_PATH_SETTING.getKey(), basePath)
                    .put(S3Repository.Repository.BUCKET_SETTING.getKey(), bucketSettings.get("bucket"))
                    .put(S3Repository.Repository.REGION_SETTING.getKey(), bucketSettings.get("region"))
                    ).get();
        assertThat(putRepositoryResponse.isAcknowledged(), equalTo(true));

        assertRepositoryIsOperational(client, "test-repo");
    }

    /**
     * Test case for issue #86: https://github.com/elastic/elasticsearch-cloud-aws/issues/86
     */
    public void testNonExistingRepo86() {
        Client client = client();
        logger.info("-->  creating s3 repository with bucket[{}] and path [{}]", internalCluster().getInstance(Settings.class).get("repositories.s3.bucket"), basePath);
        PutRepositoryResponse putRepositoryResponse = client.admin().cluster().preparePutRepository("test-repo")
                .setType("s3").setSettings(Settings.settingsBuilder()
                    .put(S3Repository.Repository.BASE_PATH_SETTING.getKey(), basePath)
                ).get();
        assertThat(putRepositoryResponse.isAcknowledged(), equalTo(true));

        logger.info("--> restore non existing snapshot");
        try {
            client.admin().cluster().prepareRestoreSnapshot("test-repo", "no-existing-snapshot").setWaitForCompletion(true).execute().actionGet();
            fail("Shouldn't be here");
        } catch (SnapshotMissingException ex) {
            // Expected
        }
    }

    /**
     * For issue #86: https://github.com/elastic/elasticsearch-cloud-aws/issues/86
     */
    public void testGetDeleteNonExistingSnapshot86() {
        ClusterAdminClient client = client().admin().cluster();
        logger.info("-->  creating s3 repository without any path");
        PutRepositoryResponse putRepositoryResponse = client.preparePutRepository("test-repo")
                .setType("s3").setSettings(Settings.settingsBuilder()
                    .put(S3Repository.Repository.BASE_PATH_SETTING.getKey(), basePath)
                ).get();
        assertThat(putRepositoryResponse.isAcknowledged(), equalTo(true));

        try {
            client.prepareGetSnapshots("test-repo").addSnapshots("no-existing-snapshot").get();
            fail("Shouldn't be here");
        } catch (SnapshotMissingException ex) {
            // Expected
        }

        try {
            client.prepareDeleteSnapshot("test-repo", "no-existing-snapshot").get();
            fail("Shouldn't be here");
        } catch (SnapshotMissingException ex) {
            // Expected
        }
    }

    private void assertRepositoryIsOperational(Client client, String repository) {
        createIndex("test-idx-1");
        ensureGreen();

        logger.info("--> indexing some data");
        for (int i = 0; i < 100; i++) {
            index("test-idx-1", "doc", Integer.toString(i), "foo", "bar" + i);
        }
        refresh();
        assertThat(client.prepareSearch("test-idx-1").setSize(0).get().getHits().totalHits(), equalTo(100L));

        logger.info("--> snapshot");
        CreateSnapshotResponse createSnapshotResponse = client.admin().cluster().prepareCreateSnapshot(repository, "test-snap").setWaitForCompletion(true).setIndices("test-idx-*").get();
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), greaterThan(0));
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), equalTo(createSnapshotResponse.getSnapshotInfo().totalShards()));

        assertThat(client.admin().cluster().prepareGetSnapshots(repository).setSnapshots("test-snap").get().getSnapshots().get(0).state(), equalTo(SnapshotState.SUCCESS));

        logger.info("--> delete some data");
        for (int i = 0; i < 50; i++) {
            client.prepareDelete("test-idx-1", "doc", Integer.toString(i)).get();
        }
        refresh();
        assertThat(client.prepareSearch("test-idx-1").setSize(0).get().getHits().totalHits(), equalTo(50L));

        logger.info("--> close indices");
        client.admin().indices().prepareClose("test-idx-1").get();

        logger.info("--> restore all indices from the snapshot");
        RestoreSnapshotResponse restoreSnapshotResponse = client.admin().cluster().prepareRestoreSnapshot(repository, "test-snap").setWaitForCompletion(true).execute().actionGet();
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));

        ensureGreen();
        assertThat(client.prepareSearch("test-idx-1").setSize(0).get().getHits().totalHits(), equalTo(100L));
    }

    private void assertMetadataFileIsEncrypted(String snapshotName) {

        Settings settings = internalCluster().getInstance(Settings.class);
        AmazonS3 s3Client = internalCluster().getInstance(AwsS3Service.class).client(
                settings.get("repositories.s3.endpoint"),
                settings.get("repositories.s3.protocol"),
                settings.get("repositories.s3.region"),
                settings.get("cloud.aws.access_key"),
                settings.get("cloud.aws.secret_key"));
        String bucket = settings.get("repositories.s3.bucket");
        String objectKey = basePath + "/metadata-" + snapshotName;
        S3Object object = s3Client.getObject(bucket, objectKey);

        try {
            JsonReader jsonReader = new JsonReader(new InputStreamReader(object.getObjectContent()));
            jsonReader.beginObject();
            assertThat("The file hasn't been encrypted properly, its content is still readable!", jsonReader.nextName(), not(equalTo("meta-data")));
        } catch(Exception e) {
            // The json is not valid, the file is encrypted

            // MalformedJsonException can't be catched directly so the following
            //   assertion is necessary to avoid silent failures.
            assertThat(e, instanceOf(MalformedJsonException.class));
        }
    }

    private void assertMetadataFileIsNotEncrypted(String snapshotName) {

        Settings settings = internalCluster().getInstance(Settings.class);
        AmazonS3 s3Client = internalCluster().getInstance(AwsS3Service.class).client(
                settings.get("repositories.s3.endpoint"),
                settings.get("repositories.s3.protocol"),
                settings.get("repositories.s3.region"),
                settings.get("cloud.aws.access_key"),
                settings.get("cloud.aws.secret_key"));
        String bucket = settings.get("repositories.s3.bucket");
        String objectKey = basePath + "/metadata-" + snapshotName;
        S3Object object = s3Client.getObject(bucket, objectKey);

        JsonReader jsonReader = new JsonReader(new InputStreamReader(object.getObjectContent()));
        jsonReader.beginObject();
        assertThat("The file wasn't decrypted properly", jsonReader.nextName(), equalTo("meta-data"));

        // The beginning of the file looks like json. If it was encrypted, it wouldn't.
    }

    /**
     * Deletes repositories, supports wildcard notation.
     */
    public static void wipeRepositories(String... repositories) {
        // if nothing is provided, delete all
        if (repositories.length == 0) {
            repositories = new String[]{"*"};
        }
        for (String repository : repositories) {
            try {
                client().admin().cluster().prepareDeleteRepository(repository).execute().actionGet();
            } catch (RepositoryMissingException ex) {
                // ignore
            }
        }
    }

    /**
     * Deletes content of the repository files in the bucket
     */
    public void cleanRepositoryFiles(String basePath) {
        Settings settings = internalCluster().getInstance(Settings.class);
        Settings[] buckets = {
                settings.getByPrefix("repositories.s3."),
                settings.getByPrefix("repositories.s3.private-bucket."),
                settings.getByPrefix("repositories.s3.remote-bucket."),
                settings.getByPrefix("repositories.s3.external-bucket.")
        };
        for (Settings bucket : buckets) {
            String endpoint = bucket.get("endpoint", S3Repository.Repositories.ENDPOINT_SETTING.get(settings));
            Protocol protocol = S3Repository.Repositories.PROTOCOL_SETTING.get(settings);
            String region = bucket.get("region", S3Repository.Repositories.REGION_SETTING.get(settings));
            String accessKey = bucket.get("access_key", S3Repository.Repositories.KEY_SETTING.get(settings));
            String secretKey = bucket.get("secret_key", S3Repository.Repositories.SECRET_SETTING.get(settings));
            String bucketName = bucket.get("bucket");

            // We check that settings has been set in elasticsearch.yml integration test file
            // as described in README
            assertThat("Your settings in elasticsearch.yml are incorrects. Check README file.", bucketName, notNullValue());
            AmazonS3 client = internalCluster().getInstance(AwsS3Service.class).client(endpoint, protocol, region, accessKey, secretKey, null);
            try {
                ObjectListing prevListing = null;
                //From http://docs.amazonwebservices.com/AmazonS3/latest/dev/DeletingMultipleObjectsUsingJava.html
                //we can do at most 1K objects per delete
                //We don't know the bucket name until first object listing
                DeleteObjectsRequest multiObjectDeleteRequest = null;
                ArrayList<DeleteObjectsRequest.KeyVersion> keys = new ArrayList<DeleteObjectsRequest.KeyVersion>();
                while (true) {
                    ObjectListing list;
                    if (prevListing != null) {
                        list = client.listNextBatchOfObjects(prevListing);
                    } else {
                        list = client.listObjects(bucketName, basePath);
                        multiObjectDeleteRequest = new DeleteObjectsRequest(list.getBucketName());
                    }
                    for (S3ObjectSummary summary : list.getObjectSummaries()) {
                        keys.add(new DeleteObjectsRequest.KeyVersion(summary.getKey()));
                        //Every 500 objects batch the delete request
                        if (keys.size() > 500) {
                            multiObjectDeleteRequest.setKeys(keys);
                            client.deleteObjects(multiObjectDeleteRequest);
                            multiObjectDeleteRequest = new DeleteObjectsRequest(list.getBucketName());
                            keys.clear();
                        }
                    }
                    if (list.isTruncated()) {
                        prevListing = list;
                    } else {
                        break;
                    }
                }
                if (!keys.isEmpty()) {
                    multiObjectDeleteRequest.setKeys(keys);
                    client.deleteObjects(multiObjectDeleteRequest);
                }
            } catch (Throwable ex) {
                logger.warn("Failed to delete S3 repository [{}] in [{}]", ex, bucketName, region);
            }
        }
    }
}
