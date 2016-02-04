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

import org.apache.lucene.util.IOUtils;
import org.elasticsearch.Build;
import org.elasticsearch.Version;
import org.elasticsearch.bootstrap.JarHell;
import org.elasticsearch.common.cli.CliTool;
import org.elasticsearch.common.cli.Terminal;
import org.elasticsearch.common.cli.UserError;
import org.elasticsearch.common.hash.MessageDigests;
import org.elasticsearch.common.io.FileSystemUtils;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.util.Collections.unmodifiableSet;
import static org.elasticsearch.common.cli.Terminal.Verbosity.VERBOSE;
import static org.elasticsearch.common.util.set.Sets.newHashSet;

/**
 * A command for the plugin cli to install a plugin into elasticsearch.
 *
 * The install command takes a plugin id, which may be any of the following:
 * <ul>
 *     <li>An official elasticsearch plugin name</li>
 *     <li>Maven coordinates to a plugin zip</li>
 *     <li>A URL to a plugin zip</li>
 * </ul>
 *
 * Plugins are packaged as zip files. Each packaged plugin must contain a
 * plugin properties file. See {@link PluginInfo}.
 * <p>
 * The installation process first extracts the plugin files into a temporary
 * directory in order to verify the plugin satisfies the following requirements:
 * <ul>
 *     <li>Jar hell does not exist, either between the plugin's own jars, or with elasticsearch</li>
 *     <li>The plugin is not a module already provided with elasticsearch</li>
 *     <li>If the plugin contains extra security permissions, the policy file is validated</li>
 * </ul>
 * <p>
 * A plugin may also contain an optional {@code bin} directory which contains scripts. The
 * scripts will be installed into a subdirectory of the elasticsearch bin directory, using
 * the name of the plugin, and the scripts will be marked executable.
 * <p>
 * A plugin may also contain an optional {@code config} directory which contains configuration
 * files specific to the plugin. The config files be installed into a subdirectory of the
 * elasticsearch config directory, using the name of the plugin. If any files to be installed
 * already exist, they will be skipped.
 */
class InstallPluginCommand extends CliTool.Command {

    private static final String PROPERTY_SUPPORT_STAGING_URLS = "es.plugins.staging";

    // TODO: make this a resource file generated by gradle
    static final Set<String> MODULES = unmodifiableSet(newHashSet(
        "lang-expression",
        "lang-groovy"));

    // TODO: make this a resource file generated by gradle
    static final Set<String> OFFICIAL_PLUGINS = unmodifiableSet(newHashSet(
        "analysis-icu",
        "analysis-kuromoji",
        "analysis-phonetic",
        "analysis-smartcn",
        "analysis-stempel",
        "delete-by-query",
        "discovery-azure",
        "discovery-ec2",
        "discovery-gce",
        "lang-javascript",
        "lang-painless",
        "lang-python",
        "mapper-attachments",
        "mapper-murmur3",
        "mapper-size",
        "repository-azure",
        "repository-hdfs",
        "repository-s3",
        "store-smb"));

    private final String pluginId;
    private final boolean batch;

    InstallPluginCommand(Terminal terminal, String pluginId, boolean batch) {
        super(terminal);
        this.pluginId = pluginId;
        this.batch = batch;
    }

    @Override
    public CliTool.ExitStatus execute(Settings settings, Environment env) throws Exception {

        // TODO: remove this leniency!! is it needed anymore?
        if (Files.exists(env.pluginsFile()) == false) {
            terminal.println("Plugins directory [" + env.pluginsFile() + "] does not exist. Creating...");
            Files.createDirectory(env.pluginsFile());
        }

        Path pluginZip = download(pluginId, env.tmpFile());
        Path extractedZip = unzip(pluginZip, env.pluginsFile());
        install(extractedZip, env);

        return CliTool.ExitStatus.OK;
    }

