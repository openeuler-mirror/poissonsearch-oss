/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authc.support;

import com.unboundid.ldap.sdk.DN;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.xpack.security.audit.logfile.CapturingLogger;
import org.elasticsearch.xpack.security.authc.RealmConfig;
import org.elasticsearch.xpack.security.authc.ldap.LdapRealm;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.ResourceWatcherService;
import org.junit.After;
import org.junit.Before;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

public class DnRoleMapperTests extends ESTestCase {

    private static final String ROLE_MAPPING_FILE_SETTING = DnRoleMapper.ROLE_MAPPING_FILE_SETTING.getKey();
    private static final String USE_UNMAPPED_GROUPS_AS_ROLES_SETTING_KEY = DnRoleMapper.USE_UNMAPPED_GROUPS_AS_ROLES_SETTING.getKey();

    private static final String[] STARK_GROUP_DNS = new String[] {
            //groups can be named by different attributes, depending on the directory,
            //we don't care what it is named by
            "cn=shield,ou=marvel,o=superheros",
            "cn=avengers,ou=marvel,o=superheros",
            "group=genius, dc=mit, dc=edu",
            "groupName = billionaire , ou = acme",
            "gid = playboy , dc = example , dc = com",
            "groupid=philanthropist,ou=groups,dc=unitedway,dc=org"
    };

    protected Settings settings;
    protected Environment env;
    protected ThreadPool threadPool;

    @Before
    public void init() throws IOException {
        settings = Settings.builder()
                .put("resource.reload.interval.high", "2s")
                .put("path.home", createTempDir())
                .build();
        env = new Environment(settings);
        if (Files.exists(env.configFile()) == false) {
            Files.createDirectory(env.configFile());
        }
        threadPool = new TestThreadPool("test");
    }

    @After
    public void shutdown() throws InterruptedException {
        terminate(threadPool);
    }

    public void testMapper_ConfiguredWithUnreadableFile() throws Exception {
        Path file = createTempFile();
        // writing in utf_16 should cause a parsing error as we try to read the file in utf_8
        Files.write(file, Collections.singletonList("aldlfkjldjdflkjd"), StandardCharsets.UTF_16);

        ResourceWatcherService watcherService = new ResourceWatcherService(settings, threadPool);
        DnRoleMapper mapper = createMapper(file, watcherService);
        assertThat(mapper.mappingsCount(), is(0));
    }

    public void testMapper_AutoReload() throws Exception {
        Path roleMappingFile = getDataPath("role_mapping.yml");
        Path file = env.configFile().resolve("test_role_mapping.yml");
        Files.copy(roleMappingFile, file, StandardCopyOption.REPLACE_EXISTING);

        final CountDownLatch latch = new CountDownLatch(1);

        ResourceWatcherService watcherService = new ResourceWatcherService(settings, threadPool);
        DnRoleMapper mapper = createMapper(file, watcherService);
        mapper.addListener(latch::countDown);

        Set<String> roles = mapper.resolveRoles("", Collections.singletonList("cn=shield,ou=marvel,o=superheros"));
        assertThat(roles, notNullValue());
        assertThat(roles.size(), is(1));
        assertThat(roles, contains("security"));

        watcherService.start();

        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
            writer.newLine();
            writer.append("fantastic_four:\n")
                    .append("  - \"cn=fantastic_four,ou=marvel,o=superheros\"");
        }

        if (!latch.await(5, TimeUnit.SECONDS)) {
            fail("Waited too long for the updated file to be picked up");
        }

