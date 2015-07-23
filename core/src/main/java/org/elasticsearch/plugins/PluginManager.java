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

package org.elasticsearch.plugins;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;

import org.apache.lucene.util.IOUtils;
import org.elasticsearch.ElasticsearchTimeoutException;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.Version;
import org.elasticsearch.bootstrap.JarHell;
import org.elasticsearch.common.cli.Terminal;
import org.elasticsearch.common.http.client.HttpDownloadHelper;
import org.elasticsearch.common.io.FileSystemUtils;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.env.Environment;
import org.elasticsearch.plugins.PluginsService.Bundle;

import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.elasticsearch.common.Strings.hasLength;
import static org.elasticsearch.common.cli.Terminal.Verbosity.VERBOSE;
import static org.elasticsearch.common.io.FileSystemUtils.moveFilesWithoutOverwriting;

/**
 *
 */
public class PluginManager {

    public enum OutputMode {
        DEFAULT, SILENT, VERBOSE
    }

    private static final ImmutableSet<String> BLACKLIST = ImmutableSet.<String>builder()
            .add("elasticsearch",
                    "elasticsearch.bat",
                    "elasticsearch.in.sh",
                    "plugin",
                    "plugin.bat",
                    "service.bat").build();

    static final ImmutableSet<String> OFFICIAL_PLUGINS = ImmutableSet.<String>builder()
            .add(
                    "elasticsearch-analysis-icu",
                    "elasticsearch-analysis-kuromoji",
                    "elasticsearch-analysis-phonetic",
                    "elasticsearch-analysis-smartcn",
                    "elasticsearch-analysis-stempel",
                    "elasticsearch-cloud-aws",
                    "elasticsearch-cloud-azure",
                    "elasticsearch-cloud-gce",
                    "elasticsearch-delete-by-query",
                    "elasticsearch-lang-javascript",
                    "elasticsearch-lang-python"
            ).build();

    private final Environment environment;
    private String url;
    private OutputMode outputMode;
    private TimeValue timeout;

    public PluginManager(Environment environment, String url, OutputMode outputMode, TimeValue timeout) {
        this.environment = environment;
        this.url = url;
        this.outputMode = outputMode;
        this.timeout = timeout;
    }

    public void downloadAndExtract(String name, Terminal terminal) throws IOException {
        if (name == null) {
            throw new IllegalArgumentException("plugin name must be supplied with install [name].");
        }

        if (!Files.exists(environment.pluginsFile())) {
            terminal.println("Plugins directory [%s] does not exist. Creating...", environment.pluginsFile());
            Files.createDirectory(environment.pluginsFile());
        }

        if (!Files.isWritable(environment.pluginsFile())) {
            throw new IOException("plugin directory " + environment.pluginsFile() + " is read only");
        }

        PluginHandle pluginHandle = PluginHandle.parse(name);
        checkForForbiddenName(pluginHandle.name);

        Path pluginFile = download(pluginHandle, terminal);
        extract(pluginHandle, terminal, pluginFile);
    }

    private Path download(PluginHandle pluginHandle, Terminal terminal) throws IOException {
        Path pluginFile = pluginHandle.newDistroFile(environment);

        HttpDownloadHelper downloadHelper = new HttpDownloadHelper();
        boolean downloaded = false;
        HttpDownloadHelper.DownloadProgress progress;
        if (outputMode == OutputMode.SILENT) {
            progress = new HttpDownloadHelper.NullProgress();
        } else {
            progress = new HttpDownloadHelper.VerboseProgress(terminal.writer());
        }

        // first, try directly from the URL provided
        if (url != null) {
            URL pluginUrl = new URL(url);
            terminal.println("Trying %s ...", pluginUrl.toExternalForm());
            try {
                downloadHelper.download(pluginUrl, pluginFile, progress, this.timeout);
                downloaded = true;
            } catch (ElasticsearchTimeoutException e) {
                throw e;
            } catch (Exception e) {
                // ignore
                terminal.println("Failed: %s", ExceptionsHelper.detailedMessage(e));
            }
        } else {
            if (PluginHandle.isOfficialPlugin(pluginHandle.repo, pluginHandle.user, pluginHandle.version)) {
                checkForOfficialPlugins(pluginHandle.name);
            }
        }

        if (!downloaded) {
            // We try all possible locations
            for (URL url : pluginHandle.urls()) {
                terminal.println("Trying %s ...", url.toExternalForm());
                try {
                    downloadHelper.download(url, pluginFile, progress, this.timeout);
                    downloaded = true;
                    break;
                } catch (ElasticsearchTimeoutException e) {
                    throw e;
                } catch (Exception e) {
                    terminal.println(VERBOSE, "Failed: %s", ExceptionsHelper.detailedMessage(e));
                }
            }
        }

        if (!downloaded) {
            // try to cleanup what we downloaded
            IOUtils.deleteFilesIgnoringExceptions(pluginFile);
            throw new IOException("failed to download out of all possible locations..., use --verbose to get detailed information");
        }
        return pluginFile;
    }

