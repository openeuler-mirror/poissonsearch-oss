/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc.esusers;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.base.Charsets;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.internal.Nullable;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.shield.authc.support.Hasher;
import org.elasticsearch.shield.authc.support.UserPasswdStore;
import org.elasticsearch.shield.plugin.SecurityPlugin;
import org.elasticsearch.watcher.FileChangesListener;
import org.elasticsearch.watcher.FileWatcher;
import org.elasticsearch.watcher.ResourceWatcherService;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 *
 */
public class FileUserPasswdStore extends AbstractComponent implements UserPasswdStore {

    private final Path file;
    private final FileWatcher watcher;
    final Hasher hasher = Hasher.HTPASSWD;

    private volatile ImmutableMap<String, char[]> esUsers;

    private final Listener listener;

    @Inject
    public FileUserPasswdStore(Settings settings, Environment env, ResourceWatcherService watcherService) {
        this(settings, env, watcherService, Listener.NOOP);
    }

    FileUserPasswdStore(Settings settings, Environment env, ResourceWatcherService watcherService, Listener listener) {
        super(settings);
        file = resolveFile(settings, env);
        esUsers = parseFile(file, logger);
        watcher = new FileWatcher(file.getParent().toFile());
        watcher.addListener(new FileListener());
        watcherService.add(watcher);
        this.listener = listener;
    }

    @Override
    public boolean verifyPassword(String username, char[] password) {
        if (esUsers == null) {
            return false;
        }
        char[] hash = esUsers.get(username);
        if (hash == null) {
            return false;
        }
        return hasher.verify(password, hash);
    }

    public static Path resolveFile(Settings settings, Environment env) {
        String location = settings.get("shield.authc.esusers.files.users");
        if (location == null) {
            File shieldDirectory = new File(env.configFile(), SecurityPlugin.NAME);
            return shieldDirectory.toPath().resolve(".users");
        }
        return Paths.get(location);
    }

    /**
     * parses the esusers file. Should never return {@code null}, if the file doesn't exist an
     * empty map is returned
     */
    public static ImmutableMap<String, char[]> parseFile(Path path, @Nullable ESLogger logger) {
        if (logger != null) {
            logger.trace("Reading users file located at [{}]", path);
        }
        if (!Files.exists(path)) {
            return ImmutableMap.of();
        }

        List<String> lines = null;
        try {
            lines = Files.readAllLines(path, Charsets.UTF_8);
        } catch (IOException ioe) {
            throw new ElasticsearchException("Could not read users file [" + path.toAbsolutePath() + "]", ioe);
        }

        ImmutableMap.Builder<String, char[]> users = ImmutableMap.builder();

        int lineNr = 0;
        for (String line : lines) {
            lineNr++;
            int i = line.indexOf(":");
            if (i <= 0 || i == line.length() - 1) {
                if (logger != null) {
                    logger.error("Invalid entry in users file [" + path.toAbsolutePath() + "], line [" + lineNr + "]. Skipping...");
                }
                continue;
            }
            String username = line.substring(0, i).trim();
            String hash = line.substring(i + 1).trim();
            users.put(username, hash.toCharArray());
        }

        return users.build();
    }

    public static void writeFile(Map<String, char[]> esUsers, Path path) {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(path, Charsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))) {
            for (Map.Entry<String, char[]> entry : esUsers.entrySet()) {
                writer.printf(Locale.ROOT, "%s:%s%s", entry.getKey(), new String(entry.getValue()), System.lineSeparator());
            }
        } catch (IOException ioe) {
            throw new ElasticsearchException("Could not write users file [" + path.toAbsolutePath() + "], please check file permissions", ioe);
        }
    }

    private class FileListener extends FileChangesListener {
        @Override
        public void onFileCreated(File file) {
            if (file.equals(FileUserPasswdStore.this.file.toFile())) {
                esUsers = parseFile(file.toPath(), logger);
                listener.onRefresh();
            }
        }

        @Override
        public void onFileDeleted(File file) {
            if (file.equals(FileUserPasswdStore.this.file.toFile())) {
                esUsers = ImmutableMap.of();
                listener.onRefresh();
            }
        }

        @Override
        public void onFileChanged(File file) {
            if (file.equals(FileUserPasswdStore.this.file.toFile())) {
                esUsers = parseFile(file.toPath(), logger);
                listener.onRefresh();
            }
        }
    }

    public static interface Listener {

        final Listener NOOP = new Listener() {
            @Override
            public void onRefresh() {
            }
        };

        void onRefresh();
    }
}
