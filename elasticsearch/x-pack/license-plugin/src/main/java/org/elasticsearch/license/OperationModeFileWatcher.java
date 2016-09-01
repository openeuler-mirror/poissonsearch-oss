/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license;


import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.util.Supplier;
import org.elasticsearch.license.License.OperationMode;
import org.elasticsearch.watcher.FileChangesListener;
import org.elasticsearch.watcher.FileWatcher;
import org.elasticsearch.watcher.ResourceWatcherService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * File based watcher for license {@link OperationMode}
 * Watches for changes in <code>licenseModePath</code>, use
 * {@link #getCurrentOperationMode()} to access the latest mode
 *
 * In case of failure to read a valid operation mode from <code>licenseModePath</code>,
 * the operation mode will default to PLATINUM
 */
public final class OperationModeFileWatcher implements FileChangesListener {
    private final ResourceWatcherService resourceWatcherService;
    private final Path licenseModePath;
    private final AtomicBoolean initialized = new AtomicBoolean();
    private final OperationMode defaultOperationMode = OperationMode.PLATINUM;
    private volatile OperationMode currentOperationMode = defaultOperationMode;
    private final Logger logger;
    private final Runnable onChange;

    public OperationModeFileWatcher(ResourceWatcherService resourceWatcherService, Path licenseModePath,
                                    Logger logger, Runnable onChange) {
        this.resourceWatcherService = resourceWatcherService;
        this.licenseModePath = licenseModePath;
        this.logger = logger;
        this.onChange = onChange;
    }

    public void init() {
        if (initialized.compareAndSet(false, true)) {
            final FileWatcher watcher = new FileWatcher(licenseModePath);
            watcher.addListener(this);
            try {
                resourceWatcherService.add(watcher, ResourceWatcherService.Frequency.HIGH);
                if (Files.exists(licenseModePath)) {
                    onChange(licenseModePath);
                }
            } catch (IOException e) {
                logger.error("couldn't initialize watching license mode file", e);
            }
        }
    }

    /**
     * Returns the current operation mode based on license mode file.
     * Defaults to {@link OperationMode#PLATINUM}
     */
    public OperationMode getCurrentOperationMode() {
        return currentOperationMode;
    }

    @Override
    public void onFileInit(Path file) {
        onChange(file);
    }

    @Override
    public void onFileCreated(Path file) {
        onChange(file);
    }

    @Override
    public void onFileDeleted(Path file) {
        onChange(file);
    }

    @Override
    public void onFileChanged(Path file) {
        onChange(file);
    }

    private synchronized void onChange(Path file) {
        if (file.equals(licenseModePath)) {
            currentOperationMode = defaultOperationMode;
            if (Files.exists(licenseModePath)
                    && Files.isReadable(licenseModePath)) {
                final byte[] content;
                try {
                    content = Files.readAllBytes(licenseModePath);
                } catch (IOException e) {
                    logger.error(
                            (Supplier<?>) () -> new ParameterizedMessage(
                                    "couldn't read operation mode from [{}]", licenseModePath.toAbsolutePath()), e);
                    return;
                }
                String operationMode = new String(content, StandardCharsets.UTF_8);
                try {
                    currentOperationMode = OperationMode.resolve(operationMode);
                } catch (IllegalArgumentException e) {
                    logger.error(
                            (Supplier<?>) () -> new ParameterizedMessage(
                                    "invalid operation mode in [{}]", licenseModePath.toAbsolutePath()), e);
                    return;
                }
            }
            onChange.run();
        }
    }
}