    /** Downloads the plugin and returns the file it was downloaded to. */
    private Path download(String pluginId, Path tmpDir) throws Exception {
        if (OFFICIAL_PLUGINS.contains(pluginId)) {
            final String version = Version.CURRENT.toString();
            final String url;
            if (System.getProperty(PROPERTY_SUPPORT_STAGING_URLS, "false").equals("true")) {
                url = String.format(Locale.ROOT, "https://download.elastic.co/elasticsearch/staging/%1$s-%2$s/org/elasticsearch/plugin/%3$s/%1$s/%3$s-%1$s.zip",
                    version, Build.CURRENT.shortHash(), pluginId);
            } else {
                url = String.format(Locale.ROOT, "https://download.elastic.co/elasticsearch/release/org/elasticsearch/plugin/%1$s/%2$s/%1$s-%2$s.zip",
                    pluginId, version);
            }
            terminal.println("-> Downloading " + pluginId + " from elastic");
            return downloadZipAndChecksum(url, tmpDir);
        }

        // now try as maven coordinates, a valid URL would only have a colon and slash
        String[] coordinates = pluginId.split(":");
        if (coordinates.length == 3 && pluginId.contains("/") == false) {
            String mavenUrl = String.format(Locale.ROOT, "https://repo1.maven.org/maven2/%1$s/%2$s/%3$s/%2$s-%3$s.zip",
                coordinates[0].replace(".", "/") /* groupId */, coordinates[1] /* artifactId */, coordinates[2] /* version */);
            terminal.println("-> Downloading " + pluginId + " from maven central");
            return downloadZipAndChecksum(mavenUrl, tmpDir);
        }

        // fall back to plain old URL
        terminal.println("-> Downloading " + URLDecoder.decode(pluginId, "UTF-8"));
        return downloadZip(pluginId, tmpDir);
    }

    /** Downloads a zip from the url, into a temp file under the given temp dir. */
    private Path downloadZip(String urlString, Path tmpDir) throws IOException {
        URL url = new URL(urlString);
        Path zip = Files.createTempFile(tmpDir, null, ".zip");
        try (InputStream in = url.openStream()) {
            // must overwrite since creating the temp file above actually created the file
            Files.copy(in, zip, StandardCopyOption.REPLACE_EXISTING);
        }
        return zip;
    }

    /** Downloads a zip from the url, as well as a SHA1 checksum, and checks the checksum. */
    private Path downloadZipAndChecksum(String urlString, Path tmpDir) throws Exception {
        Path zip = downloadZip(urlString, tmpDir);

        URL checksumUrl = new URL(urlString + ".sha1");
        final String expectedChecksum;
        try (InputStream in = checksumUrl.openStream()) {
            BufferedReader checksumReader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            expectedChecksum = checksumReader.readLine();
            if (checksumReader.readLine() != null) {
                throw new UserError(CliTool.ExitStatus.IO_ERROR, "Invalid checksum file at " + checksumUrl);
            }
        }

        byte[] zipbytes = Files.readAllBytes(zip);
        String gotChecksum = MessageDigests.toHexString(MessageDigests.sha1().digest(zipbytes));
        if (expectedChecksum.equals(gotChecksum) == false) {
            throw new UserError(CliTool.ExitStatus.IO_ERROR, "SHA1 mismatch, expected " + expectedChecksum + " but got " + gotChecksum);
        }

        return zip;
    }

    private Path unzip(Path zip, Path pluginsDir) throws IOException {
        // unzip plugin to a staging temp dir
        Path target = Files.createTempDirectory(pluginsDir, ".installing-");
        Files.createDirectories(target);

        // TODO: we should wrap this in a try/catch and try deleting the target dir on failure?
        try (ZipInputStream zipInput = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zipInput.getNextEntry()) != null) {
                Path targetFile = target.resolve(entry.getName());
                // TODO: handle name being an absolute path

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
        Files.delete(zip);
        return target;
    }

    /** Load information about the plugin, and verify it can be installed with no errors. */
    private PluginInfo verify(Path pluginRoot, Environment env) throws Exception {
        // read and validate the plugin descriptor
        PluginInfo info = PluginInfo.readFromProperties(pluginRoot);
        terminal.println(VERBOSE, info.toString());

        // don't let luser install plugin as a module...
        // they might be unavoidably in maven central and are packaged up the same way)
        if (MODULES.contains(info.getName())) {
            throw new UserError(CliTool.ExitStatus.USAGE, "plugin '" + info.getName() + "' cannot be installed like this, it is a system module");
        }

        // check for jar hell before any copying
        jarHellCheck(pluginRoot, env.pluginsFile(), info.isIsolated());

        // read optional security policy (extra permissions)
        // if it exists, confirm or warn the user
        Path policy = pluginRoot.resolve(PluginInfo.ES_PLUGIN_POLICY);
        if (Files.exists(policy)) {
            PluginSecurity.readPolicy(policy, terminal, env, batch);
        }

        return info;
    }

    /** check a candidate plugin for jar hell before installing it */
    private void jarHellCheck(Path candidate, Path pluginsDir, boolean isolated) throws Exception {
        // create list of current jars in classpath
        final List<URL> jars = new ArrayList<>();
        jars.addAll(Arrays.asList(JarHell.parseClassPath()));

        // read existing bundles. this does some checks on the installation too.
        List<PluginsService.Bundle> bundles = PluginsService.getPluginBundles(pluginsDir);

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
        // TODO: no jars should be an error
        // TODO: verify the classname exists in one of the jars!

        // check combined (current classpath + new jars to-be-added)
        JarHell.checkJarHell(jars.toArray(new URL[jars.size()]));
    }

