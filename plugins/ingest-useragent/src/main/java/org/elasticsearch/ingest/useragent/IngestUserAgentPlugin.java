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

package org.elasticsearch.ingest.useragent;

import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.node.NodeModule;
import org.elasticsearch.plugins.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

public class IngestUserAgentPlugin extends Plugin {

    private final Setting<Long> CACHE_SIZE_SETTING = Setting.longSetting("ingest.useragent.cache_size", 1000, 0,
            Setting.Property.NodeScope);

    static final String DEFAULT_PARSER_NAME = "_default_";

    public void onModule(NodeModule nodeModule) throws IOException {
        Path userAgentConfigDirectory = nodeModule.getNode().getEnvironment().configFile().resolve("ingest-useragent");

        if (Files.exists(userAgentConfigDirectory) == false && Files.isDirectory(userAgentConfigDirectory)) {
            throw new IllegalStateException(
                    "the user agent directory [" + userAgentConfigDirectory + "] containing the regex file doesn't exist");
        }

        long cacheSize = CACHE_SIZE_SETTING.get(nodeModule.getNode().settings());

        UserAgentCache cache = new UserAgentCache(cacheSize);

        Map<String, UserAgentParser> userAgentParsers = createUserAgentParsers(userAgentConfigDirectory, cache);

        nodeModule.registerProcessor(UserAgentProcessor.TYPE, (registry) -> new UserAgentProcessor.Factory(userAgentParsers));
    }

    static Map<String, UserAgentParser> createUserAgentParsers(Path userAgentConfigDirectory, UserAgentCache cache) throws IOException {
        Map<String, UserAgentParser> userAgentParsers = new HashMap<>();

        UserAgentParser defaultParser = new UserAgentParser(DEFAULT_PARSER_NAME,
                IngestUserAgentPlugin.class.getResourceAsStream("/regexes.yaml"), cache);
        userAgentParsers.put(DEFAULT_PARSER_NAME, defaultParser);

        if (Files.exists(userAgentConfigDirectory) && Files.isDirectory(userAgentConfigDirectory)) {
            PathMatcher pathMatcher = userAgentConfigDirectory.getFileSystem().getPathMatcher("glob:**.yaml");
    
            try (Stream<Path> regexFiles = Files.find(userAgentConfigDirectory, 1,
                    (path, attr) -> attr.isRegularFile() && pathMatcher.matches(path))) {
                Iterable<Path> iterable = regexFiles::iterator;
                for (Path path : iterable) {
                    String parserName = path.getFileName().toString();
                    try (InputStream regexStream = Files.newInputStream(path, StandardOpenOption.READ)) {
                        userAgentParsers.put(parserName, new UserAgentParser(parserName, regexStream, cache));
                    }
                }
            }
        }

        return Collections.unmodifiableMap(userAgentParsers);
    }

}
