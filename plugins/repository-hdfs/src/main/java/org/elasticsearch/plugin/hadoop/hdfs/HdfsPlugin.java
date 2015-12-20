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
package org.elasticsearch.plugin.hadoop.hdfs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedAction;

import org.elasticsearch.SpecialPermission;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.index.snapshots.blobstore.BlobStoreIndexShardRepository;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.repositories.RepositoriesModule;
import org.elasticsearch.repositories.hdfs.HdfsRepository;


// Code 
public class HdfsPlugin extends Plugin {
  
    // initialize some problematic classes with elevated privileges
    static {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new SpecialPermission());
        }
        AccessController.doPrivileged(new PrivilegedAction<Void>() {
            @Override
            public Void run() {
                return evilHadoopInit();
            }
        });
    }

    @SuppressForbidden(reason = "Needs a security hack for hadoop on windows, until HADOOP-XXXX is fixed")
    private static Void evilHadoopInit() {
        String oldValue = null;
        try {
            // hack: on Windows, Shell's clinit has a similar problem that on unix,
            // but here we can workaround it for now by setting hadoop home
            // TODO: remove THIS when hadoop is fixed
            Path hadoopHome = Files.createTempDirectory("hadoop").toAbsolutePath();
            oldValue = System.setProperty("hadoop.home.dir", hadoopHome.toString());
            Class.forName("org.apache.hadoop.security.UserGroupInformation");
            Class.forName("org.apache.hadoop.util.StringUtils");
            Class.forName("org.apache.hadoop.util.ShutdownHookManager");
        } catch (ClassNotFoundException | IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (oldValue == null) {
                System.clearProperty("hadoop.home.dir");
            } else {
                System.setProperty("hadoop.home.dir", oldValue);
            }
        }
        return null;
    }

    @Override
    public String name() {
        return "repository-hdfs";
    }

    @Override
    public String description() {
        return "HDFS Repository Plugin";
    }

    public void onModule(RepositoriesModule repositoriesModule) {
        repositoriesModule.registerRepository("hdfs", HdfsRepository.class, BlobStoreIndexShardRepository.class);
    }
}
