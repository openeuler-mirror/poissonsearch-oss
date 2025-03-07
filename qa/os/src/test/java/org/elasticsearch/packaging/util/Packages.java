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

package org.elasticsearch.packaging.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.packaging.util.Shell.Result;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Collections.singletonList;
import static org.elasticsearch.packaging.util.FileExistenceMatchers.fileDoesNotExist;
import static org.elasticsearch.packaging.util.FileMatcher.Fileness.Directory;
import static org.elasticsearch.packaging.util.FileMatcher.Fileness.File;
import static org.elasticsearch.packaging.util.FileMatcher.file;
import static org.elasticsearch.packaging.util.FileMatcher.p644;
import static org.elasticsearch.packaging.util.FileMatcher.p660;
import static org.elasticsearch.packaging.util.FileMatcher.p750;
import static org.elasticsearch.packaging.util.FileMatcher.p755;
import static org.elasticsearch.packaging.util.Platforms.isSysVInit;
import static org.elasticsearch.packaging.util.Platforms.isSystemd;
import static org.elasticsearch.packaging.util.ServerUtils.waitForElasticsearch;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Packages {

    private static final Logger logger = LogManager.getLogger(Packages.class);

    public static final Path SYSVINIT_SCRIPT = Paths.get("/etc/init.d/poissonsearch");
    public static final Path SYSTEMD_SERVICE = Paths.get("/usr/lib/systemd/system/poissonsearch.service");

    public static void assertInstalled(Distribution distribution) throws Exception {
        final Result status = packageStatus(distribution);
        assertThat(status.exitCode, is(0));

        Platforms.onDPKG(() -> assertFalse(Pattern.compile("(?m)^Status:.+deinstall ok").matcher(status.stdout).find()));
    }

    public static void assertRemoved(Distribution distribution) throws Exception {
        final Result status = packageStatus(distribution);

        Platforms.onRPM(() -> assertThat(status.exitCode, is(1)));

        Platforms.onDPKG(() -> {
            assertThat(status.exitCode, anyOf(is(0), is(1)));
            if (status.exitCode == 0) {
                assertTrue(
                    "an uninstalled status should be indicated: " + status.stdout,
                    Pattern.compile("(?m)^Status:.+deinstall ok").matcher(status.stdout).find()
                        || Pattern.compile("(?m)^Status:.+ok not-installed").matcher(status.stdout).find()
                );
            }
        });
    }

    public static Result packageStatus(Distribution distribution) {
        logger.info("Package type: " + distribution.packaging);
        return runPackageManager(distribution, new Shell(), PackageManagerCommand.QUERY);
    }

    public static Installation installPackage(Shell sh, Distribution distribution) throws IOException {
        String systemJavaHome = sh.run("echo $SYSTEM_JAVA_HOME").stdout.trim();
        if (distribution.hasJdk == false) {
            sh.getEnv().put("JAVA_HOME", systemJavaHome);
        }
        final Result result = runPackageManager(distribution, sh, PackageManagerCommand.INSTALL);
        if (result.exitCode != 0) {
            throw new RuntimeException("Installing distribution " + distribution + " failed: " + result);
        }

        Installation installation = Installation.ofPackage(sh, distribution);

        if (distribution.hasJdk == false) {
            Files.write(installation.envFile, singletonList("JAVA_HOME=" + systemJavaHome), StandardOpenOption.APPEND);
        }
        return installation;
    }

    public static Installation upgradePackage(Shell sh, Distribution distribution) throws IOException {
        final Result result = runPackageManager(distribution, sh, PackageManagerCommand.UPGRADE);
        if (result.exitCode != 0) {
            throw new RuntimeException("Upgrading distribution " + distribution + " failed: " + result);
        }

        return Installation.ofPackage(sh, distribution);
    }

    public static Installation forceUpgradePackage(Shell sh, Distribution distribution) throws IOException {
        final Result result = runPackageManager(distribution, sh, PackageManagerCommand.FORCE_UPGRADE);
        if (result.exitCode != 0) {
            throw new RuntimeException("Force upgrading distribution " + distribution + " failed: " + result);
        }

        return Installation.ofPackage(sh, distribution);
    }

    private static Result runPackageManager(Distribution distribution, Shell sh, PackageManagerCommand command) {
        final String distributionArg = command == PackageManagerCommand.QUERY || command == PackageManagerCommand.REMOVE
            ? distribution.flavor.name
            : distribution.path.toString();

        if (Platforms.isRPM()) {
            String rpmOptions = RPM_OPTIONS.get(command);
            return sh.runIgnoreExitCode("rpm " + rpmOptions + " " + distributionArg);
        } else {
            String debOptions = DEB_OPTIONS.get(command);
            Result r = sh.runIgnoreExitCode("dpkg " + debOptions + " " + distributionArg);
            if (r.exitCode != 0) {
                Result lockOF = sh.runIgnoreExitCode("lsof /var/lib/dpkg/lock");
                if (lockOF.exitCode == 0) {
                    throw new RuntimeException("dpkg failed and the lockfile still exists. " + "Failure:\n" + r + "\nLockfile:\n" + lockOF);
                }
            }
            return r;
        }
    }

    public static void remove(Distribution distribution) throws Exception {
        final Shell sh = new Shell();
        Result result = runPackageManager(distribution, sh, PackageManagerCommand.REMOVE);
        assertThat(result.toString(), result.isSuccess(), is(true));

        Platforms.onRPM(() -> {
            final Result status = packageStatus(distribution);
            assertThat(status.exitCode, is(1));
        });

        Platforms.onDPKG(() -> {
            final Result status = packageStatus(distribution);
            assertThat(status.exitCode, is(0));
            assertTrue(Pattern.compile("(?m)^Status:.+deinstall ok").matcher(status.stdout).find());
        });
    }

    public static void verifyPackageInstallation(Installation installation, Distribution distribution, Shell sh) {
        verifyOssInstallation(installation, distribution, sh);
        if (distribution.flavor == Distribution.Flavor.DEFAULT) {
            verifyDefaultInstallation(installation, distribution);
        }
    }

    private static void verifyOssInstallation(Installation es, Distribution distribution, Shell sh) {

        sh.run("id poissonsearch");
        sh.run("getent group poissonsearch");

        final Result passwdResult = sh.run("getent passwd poissonsearch");
        final Path homeDir = Paths.get(passwdResult.stdout.trim().split(":")[5]);
        assertThat("poissonsearch user home directory must not exist", homeDir, fileDoesNotExist());

        Stream.of(es.home, es.plugins, es.modules).forEach(dir -> assertThat(dir, file(Directory, "root", "root", p755)));

        Stream.of(es.data, es.logs).forEach(dir -> assertThat(dir, file(Directory, "poissonsearch", "poissonsearch", p750)));

        // we shell out here because java's posix file permission view doesn't support special modes
        assertThat(es.config, file(Directory, "root", "poissonsearch", p750));
        assertThat(sh.run("find \"" + es.config + "\" -maxdepth 0 -printf \"%m\"").stdout, containsString("2750"));

        final Path jvmOptionsDirectory = es.config.resolve("jvm.options.d");
        assertThat(jvmOptionsDirectory, file(Directory, "root", "poissonsearch", p750));
        assertThat(sh.run("find \"" + jvmOptionsDirectory + "\" -maxdepth 0 -printf \"%m\"").stdout, containsString("2750"));

        Stream.of("elasticsearch.keystore", "elasticsearch.yml", "jvm.options", "log4j2.properties")
            .forEach(configFile -> assertThat(es.config(configFile), file(File, "root", "poissonsearch", p660)));
        assertThat(es.config(".elasticsearch.keystore.initial_md5sum"), file(File, "root", "poissonsearch", p644));

        assertThat(sh.run("sudo -u poissonsearch " + es.bin("elasticsearch-keystore") + " list").stdout, containsString("keystore.seed"));

        Stream.of(es.bin, es.lib).forEach(dir -> assertThat(dir, file(Directory, "root", "root", p755)));

        Stream.of("elasticsearch", "elasticsearch-plugin", "elasticsearch-keystore", "elasticsearch-shard", "elasticsearch-node")
            .forEach(executable -> assertThat(es.bin(executable), file(File, "root", "root", p755)));

        Stream.of("NOTICE.txt", "README.asciidoc").forEach(doc -> assertThat(es.home.resolve(doc), file(File, "root", "root", p644)));

        assertThat(es.envFile, file(File, "root", "poissonsearch", p660));

        if (distribution.packaging == Distribution.Packaging.RPM) {
            assertThat(es.home.resolve("LICENSE.txt"), file(File, "root", "root", p644));
        } else {
            Path copyrightDir = Paths.get(sh.run("readlink -f /usr/share/doc/" + distribution.flavor.name).stdout.trim());
            assertThat(copyrightDir, file(Directory, "root", "root", p755));
            assertThat(copyrightDir.resolve("copyright"), file(File, "root", "root", p644));
        }

        if (isSystemd()) {
            Stream.of(
                SYSTEMD_SERVICE,
                Paths.get("/usr/lib/tmpfiles.d/poissonsearch.conf"),
                Paths.get("/usr/lib/sysctl.d/poissonsearch.conf")
            ).forEach(confFile -> assertThat(confFile, file(File, "root", "root", p644)));

            final String sysctlExecutable = (distribution.packaging == Distribution.Packaging.RPM) ? "/usr/sbin/sysctl" : "/sbin/sysctl";
            assertThat(sh.run(sysctlExecutable + " vm.max_map_count").stdout, containsString("vm.max_map_count = 262144"));
        }

        if (isSysVInit()) {
            assertThat(SYSVINIT_SCRIPT, file(File, "root", "root", p750));
        }
    }

    private static void verifyDefaultInstallation(Installation es, Distribution distribution) {

        Stream.of(
            "elasticsearch-certgen",
            "elasticsearch-certutil",
            "elasticsearch-croneval",
            "elasticsearch-migrate",
            "elasticsearch-saml-metadata",
            "elasticsearch-setup-passwords",
            "elasticsearch-sql-cli",
            "elasticsearch-syskeygen",
            "elasticsearch-users",
            "x-pack-env",
            "x-pack-security-env",
            "x-pack-watcher-env"
        ).forEach(executable -> assertThat(es.bin(executable), file(File, "root", "root", p755)));

        // at this time we only install the current version of archive distributions, but if that changes we'll need to pass
        // the version through here
        assertThat(es.bin("elasticsearch-sql-cli-" + distribution.version + ".jar"), file(File, "root", "root", p755));

        Stream.of("users", "users_roles", "roles.yml", "role_mapping.yml", "log4j2.properties")
            .forEach(configFile -> assertThat(es.config(configFile), file(File, "root", "poissonsearch", p660)));
    }

    /**
     * Starts Elasticsearch, without checking that startup is successful.
     */
    public static Shell.Result runElasticsearchStartCommand(Shell sh) throws IOException {
        if (isSystemd()) {
            sh.run("systemctl daemon-reload");
            sh.run("systemctl enable poissonsearch.service");
            sh.run("systemctl is-enabled poissonsearch.service");
            return sh.runIgnoreExitCode("systemctl start poissonsearch.service");
        }
        return sh.runIgnoreExitCode("service poissonsearch start");
    }

    public static void assertElasticsearchStarted(Shell sh, Installation installation) throws Exception {
        waitForElasticsearch(installation);

        if (isSystemd()) {
            sh.run("systemctl is-active poissonsearch.service");
            sh.run("systemctl status poissonsearch.service");
        } else {
            sh.run("service poissonsearch status");
        }
    }

    public static void stopElasticsearch(Shell sh) {
        if (isSystemd()) {
            sh.run("systemctl stop poissonsearch.service");
        } else {
            sh.run("service poissonsearch stop");
        }
    }

    public static void restartElasticsearch(Shell sh, Installation installation) throws Exception {
        if (isSystemd()) {
            sh.run("systemctl restart poissonsearch.service");
        } else {
            sh.run("service poissonsearch restart");
        }
        assertElasticsearchStarted(sh, installation);
    }

    /**
     * A small wrapper for retrieving only recent journald logs for the
     * Elasticsearch service. It works by creating a cursor for the logs
     * when instantiated, and advancing that cursor when the {@code clear()}
     * method is called.
     */
    public static class JournaldWrapper {
        private Shell sh;
        private String cursor;

        /**
         * Create a new wrapper for Elasticsearch JournalD logs.
         * @param sh A shell with appropriate permissions.
         */
        public JournaldWrapper(Shell sh) {
            this.sh = sh;
            clear();
        }

        /**
         * "Clears" the journaled messages by retrieving the latest cursor
         * for Elasticsearch logs and storing it in class state.
         */
        public void clear() {
            final String script = "sudo journalctl --unit=poissonsearch.service --lines=0 --show-cursor -o cat | sed -e 's/-- cursor: //'";
            cursor = sh.run(script).stdout.trim();
        }

        /**
         * Retrieves all log messages coming after the stored cursor.
         * @return Recent journald logs for the Elasticsearch service.
         */
        public Result getLogs() {
            return sh.run("journalctl -u poissonsearch.service --after-cursor='" + this.cursor + "'");
        }
    }

    private enum PackageManagerCommand {
        QUERY,
        INSTALL,
        UPGRADE,
        FORCE_UPGRADE,
        REMOVE
    }

    private static Map<PackageManagerCommand, String> RPM_OPTIONS = org.elasticsearch.common.collect.Map.of(
        PackageManagerCommand.QUERY,
        "-qe",
        PackageManagerCommand.INSTALL,
        "-i",
        PackageManagerCommand.UPGRADE,
        "-U",
        PackageManagerCommand.FORCE_UPGRADE,
        "-U --force",
        PackageManagerCommand.REMOVE,
        "-e"
    );

    private static Map<PackageManagerCommand, String> DEB_OPTIONS = org.elasticsearch.common.collect.Map.of(
        PackageManagerCommand.QUERY,
        "-s",
        PackageManagerCommand.INSTALL,
        "-i",
        PackageManagerCommand.UPGRADE,
        "-i --force-confnew",
        PackageManagerCommand.FORCE_UPGRADE,
        "-i --force-conflicts",
        PackageManagerCommand.REMOVE,
        "-r"
    );
}
