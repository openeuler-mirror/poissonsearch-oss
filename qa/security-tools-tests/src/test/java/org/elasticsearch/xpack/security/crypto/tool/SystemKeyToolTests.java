/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.crypto.tool;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.apache.lucene.util.IOUtils;
import org.elasticsearch.cli.Command;
import org.elasticsearch.cli.CommandTestCase;
import org.elasticsearch.cli.UserException;
import org.elasticsearch.common.io.PathUtilsForTesting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.TestEnvironment;
import org.elasticsearch.xpack.core.XPackField;
import org.junit.After;

import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;

public class SystemKeyToolTests extends CommandTestCase {

    private FileSystem jimfs;

    private Path initFileSystem(boolean needsPosix) throws Exception {
        String view = needsPosix ? "posix" : randomFrom("basic", "posix");
        Configuration conf = Configuration.unix().toBuilder().setAttributeViews(view).build();
        jimfs = Jimfs.newFileSystem(conf);
        PathUtilsForTesting.installMock(jimfs);
        return jimfs.getPath("eshome");
    }

    @After
    public void tearDown() throws Exception {
        IOUtils.close(jimfs);
        super.tearDown();
    }

    @Override
    protected Command newCommand() {
        return new SystemKeyTool() {

            @Override
            protected Environment createEnv(Map<String, String> settings) throws UserException {
                Settings.Builder builder = Settings.builder();
                settings.forEach((k,v) -> builder.put(k, v));
                return TestEnvironment.newEnvironment(builder.build());
            }

        };
    }

    public void testGenerate() throws Exception {
        final Path homeDir = initFileSystem(true);

        Path path = jimfs.getPath(randomAlphaOfLength(10)).resolve("key");
        Files.createDirectory(path.getParent());

        execute("-Epath.home=" + homeDir, path.toString());
        byte[] bytes = Files.readAllBytes(path);
        // TODO: maybe we should actually check the key is...i dunno...valid?
        assertEquals(SystemKeyTool.KEY_SIZE / 8, bytes.length);

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
        assertTrue(perms.toString(), perms.contains(PosixFilePermission.OWNER_READ));
        assertTrue(perms.toString(), perms.contains(PosixFilePermission.OWNER_WRITE));
        assertEquals(perms.toString(), 2, perms.size());
    }

    public void testGeneratePathInSettings() throws Exception {
        final Path homeDir = initFileSystem(false);

        Path xpackConf = homeDir.resolve("config").resolve(XPackField.NAME);
        Files.createDirectories(xpackConf);
        execute("-Epath.home=" + homeDir.toString());
        byte[] bytes = Files.readAllBytes(xpackConf.resolve("system_key"));
        assertEquals(SystemKeyTool.KEY_SIZE / 8, bytes.length);
    }

    public void testGenerateDefaultPath() throws Exception {
        final Path homeDir = initFileSystem(false);
        Path keyPath = homeDir.resolve("config/x-pack/system_key");
        Files.createDirectories(keyPath.getParent());
        execute("-Epath.home=" + homeDir.toString());
        byte[] bytes = Files.readAllBytes(keyPath);
        assertEquals(SystemKeyTool.KEY_SIZE / 8, bytes.length);
    }

    public void testThatSystemKeyMayOnlyBeReadByOwner() throws Exception {
        final Path homeDir = initFileSystem(true);

        Path path = jimfs.getPath(randomAlphaOfLength(10)).resolve("key");
        Files.createDirectories(path.getParent());

        execute("-Epath.home=" + homeDir, path.toString());
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
        assertTrue(perms.toString(), perms.contains(PosixFilePermission.OWNER_READ));
        assertTrue(perms.toString(), perms.contains(PosixFilePermission.OWNER_WRITE));
        assertEquals(perms.toString(), 2, perms.size());
    }

}
