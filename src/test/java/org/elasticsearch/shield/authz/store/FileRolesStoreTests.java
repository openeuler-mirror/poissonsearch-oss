/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authz.store;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.shield.audit.logfile.CapturingLogger;
import org.elasticsearch.shield.authc.support.RefreshListener;
import org.elasticsearch.shield.authz.Permission;
import org.elasticsearch.shield.authz.Privilege;
import org.elasticsearch.test.ElasticsearchTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.ResourceWatcherService;
import org.junit.Test;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.*;

/**
 *
 */
public class FileRolesStoreTests extends ElasticsearchTestCase {

    @Test
    public void testParseFile() throws Exception {
        Path path = getDataPath("roles.yml");
        Map<String, Permission.Global.Role> roles = FileRolesStore.parseFile(path, Collections.<Permission.Global.Role>emptySet(), logger);
        assertThat(roles, notNullValue());
        assertThat(roles.size(), is(4));

        Permission.Global.Role role = roles.get("role1");
        assertThat(role, notNullValue());
        assertThat(role.name(), equalTo("role1"));
        assertThat(role.cluster(), notNullValue());
        assertThat(role.cluster().privilege(), is(Privilege.Cluster.ALL));
        assertThat(role.indices(), notNullValue());
        assertThat(role.indices().groups(), notNullValue());
        assertThat(role.indices().groups().length, is(2));

        Permission.Global.Indices.Group group = role.indices().groups()[0];
        assertThat(group.indices(), notNullValue());
        assertThat(group.indices().length, is(2));
        assertThat(group.indices()[0], equalTo("idx1"));
        assertThat(group.indices()[1], equalTo("idx2"));
        assertThat(group.privilege(), notNullValue());
        assertThat(group.privilege(), is(Privilege.Index.READ));

        group = role.indices().groups()[1];
        assertThat(group.indices(), notNullValue());
        assertThat(group.indices().length, is(1));
        assertThat(group.indices()[0], equalTo("idx3"));
        assertThat(group.privilege(), notNullValue());
        assertThat(group.privilege(), is(Privilege.Index.CRUD));

        role = roles.get("role2");
        assertThat(role, notNullValue());
        assertThat(role.name(), equalTo("role2"));
        assertThat(role.cluster(), notNullValue());
        assertThat(role.cluster().privilege(), is(Privilege.Cluster.ALL)); // MONITOR is collapsed into ALL
        assertThat(role.indices(), notNullValue());
        assertThat(role.indices(), is(Permission.Indices.Core.NONE));

        role = roles.get("role3");
        assertThat(role, notNullValue());
        assertThat(role.name(), equalTo("role3"));
        assertThat(role.cluster(), notNullValue());
        assertThat(role.cluster(), is(Permission.Cluster.Core.NONE));
        assertThat(role.indices(), notNullValue());
        assertThat(role.indices().groups(), notNullValue());
        assertThat(role.indices().groups().length, is(1));

        group = role.indices().groups()[0];
        assertThat(group.indices(), notNullValue());
        assertThat(group.indices().length, is(1));
        assertThat(group.indices()[0], equalTo("/.*_.*/"));
        assertThat(group.privilege(), notNullValue());
        assertThat(group.privilege().isAlias(Privilege.Index.union(Privilege.Index.READ, Privilege.Index.WRITE)), is(true));

        role = roles.get("role4");
        assertThat(role, notNullValue());
        assertThat(role.name(), equalTo("role4"));
        assertThat(role.cluster(), notNullValue());
        assertThat(role.cluster(), is(Permission.Cluster.Core.NONE));
        assertThat(role.indices(), is(Permission.Indices.Core.NONE));
    }

    /**
     * This test is mainly to make sure we can read the default roles.yml config
     */
    @Test
    public void testDefaultRolesFile() throws Exception {
        Path path = getDataPath("default_roles.yml");
        Map<String, Permission.Global.Role> roles = FileRolesStore.parseFile(path, Collections.<Permission.Global.Role>emptySet(), logger);
        assertThat(roles, notNullValue());
        assertThat(roles.size(), is(8));

        assertThat(roles, hasKey("admin"));
        assertThat(roles, hasKey("power_user"));
        assertThat(roles, hasKey("user"));
        assertThat(roles, hasKey("kibana3"));
        assertThat(roles, hasKey("kibana4"));
        assertThat(roles, hasKey("logstash"));
        assertThat(roles, hasKey("marvel_user"));
        assertThat(roles, hasKey("marvel_agent"));
    }