    /**
     * Installs the plugin from {@code tmpRoot} into the plugins dir.
     * If the plugin has a bin dir and/or a config dir, those are copied.
     */
    private void install(Path tmpRoot, Environment env) throws Exception {
        List<Path> deleteOnFailure = new ArrayList<>();
        deleteOnFailure.add(tmpRoot);

        try {
            PluginInfo info = verify(tmpRoot, env);

            final Path destination = env.pluginsFile().resolve(info.getName());
            if (Files.exists(destination)) {
                throw new UserError(CliTool.ExitStatus.USAGE, "plugin directory " + destination.toAbsolutePath() + " already exists. To update the plugin, uninstall it first using 'remove " + info.getName() + "' command");
            }

            Path tmpBinDir = tmpRoot.resolve("bin");
            if (Files.exists(tmpBinDir)) {
                Path destBinDir = env.binFile().resolve(info.getName());
                deleteOnFailure.add(destBinDir);
                installBin(info, tmpBinDir, destBinDir);
            }

            Path tmpConfigDir = tmpRoot.resolve("config");
            if (Files.exists(tmpConfigDir)) {
                // some files may already exist, and we don't remove plugin config files on plugin removal,
                // so any installed config files are left on failure too
                installConfig(info, tmpConfigDir, env.configFile().resolve(info.getName()));
            }

            Files.move(tmpRoot, destination, StandardCopyOption.ATOMIC_MOVE);
            terminal.println("-> Installed " + info.getName());

        } catch (Exception installProblem) {
            try {
                IOUtils.rm(deleteOnFailure.toArray(new Path[0]));
            } catch (IOException exceptionWhileRemovingFiles) {
                installProblem.addSuppressed(exceptionWhileRemovingFiles);
            }
            throw installProblem;
        }
    }

    /** Copies the files from {@code tmpBinDir} into {@code destBinDir}, along with permissions from dest dirs parent. */
    private void installBin(PluginInfo info, Path tmpBinDir, Path destBinDir) throws Exception {
        if (Files.isDirectory(tmpBinDir) == false) {
            throw new UserError(CliTool.ExitStatus.IO_ERROR, "bin in plugin " + info.getName() + " is not a directory");
        }
        Files.createDirectory(destBinDir);

        // setup file attributes for the installed files to those of the parent dir
        Set<PosixFilePermission> perms = new HashSet<>();
        PosixFileAttributeView binAttrs = Files.getFileAttributeView(destBinDir.getParent(), PosixFileAttributeView.class);
        if (binAttrs != null) {
            perms = new HashSet<>(binAttrs.readAttributes().permissions());
            // setting execute bits, since this just means "the file is executable", and actual execution requires read
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
        }

        try (DirectoryStream<Path> stream  = Files.newDirectoryStream(tmpBinDir)) {
            for (Path srcFile : stream) {
                if (Files.isDirectory(srcFile)) {
                    throw new UserError(CliTool.ExitStatus.DATA_ERROR, "Directories not allowed in bin dir for plugin " + info.getName() + ", found " + srcFile.getFileName());
                }

                Path destFile = destBinDir.resolve(tmpBinDir.relativize(srcFile));
                Files.copy(srcFile, destFile);

                if (perms.isEmpty() == false) {
                    PosixFileAttributeView view = Files.getFileAttributeView(destFile, PosixFileAttributeView.class);
                    view.setPermissions(perms);
                }
            }
        }
        IOUtils.rm(tmpBinDir); // clean up what we just copied
    }

    /**
     * Copies the files from {@code tmpConfigDir} into {@code destConfigDir}.
     * Any files existing in both the source and destination will be skipped.
     */
    private void installConfig(PluginInfo info, Path tmpConfigDir, Path destConfigDir) throws Exception {
        if (Files.isDirectory(tmpConfigDir) == false) {
            throw new UserError(CliTool.ExitStatus.IO_ERROR, "config in plugin " + info.getName() + " is not a directory");
        }

        // create the plugin's config dir "if necessary"
        Files.createDirectories(destConfigDir);

        try (DirectoryStream<Path> stream  = Files.newDirectoryStream(tmpConfigDir)) {
            for (Path srcFile : stream) {
                if (Files.isDirectory(srcFile)) {
                    throw new UserError(CliTool.ExitStatus.DATA_ERROR, "Directories not allowed in config dir for plugin " + info.getName());
                }

                Path destFile = destConfigDir.resolve(tmpConfigDir.relativize(srcFile));
                if (Files.exists(destFile) == false) {
                    Files.copy(srcFile, destFile);
                }
            }
        }
        IOUtils.rm(tmpConfigDir); // clean up what we just copied
    }
}