        roles = mapper.resolveRoles("", Collections.singletonList("cn=fantastic_four,ou=marvel,o=superheros"));
        assertThat(roles, notNullValue());
        assertThat(roles.size(), is(1));
        assertThat(roles, contains("fantastic_four"));
    }

    public void testMapper_AutoReload_WithParseFailures() throws Exception {
        Path roleMappingFile = getDataPath("role_mapping.yml");
        Path file = env.configFile().resolve("test_role_mapping.yml");
        Files.copy(roleMappingFile, file, StandardCopyOption.REPLACE_EXISTING);

        final CountDownLatch latch = new CountDownLatch(1);

        ResourceWatcherService watcherService = new ResourceWatcherService(settings, threadPool);
        DnRoleMapper mapper = createMapper(file, watcherService);
        mapper.addListener(latch::countDown);

        Set<String> roles = mapper.resolveRoles("", Collections.singletonList("cn=shield,ou=marvel,o=superheros"));
        assertThat(roles, notNullValue());
        assertThat(roles.size(), is(1));
        assertThat(roles, contains("security"));

        watcherService.start();

        // now replacing the content of the users file with something that cannot be read
        Files.write(file, Collections.singletonList("aldlfkjldjdflkjd"), StandardCharsets.UTF_16);

        if (!latch.await(5, TimeUnit.SECONDS)) {
            fail("Waited too long for the updated file to be picked up");
        }

        assertThat(mapper.mappingsCount(), is(0));
    }

    public void testParseFile() throws Exception {
        Path file = getDataPath("role_mapping.yml");
        Logger logger = CapturingLogger.newCapturingLogger(Level.INFO);
        Map<DN, Set<String>> mappings = DnRoleMapper.parseFile(file, logger, "_type", "_name");
        assertThat(mappings, notNullValue());
        assertThat(mappings.size(), is(3));

        DN dn = new DN("cn=avengers,ou=marvel,o=superheros");
        assertThat(mappings, hasKey(dn));
        Set<String> roles = mappings.get(dn);
        assertThat(roles, notNullValue());
        assertThat(roles, hasSize(2));
        assertThat(roles, containsInAnyOrder("security", "avenger"));

        dn = new DN("cn=shield,ou=marvel,o=superheros");
        assertThat(mappings, hasKey(dn));
        roles = mappings.get(dn);
        assertThat(roles, notNullValue());
        assertThat(roles, hasSize(1));
        assertThat(roles, contains("security"));

        dn = new DN("cn=Horatio Hornblower,ou=people,o=sevenSeas");
        assertThat(mappings, hasKey(dn));
        roles = mappings.get(dn);
        assertThat(roles, notNullValue());
        assertThat(roles, hasSize(1));
        assertThat(roles, contains("avenger"));
    }

    public void testParseFile_Empty() throws Exception {
        Path file = createTempDir().resolve("foo.yaml");
        Files.createFile(file);
        Logger logger = CapturingLogger.newCapturingLogger(Level.DEBUG);
        Map<DN, Set<String>> mappings = DnRoleMapper.parseFile(file, logger, "_type", "_name");
        assertThat(mappings, notNullValue());
        assertThat(mappings.isEmpty(), is(true));
        List<String> events = CapturingLogger.output(logger.getName(), Level.DEBUG);
        assertThat(events.size(), is(1));
        assertThat(events.get(0), containsString("[0] role mappings found"));
    }

    public void testParseFile_WhenFileDoesNotExist() throws Exception {
        Path file = createTempDir().resolve(randomAsciiOfLength(10));
        Logger logger = CapturingLogger.newCapturingLogger(Level.INFO);
        Map<DN, Set<String>> mappings = DnRoleMapper.parseFile(file, logger, "_type", "_name");
        assertThat(mappings, notNullValue());
        assertThat(mappings.isEmpty(), is(true));
    }

    public void testParseFile_WhenCannotReadFile() throws Exception {
        Path file = createTempFile();
        // writing in utf_16 should cause a parsing error as we try to read the file in utf_8
        Files.write(file, Collections.singletonList("aldlfkjldjdflkjd"), StandardCharsets.UTF_16);
        Logger logger = CapturingLogger.newCapturingLogger(Level.INFO);
        try {
            DnRoleMapper.parseFile(file, logger, "_type", "_name");
            fail("expected a parse failure");
        } catch (Exception e) {
            this.logger.info("expected", e);
        }
    }

    public void testParseFileLenient_WhenCannotReadFile() throws Exception {
        Path file = createTempFile();
        // writing in utf_16 should cause a parsing error as we try to read the file in utf_8
        Files.write(file, Collections.singletonList("aldlfkjldjdflkjd"), StandardCharsets.UTF_16);
        Logger logger = CapturingLogger.newCapturingLogger(Level.INFO);
        Map<DN, Set<String>> mappings = DnRoleMapper.parseFileLenient(file, logger, "_type", "_name");
        assertThat(mappings, notNullValue());
        assertThat(mappings.isEmpty(), is(true));
        List<String> events = CapturingLogger.output(logger.getName(), Level.ERROR);
        assertThat(events.size(), is(1));
        assertThat(events.get(0), containsString("failed to parse role mappings file"));
    }

    public void testYaml() throws Exception {
        Path file = getDataPath("role_mapping.yml");
        Settings ldapSettings = Settings.builder()
                .put(ROLE_MAPPING_FILE_SETTING, file.toAbsolutePath())
                .build();
        RealmConfig config = new RealmConfig("ldap1", ldapSettings, settings);

        DnRoleMapper mapper = new DnRoleMapper(LdapRealm.LDAP_TYPE, config, new ResourceWatcherService(settings, threadPool), null);

        Set<String> roles = mapper.resolveRoles("", Arrays.asList(STARK_GROUP_DNS));

        //verify
        assertThat(roles, hasItems("security", "avenger"));
    }

    public void testRelativeDN() {
        Settings ldapSettings = Settings.builder()
                .put(USE_UNMAPPED_GROUPS_AS_ROLES_SETTING_KEY, true)
                .build();
        RealmConfig config = new RealmConfig("ldap1", ldapSettings, settings);

        DnRoleMapper mapper = new DnRoleMapper(LdapRealm.LDAP_TYPE, config, new ResourceWatcherService(settings, threadPool), null);

        Set<String> roles = mapper.resolveRoles("", Arrays.asList(STARK_GROUP_DNS));
        assertThat(roles, hasItems("genius", "billionaire", "playboy", "philanthropist", "shield", "avengers"));
    }

    public void testUserDNMapping() throws Exception {
        Path file = getDataPath("role_mapping.yml");
        Settings ldapSettings = Settings.builder()
                .put(ROLE_MAPPING_FILE_SETTING, file.toAbsolutePath())
                .put(USE_UNMAPPED_GROUPS_AS_ROLES_SETTING_KEY, false)
                .build();
        RealmConfig config = new RealmConfig("ldap-userdn-role", ldapSettings, settings);

        DnRoleMapper mapper = new DnRoleMapper(LdapRealm.LDAP_TYPE, config, new ResourceWatcherService(settings, threadPool), null);

        Set<String> roles = mapper.resolveRoles("cn=Horatio Hornblower,ou=people,o=sevenSeas", Collections.<String>emptyList());
        assertThat(roles, hasItem("avenger"));
    }

    protected DnRoleMapper createMapper(Path file, ResourceWatcherService watcherService) {
        Settings realmSettings = Settings.builder()
                .put("files.role_mapping", file.toAbsolutePath())
                .build();
        RealmConfig config = new RealmConfig("ad-group-mapper-test", realmSettings, settings, env);
        return new DnRoleMapper(randomBoolean() ? LdapRealm.AD_TYPE : LdapRealm.LDAP_TYPE, config, watcherService, () -> {});
    }
}
