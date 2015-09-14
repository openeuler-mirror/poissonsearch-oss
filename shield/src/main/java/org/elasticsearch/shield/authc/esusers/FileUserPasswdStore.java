/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc.esusers;

import com.google.common.collect.ImmutableMap;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.inject.internal.Nullable;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.shield.ShieldPlugin;
import org.elasticsearch.shield.authc.RealmConfig;
import org.elasticsearch.shield.authc.support.Hasher;
import org.elasticsearch.shield.authc.support.RefreshListener;
import org.elasticsearch.shield.authc.support.SecuredString;
import org.elasticsearch.shield.support.NoOpLogger;
import org.elasticsearch.shield.support.Validation;
import org.elasticsearch.watcher.FileChangesListener;
import org.elasticsearch.watcher.FileWatcher;
import org.elasticsearch.watcher.ResourceWatcherService;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.elasticsearch.shield.support.ShieldFiles.openAtomicMoveWriter;

/**
 *
 */
public class FileUserPasswdStore {

    private final ESLogger logger;

    private final Path file;
    final Hasher hasher = Hasher.BCRYPT;

    private volatile ImmutableMap<String, char[]> users;

    private CopyOnWriteArrayList<RefreshListener> listeners;

    public FileUserPasswdStore(RealmConfig config, ResourceWatcherService watcherService) {
        this(config, watcherService, null);
    }

    FileUserPasswdStore(RealmConfig config, ResourceWatcherService watcherService, RefreshListener listener) {
        logger = config.logger(FileUserPasswdStore.class);
        file = resolveFile(config.settings(), config.env());
        users = parseFileLenient(file, logger);
        if (users.isEmpty() && logger.isDebugEnabled()) {
            logger.debug("realm [esusers] has no users");
        }
        FileWatcher watcher = new FileWatcher(file.getParent());
        watcher.addListener(new FileListener());
        try {
            watcherService.add(watcher, ResourceWatcherService.Frequency.HIGH);
        } catch (IOException e) {
            throw new ElasticsearchException("failed to start watching users file [{}]", e, file.toAbsolutePath());
        }

        listeners = new CopyOnWriteArrayList<>();
        if (listener != null) {
            listeners.add(listener);
        }
    }

    void addListener(RefreshListener listener) {
        listeners.add(listener);
    }

    int usersCount() {
        return users.size();
    }

    public boolean verifyPassword(String username, SecuredString password) {
        if (users == null) {
            return false;
        }
        char[] hash = users.get(username);
        return hash != null && hasher.verify(password, hash);
    }

    public boolean userExists(String username) {
        return users != null && users.containsKey(username);
    }

    public static Path resolveFile(Settings settings, Environment env) {
        String location = settings.get("files.users");
        if (location == null) {
            return ShieldPlugin.resolveConfigFile(env, "users");
        }
        return env.binFile().getParent().resolve(location);
    }

    /**
     * Internally in this class, we try to load the file, but if for some reason we can't, we're being more lenient by
     * logging the error and skipping all users. This is aligned with how we handle other auto-loaded files in shield.
     */
    static ImmutableMap<String, char[]> parseFileLenient(Path path, ESLogger logger) {
        try {
            return parseFile(path, logger);
        } catch (Throwable t) {
            logger.error("failed to parse users file [{}]. skipping/removing all users...", t, path.toAbsolutePath());
            return ImmutableMap.of();
        }
    }

    /**
     * parses the esusers file. Should never return {@code null}, if the file doesn't exist an
     * empty map is returned
     */
    public static ImmutableMap<String, char[]> parseFile(Path path, @Nullable ESLogger logger) {
        if (logger == null) {
            logger = NoOpLogger.INSTANCE;
        }
        logger.trace("reading users file [{}]...", path.toAbsolutePath());

        if (!Files.exists(path)) {
            return ImmutableMap.of();
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            throw new IllegalStateException("could not read users file [" + path.toAbsolutePath() + "]", ioe);
        }

        ImmutableMap.Builder<String, char[]> users = ImmutableMap.builder();

        int lineNr = 0;
        for (String line : lines) {
            lineNr++;
            if (line.startsWith("#")) { // comment
                continue;
            }

            // only trim the line because we have a format, our tool generates the formatted text and we shouldn't be lenient
            // and allow spaces in the format
            line = line.trim();
            int i = line.indexOf(":");
            if (i <= 0 || i == line.length() - 1) {
                logger.error("invalid entry in users file [{}], line [{}]. skipping...", path.toAbsolutePath(), lineNr);
                continue;
            }
            String username = line.substring(0, i);
            Validation.Error validationError = Validation.ESUsers.validateUsername(username);
            if (validationError != null) {
                logger.error("invalid username [{}] in users file [{}], skipping... ({})", username, path.toAbsolutePath(), validationError);
                continue;
            }
            String hash = line.substring(i + 1);
            users.put(username, hash.toCharArray());
        }

        ImmutableMap<String, char[]> usersMap = users.build();
        if (usersMap.isEmpty()){
            logger.warn("no users found in users file [{}]. use bin/shield/esusers to add users and role mappings", path.toAbsolutePath());
        }
        return usersMap;
    }

    public static void writeFile(Map<String, char[]> esUsers, Path path) {
        try (PrintWriter writer = new PrintWriter(openAtomicMoveWriter(path))) {
            for (Map.Entry<String, char[]> entry : esUsers.entrySet()) {
                writer.printf(Locale.ROOT, "%s:%s%s", entry.getKey(), new String(entry.getValue()), System.lineSeparator());
            }
        } catch (IOException ioe) {
            throw new ElasticsearchException("could not write file [{}], please check file permissions", ioe, path.toAbsolutePath());
        }
    }

    protected void notifyRefresh() {
        for (RefreshListener listener : listeners) {
            listener.onRefresh();
        }
    }

    private class FileListener extends FileChangesListener {
        @Override
        public void onFileCreated(Path file) {
            onFileChanged(file);
        }

        @Override
        public void onFileDeleted(Path file) {
            onFileChanged(file);
        }

        @Override
        public void onFileChanged(Path file) {
            if (file.equals(FileUserPasswdStore.this.file)) {
                logger.info("users file [{}] changed. updating users... )", file.toAbsolutePath());
                users = parseFileLenient(file, logger);
                notifyRefresh();
            }
        }
    }
}
