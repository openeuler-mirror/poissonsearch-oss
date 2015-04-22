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

package org.elasticsearch.bootstrap;

import org.apache.lucene.util.Constants;
import org.elasticsearch.common.io.PathUtils;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.NodeEnvironment;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** 
 * Initializes securitymanager with necessary permissions.
 * <p>
 * We use a template file (the one we test with), and add additional 
 * permissions based on the environment (data paths, etc)
 */
class Security {
    
    /** 
     * Initializes securitymanager for the environment
     * Can only happen once!
     */
    static void configure(Environment environment) throws IOException {
        Path newConfig = processTemplate(environment.configFile().resolve("security.policy"), environment);
        System.setProperty("java.security.policy", newConfig.toString());
        System.setSecurityManager(new SecurityManager());
        try {
            Files.delete(newConfig);
        } catch (Exception e) {
            Loggers.getLogger(Security.class).warn("unable to remove temporary file: " + newConfig, e);
        }
    }
   
    // package-private for testing
    static Path processTemplate(Path template, Environment environment) throws IOException {
        Path processed = Files.createTempFile(null, null);
        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(processed))) {
            // copy the template as-is.
            Files.copy(template, output);
            
            // add permissions for all configured paths.
            Set<Path> paths = new HashSet<>();
            paths.add(environment.homeFile());
            paths.add(environment.configFile());
            paths.add(environment.logsFile());
            paths.add(environment.pluginsFile());
            paths.add(environment.workFile());
            paths.add(environment.workWithClusterFile());
            for (Path path : environment.dataFiles()) {
                paths.add(path);
            }
            for (Path path : environment.dataWithClusterFiles()) {
                paths.add(path);
            }
            output.write(createPermissions(paths));
        }
        return processed;
    }
    
    // package private for testing
    static byte[] createPermissions(Set<Path> paths) throws IOException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        
        // all policy files are UTF-8:
        //  https://docs.oracle.com/javase/7/docs/technotes/guides/security/PolicyFiles.html
        try (Writer writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8)) {
            writer.write(System.lineSeparator());
            writer.write("grant {");
            writer.write(System.lineSeparator());
            for (Path path : paths) {
                // add each path twice: once for itself, again for files underneath it
                addPath(writer, encode(path), "read,readlink,write,delete");
                addRecursivePath(writer, encode(path), "read,readlink,write,delete");
            }
            
            // on *nix, try to grant read perms to file stores / SSD detection
            if (!Constants.WINDOWS) {
                Set<String> stores = new HashSet<>();
                for (FileStore store : PathUtils.getDefaultFileSystem().getFileStores()) {
                    try {
                        String mount = NodeEnvironment.getMountPoint(store);
                        // mount point for fstat() calls against it
                        if (mount.startsWith("/")) {
                            stores.add(mount);
                        }
                        // block device: add it for SSD detection
                        if (store.name().startsWith("/")) {
                            stores.add(store.name());
                        }
                    } catch (Throwable t) {
                        // these are hacks that are not guaranteed
                    }
                }
                for (String store : stores) {
                    addPath(writer, encode(store), "read,readlink");
                }
                addRecursivePath(writer, "/sys/block", "read,readlink");
                addRecursivePath(writer, "/sys/devices", "read,readlink");
                addRecursivePath(writer, "/dev", "read,readlink");
                addRecursivePath(writer, "/devices", "read,readlink");
            }
            
            writer.write("};");
            writer.write(System.lineSeparator());
        }
        
        return stream.toByteArray();
    }
    
    static void addPath(Writer writer, String path, String permissions) throws IOException {
        writer.write("permission java.io.FilePermission \"" + path + "\", \"" + permissions + "\";");
        writer.write(System.lineSeparator());
    }
    
    static void addRecursivePath(Writer writer, String path, String permissions) throws IOException {
        writer.write("permission java.io.FilePermission \"" + path + "${/}-\", \"" + permissions + "\";");
        writer.write(System.lineSeparator());
    }
    
    // Any backslashes in paths must be escaped, because it is the escape character when parsing.
    // See "Note Regarding File Path Specifications on Windows Systems".
    // https://docs.oracle.com/javase/7/docs/technotes/guides/security/PolicyFiles.html
    static String encode(Path path) {
        return encode(path.toString());
    }
    
    static String encode(String path) {
        return path.replace("\\", "\\\\");
    }
}