    private void extract(PluginHandle pluginHandle, Terminal terminal, Path pluginFile) throws IOException {
        final Path extractLocation = pluginHandle.extractedDir(environment);
        if (Files.exists(extractLocation)) {
            throw new IOException("plugin directory " + extractLocation.toAbsolutePath() + " already exists. To update the plugin, uninstall it first using 'remove " + pluginHandle.name + "' command");
        }

        // unzip plugin to a staging temp dir, named for the plugin
        Path tmp = Files.createTempDirectory(environment.tmpFile(), null);
        Path root = tmp.resolve(pluginHandle.name); 
        unzipPlugin(pluginFile, root);

        // find the actual root (in case its unzipped with extra directory wrapping)
        root = findPluginRoot(root);

        // read and validate the plugin descriptor
        PluginInfo info = PluginInfo.readFromProperties(root);
        terminal.println("%s", info);

        // check for jar hell before any copying
        if (info.isJvm()) {
            jarHellCheck(root, info.isIsolated());
        }

        // install plugin
        FileSystemUtils.copyDirectoryRecursively(root, extractLocation);
        terminal.println("Installed %s into %s", pluginHandle.name, extractLocation.toAbsolutePath());

        // cleanup
        IOUtils.rm(tmp, pluginFile);

        // take care of bin/ by moving and applying permissions if needed
        Path binFile = extractLocation.resolve("bin");
        if (Files.isDirectory(binFile)) {
            Path toLocation = pluginHandle.binDir(environment);
            terminal.println(VERBOSE, "Found bin, moving to %s", toLocation.toAbsolutePath());
            if (Files.exists(toLocation)) {
                IOUtils.rm(toLocation);
            }
            try {
                FileSystemUtils.move(binFile, toLocation);
            } catch (IOException e) {
                throw new IOException("Could not move [" + binFile + "] to [" + toLocation + "]", e);
            }
            if (Files.getFileStore(toLocation).supportsFileAttributeView(PosixFileAttributeView.class)) {
                // add read and execute permissions to existing perms, so execution will work.
                // read should generally be set already, but set it anyway: don't rely on umask...
                final Set<PosixFilePermission> executePerms = new HashSet<>();
                executePerms.add(PosixFilePermission.OWNER_READ);
                executePerms.add(PosixFilePermission.GROUP_READ);
                executePerms.add(PosixFilePermission.OTHERS_READ);
                executePerms.add(PosixFilePermission.OWNER_EXECUTE);
                executePerms.add(PosixFilePermission.GROUP_EXECUTE);
                executePerms.add(PosixFilePermission.OTHERS_EXECUTE);
                Files.walkFileTree(toLocation, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (attrs.isRegularFile()) {
                            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
                            perms.addAll(executePerms);
                            Files.setPosixFilePermissions(file, perms);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                terminal.println(VERBOSE, "Skipping posix permissions - filestore doesn't support posix permission");
            }
            terminal.println(VERBOSE, "Installed %s into %s", pluginHandle.name, toLocation.toAbsolutePath());
        }

        Path configFile = extractLocation.resolve("config");
        if (Files.isDirectory(configFile)) {
            Path configDestLocation = pluginHandle.configDir(environment);
            terminal.println(VERBOSE, "Found config, moving to %s", configDestLocation.toAbsolutePath());
            moveFilesWithoutOverwriting(configFile, configDestLocation, ".new");
            terminal.println(VERBOSE, "Installed %s into %s", pluginHandle.name, configDestLocation.toAbsolutePath());
        }
    }

    /** we check whether we need to remove the top-level folder while extracting
     *  sometimes (e.g. github) the downloaded archive contains a top-level folder which needs to be removed
     */
    private Path findPluginRoot(Path dir) throws IOException {
        if (Files.exists(dir.resolve(PluginInfo.ES_PLUGIN_PROPERTIES))) {
            return dir;
        } else {
            final Path[] topLevelFiles = FileSystemUtils.files(dir);
            if (topLevelFiles.length == 1 && Files.isDirectory(topLevelFiles[0])) {
                Path subdir = topLevelFiles[0];
                if (Files.exists(subdir.resolve(PluginInfo.ES_PLUGIN_PROPERTIES))) {
                    return subdir;
                }
            }
        }
        throw new RuntimeException("Could not find plugin descriptor '" + PluginInfo.ES_PLUGIN_PROPERTIES + "' in plugin zip");
    }

    /** check a candidate plugin for jar hell before installing it */
    private void jarHellCheck(Path candidate, boolean isolated) throws IOException {
        // create list of current jars in classpath
        final List<URL> jars = new ArrayList<>();
        ClassLoader loader = PluginManager.class.getClassLoader();
        if (loader instanceof URLClassLoader) {
            Collections.addAll(jars, ((URLClassLoader) loader).getURLs());
        }

        // read existing bundles. this does some checks on the installation too.
        List<Bundle> bundles = PluginsService.getPluginBundles(environment);

        // if we aren't isolated, we need to jarhellcheck against any other non-isolated plugins
        // thats always the first bundle
        if (isolated == false) {
            jars.addAll(bundles.get(0).urls);
        }

        // add plugin jars to the list
        Path pluginJars[] = FileSystemUtils.files(candidate, "*.jar");
        for (Path jar : pluginJars) {
            jars.add(jar.toUri().toURL());
        }

        // check combined (current classpath + new jars to-be-added)
        try {
            JarHell.checkJarHell(jars.toArray(new URL[jars.size()]));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private void unzipPlugin(Path zip, Path target) throws IOException {
        Files.createDirectories(target);
        
        try (ZipInputStream zipInput = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zipInput.getNextEntry()) != null) {
                Path targetFile = target.resolve(entry.getName());

                // be on the safe side: do not rely on that directories are always extracted
                // before their children (although this makes sense, but is it guaranteed?)
                Files.createDirectories(targetFile.getParent());
                if (entry.isDirectory() == false) {
                    try (OutputStream out = Files.newOutputStream(targetFile)) {
                        int len;
                        while((len = zipInput.read(buffer)) >= 0) {
                            out.write(buffer, 0, len);
                        }
                    }
                }
                zipInput.closeEntry();
            }
        }
    }

    public void removePlugin(String name, Terminal terminal) throws IOException {
        if (name == null) {
            throw new IllegalArgumentException("plugin name must be supplied with remove [name].");
        }
        PluginHandle pluginHandle = PluginHandle.parse(name);
        boolean removed = false;

        checkForForbiddenName(pluginHandle.name);
        Path pluginToDelete = pluginHandle.extractedDir(environment);
        if (Files.exists(pluginToDelete)) {
            terminal.println(VERBOSE, "Removing: %s", pluginToDelete);
            try {
                IOUtils.rm(pluginToDelete);
            } catch (IOException ex){
                throw new IOException("Unable to remove " + pluginHandle.name + ". Check file permissions on " +
                        pluginToDelete.toString(), ex);
            }
            removed = true;
        }
        Path binLocation = pluginHandle.binDir(environment);
        if (Files.exists(binLocation)) {
            terminal.println(VERBOSE, "Removing: %s", binLocation);
            try {
                IOUtils.rm(binLocation);
            } catch (IOException ex){
                throw new IOException("Unable to remove " + pluginHandle.name + ". Check file permissions on " +
                        binLocation.toString(), ex);
            }
            removed = true;
        }

        if (removed) {
            terminal.println("Removed %s", name);
        } else {
            terminal.println("Plugin %s not found. Run plugin --list to get list of installed plugins.", name);
        }
    }

    private static void checkForForbiddenName(String name) {
        if (!hasLength(name) || BLACKLIST.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Illegal plugin name: " + name);
        }
    }

    protected static void checkForOfficialPlugins(String name) {
        // We make sure that users can use only new short naming for official plugins only
        if (!OFFICIAL_PLUGINS.contains(name)) {
            throw new IllegalArgumentException(name +
                    " is not an official plugin so you should install it using elasticsearch/" +
                    name + "/latest naming form.");
        }
    }

    public Path[] getListInstalledPlugins() throws IOException {
        if (!Files.exists(environment.pluginsFile())) {
            return new Path[0];
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(environment.pluginsFile())) {
            return Iterators.toArray(stream.iterator(), Path.class);
        }
    }

    public void listInstalledPlugins(Terminal terminal) throws IOException {
        Path[] plugins = getListInstalledPlugins();
        terminal.println("Installed plugins in %s:", environment.pluginsFile().toAbsolutePath());
        if (plugins == null || plugins.length == 0) {
            terminal.println("    - No plugin detected");
        } else {
            for (Path plugin : plugins) {
                terminal.println("    - " + plugin.getFileName());
            }
        }
    }

    /**
     * Helper class to extract properly user name, repository name, version and plugin name
     * from plugin name given by a user.
     */
    static class PluginHandle {

        final String name;
        final String version;
        final String user;
        final String repo;

        PluginHandle(String name, String version, String user, String repo) {
            this.name = name;
            this.version = version;
            this.user = user;
            this.repo = repo;
        }

        List<URL> urls() {
            List<URL> urls = new ArrayList<>();
            if (version != null) {
                // Elasticsearch new download service uses groupId org.elasticsearch.plugins from 2.0.0
                if (user == null) {
                    // TODO Update to https
                    addUrl(urls, String.format(Locale.ROOT, "http://download.elastic.co/org.elasticsearch.plugins/%1$s/%1$s-%2$s.zip", repo, version));
                } else {
                    // Elasticsearch old download service
                    // TODO Update to https
                    addUrl(urls, String.format(Locale.ROOT, "http://download.elastic.co/%1$s/%2$s/%2$s-%3$s.zip", user, repo, version));
                    // Maven central repository
                    addUrl(urls, String.format(Locale.ROOT, "http://search.maven.org/remotecontent?filepath=%1$s/%2$s/%3$s/%2$s-%3$s.zip", user.replace('.', '/'), repo, version));
                    // Sonatype repository
                    addUrl(urls, String.format(Locale.ROOT, "https://oss.sonatype.org/service/local/repositories/releases/content/%1$s/%2$s/%3$s/%2$s-%3$s.zip", user.replace('.', '/'), repo, version));
                    // Github repository
                    addUrl(urls, String.format(Locale.ROOT, "https://github.com/%1$s/%2$s/archive/%3$s.zip", user, repo, version));
                }
            }
            if (user != null) {
                // Github repository for master branch (assume site)
                addUrl(urls, String.format(Locale.ROOT, "https://github.com/%1$s/%2$s/archive/master.zip", user, repo));
            }
            return urls;
        }

        private static void addUrl(List<URL> urls, String url) {
            try {
                urls.add(new URL(url));
            } catch (MalformedURLException e) {
                // We simply ignore malformed URL
            }
        }

        Path newDistroFile(Environment env) throws IOException {
            return Files.createTempFile(env.tmpFile(), name, ".zip");
        }

        Path extractedDir(Environment env) {
            return env.pluginsFile().resolve(name);
        }

        Path binDir(Environment env) {
            return env.homeFile().resolve("bin").resolve(name);
        }

        Path configDir(Environment env) {
            return env.configFile().resolve(name);
        }

        static PluginHandle parse(String name) {
            String[] elements = name.split("/");
            // We first consider the simplest form: pluginname
            String repo = elements[0];
            String user = null;
            String version = null;

            // We consider the form: username/pluginname
            if (elements.length > 1) {
                user = elements[0];
                repo = elements[1];

                // We consider the form: username/pluginname/version
                if (elements.length > 2) {
                    version = elements[2];
                }
            }

            String endname = repo;
            if (repo.startsWith("elasticsearch-")) {
                // remove elasticsearch- prefix
                endname = repo.substring("elasticsearch-".length());
            } else if (repo.startsWith("es-")) {
                // remove es- prefix
                endname = repo.substring("es-".length());
            }

            if (isOfficialPlugin(repo, user, version)) {
                return new PluginHandle(endname, Version.CURRENT.number(), null, repo);
            }

            return new PluginHandle(endname, version, user, repo);
        }

        static boolean isOfficialPlugin(String repo, String user, String version) {
            return version == null && user == null && !Strings.isNullOrEmpty(repo);
        }
    }

}
