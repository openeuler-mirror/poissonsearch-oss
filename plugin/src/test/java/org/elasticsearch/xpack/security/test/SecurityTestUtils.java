/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.test;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.cluster.metadata.AliasMetaData;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.UnassignedInfo;
import org.elasticsearch.common.io.FileSystemUtils;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.elasticsearch.cluster.routing.RecoverySource.StoreRecoverySource.EXISTING_STORE_INSTANCE;
import static org.elasticsearch.xpack.security.SecurityLifecycleService.SECURITY_INDEX_NAME;

public class SecurityTestUtils {

    public static void createFolder(Path path) {
        //the directory might exist e.g. if the global cluster gets restarted, then we recreate the directory as well
        if (Files.exists(path)) {
            try {
                FileSystemUtils.deleteSubDirectories(path);
            } catch (IOException e) {
                throw new RuntimeException("could not delete existing temporary folder: " + path.toAbsolutePath(), e);
            }
        } else {
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                throw new RuntimeException("could not create temporary folder: " + path.toAbsolutePath());
            }
        }
    }

    public static String writeFile(Path folder, String name, byte[] content) {
        Path file = folder.resolve(name);
        try (OutputStream os = Files.newOutputStream(file)) {
            Streams.copy(content, os);
        } catch (IOException e) {
            throw new ElasticsearchException("error writing file in test", e);
        }
        return file.toAbsolutePath().toString();
    }

    public static String writeFile(Path folder, String name, String content) {
        return writeFile(folder, name, content.getBytes(StandardCharsets.UTF_8));
    }

    public static RoutingTable buildIndexRoutingTable(String indexName) {
        Index index = new Index(indexName, UUID.randomUUID().toString());
        ShardRouting shardRouting = ShardRouting.newUnassigned(new ShardId(index, 0), true, EXISTING_STORE_INSTANCE,
                new UnassignedInfo(UnassignedInfo.Reason.INDEX_CREATED, ""));
        String nodeId = ESTestCase.randomAlphaOfLength(8);
        IndexShardRoutingTable table = new IndexShardRoutingTable.Builder(new ShardId(index, 0))
                .addShard(shardRouting.initialize(nodeId, null, shardRouting.getExpectedShardSize()).moveToStarted())
                .build();
        return RoutingTable.builder()
                .add(IndexRoutingTable.builder(index).addIndexShard(table).build())
                .build();
    }

    /**
     * Adds the index alias {@code .security} to the underlying concrete index.
     */
    public static MetaData addAliasToMetaData(MetaData metaData, String indexName) {
        AliasMetaData aliasMetaData = AliasMetaData.newAliasMetaDataBuilder(SECURITY_INDEX_NAME).build();
        MetaData.Builder metaDataBuilder = new MetaData.Builder(metaData);
        IndexMetaData indexMetaData = metaData.index(indexName);
        metaDataBuilder.put(IndexMetaData.builder(indexMetaData).putAlias(aliasMetaData));
        return metaDataBuilder.build();
    }

}