    @Test
    public void testAutoReload() throws Exception {
        ThreadPool threadPool = null;
        ResourceWatcherService watcherService = null;
        try {
            Path roles = getDataPath("roles.yml");
            Path tmp = createTempFile();
            try (OutputStream stream = Files.newOutputStream(tmp)) {
                Files.copy(roles, stream);
            }

            Settings settings = Settings.builder()
                    .put("watcher.interval.high", "500ms")
                    .put("shield.authz.store.files.roles", tmp.toAbsolutePath())
                    .put("path.home", createTempDir())
                    .build();

            Environment env = new Environment(settings);
            threadPool = new ThreadPool("test");
            watcherService = new ResourceWatcherService(settings, threadPool);
            final CountDownLatch latch = new CountDownLatch(1);
            FileRolesStore store = new FileRolesStore(settings, env, watcherService, Collections.<Permission.Global.Role>emptySet(), new RefreshListener() {
                @Override
                public void onRefresh() {
                    latch.countDown();
                }
            });
            store.start();

            Permission.Global.Role role = store.role("role1");
            assertThat(role, notNullValue());
            role = store.role("role5");
            assertThat(role, nullValue());

            watcherService.start();

            try (BufferedWriter writer = Files.newBufferedWriter(tmp, Charsets.UTF_8, StandardOpenOption.APPEND)) {
                writer.newLine();
                writer.newLine();
                writer.newLine();
                writer.append("role5:").append(System.lineSeparator());
                writer.append("  cluster: 'MONITOR'");
            }

            if (!latch.await(5, TimeUnit.SECONDS)) {
                fail("Waited too long for the updated file to be picked up");
            }

            role = store.role("role5");
            assertThat(role, notNullValue());
            assertThat(role.name(), equalTo("role5"));
            assertThat(role.cluster().check("cluster:monitor/foo/bar"), is(true));
            assertThat(role.cluster().check("cluster:admin/foo/bar"), is(false));

        } finally {
            if (watcherService != null) {
                watcherService.stop();
            }
            terminate(threadPool);
        }
    }

    @Test
    public void testThatEmptyFileDoesNotResultInLoop() throws Exception {
        Path file = createTempFile();
        Files.write(file, ImmutableList.of("#"), Charsets.UTF_8);
        Map<String, Permission.Global.Role> roles = FileRolesStore.parseFile(file, Collections.<Permission.Global.Role>emptySet(), logger);
        assertThat(roles.keySet(), is(empty()));
    }

    @Test
    public void testThatInvalidRoleDefinitions() throws Exception {
        Path path = getDataPath("invalid_roles.yml");
        CapturingLogger logger = new CapturingLogger(CapturingLogger.Level.ERROR);
        Map<String, Permission.Global.Role> roles = FileRolesStore.parseFile(path, Collections.<Permission.Global.Role>emptySet(), logger);
        assertThat(roles.size(), is(1));
        assertThat(roles, hasKey("valid_role"));
        Permission.Global.Role role = roles.get("valid_role");
        assertThat(role, notNullValue());
        assertThat(role.name(), equalTo("valid_role"));

        List<CapturingLogger.Msg> entries = logger.output(CapturingLogger.Level.ERROR);
        assertThat(entries, hasSize(5));
        assertThat(entries.get(0).text, startsWith("invalid role definition [$dlk39] in roles file [" + path.toAbsolutePath() + "]. invalid role name"));
        assertThat(entries.get(1).text, startsWith("invalid role definition [role1] in roles file [" + path.toAbsolutePath() + "]"));
        assertThat(entries.get(2).text, startsWith("invalid role definition [role2] in roles file [" + path.toAbsolutePath() + "]. could not resolve cluster privileges [blkjdlkd]"));
        assertThat(entries.get(3).text, startsWith("invalid role definition [role3] in roles file [" + path.toAbsolutePath() + "]. [indices] field value must be an array"));
        assertThat(entries.get(4).text, startsWith("invalid role definition [role4] in roles file [" + path.toAbsolutePath() + "]. could not resolve indices privileges [al;kjdlkj;lkj]"));
    }

    @Test
    public void testThatRoleNamesDoesNotResolvePermissions() throws Exception {
        Path path = getDataPath("invalid_roles.yml");
        CapturingLogger logger = new CapturingLogger(CapturingLogger.Level.ERROR);
        ImmutableSet<String> roleNames = FileRolesStore.parseFileForRoleNames(path, logger);
        assertThat(roleNames.size(), is(5));
        assertThat(roleNames, containsInAnyOrder("valid_role", "role1", "role2", "role3", "role4"));

        List<CapturingLogger.Msg> entries = logger.output(CapturingLogger.Level.ERROR);
        assertThat(entries, hasSize(1));
        assertThat(entries.get(0).text, startsWith("invalid role definition [$dlk39] in roles file [" + path.toAbsolutePath() + "]. invalid role name"));
    }

    @Test
    public void testReservedRoles() throws Exception {
        Set<Permission.Global.Role> reservedRoles = ImmutableSet.<Permission.Global.Role>builder()
                .add(Permission.Global.Role.builder("reserved")
                        .set(Privilege.Cluster.ALL)
                        .build())
                .build();

        CapturingLogger logger = new CapturingLogger(CapturingLogger.Level.INFO);

        Path path = getDataPath("reserved_roles.yml");
        Map<String, Permission.Global.Role> roles = FileRolesStore.parseFile(path, reservedRoles, logger);
        assertThat(roles, notNullValue());
        assertThat(roles.size(), is(2));

        assertThat(roles, hasKey("admin"));
        assertThat(roles, hasKey("reserved"));
        Permission.Global.Role reserved = roles.get("reserved");

        List<CapturingLogger.Msg> messages = logger.output(CapturingLogger.Level.WARN);
        assertThat(messages, notNullValue());
        assertThat(messages, hasSize(2));
        // the system role will always be checked first
        assertThat(messages.get(0).text, containsString("role [__es_system_role] is reserved"));
        assertThat(messages.get(1).text, containsString("role [reserved] is reserved"));

        // we overriden the configured reserved role with ALL cluster priv. (was configured to be "monitor" only)
        assertThat(reserved.cluster().check("cluster:admin/test"), is(true));

        // we overriden the configured reserved role without index privs. (was configured with index priv on "index_a_*" indices)
        assertThat(reserved.indices().isEmpty(), is(true));
    }
}
