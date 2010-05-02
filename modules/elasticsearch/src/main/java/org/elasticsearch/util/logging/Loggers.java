/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

package org.elasticsearch.util.logging;

import org.elasticsearch.index.Index;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.util.Classes;
import org.elasticsearch.util.gcommon.collect.Lists;
import org.elasticsearch.util.settings.Settings;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static java.util.Arrays.asList;
import static org.elasticsearch.util.gcommon.collect.Lists.*;

/**
 * A set of utilities around Logging.
 *
 * @author kimchy (shay.banon)
 */
public class Loggers {

    private static boolean consoleLoggingEnabled = true;

    public static void disableConsoleLogging() {
        consoleLoggingEnabled = false;
    }

    public static void enableConsoleLogging() {
        consoleLoggingEnabled = true;
    }

    public static boolean consoleLoggingEnabled() {
        return consoleLoggingEnabled;
    }

    public static ESLogger getLogger(Class clazz, Settings settings, ShardId shardId, String... prefixes) {
        return getLogger(clazz, settings, shardId.index(), Lists.asList(Integer.toString(shardId.id()), prefixes).toArray(new String[0]));
    }

    public static ESLogger getLogger(Class clazz, Settings settings, Index index, String... prefixes) {
        return getLogger(clazz, settings, Lists.asList(index.name(), prefixes).toArray(new String[0]));
    }

    public static ESLogger getLogger(Class clazz, Settings settings, String... prefixes) {
        return getLogger(getLoggerName(clazz), settings, prefixes);
    }

    public static ESLogger getLogger(String loggerName, Settings settings, String... prefixes) {
        List<String> prefixesList = newArrayList();
        if (settings.getAsBoolean("logger.logHostAddress", false)) {
            try {
                prefixesList.add(InetAddress.getLocalHost().getHostAddress());
            } catch (UnknownHostException e) {
                // ignore
            }
        }
        if (settings.getAsBoolean("logger.logHostName", false)) {
            try {
                prefixesList.add(InetAddress.getLocalHost().getHostName());
            } catch (UnknownHostException e) {
                // ignore
            }
        }
        String name = settings.get("name");
        if (name != null) {
            prefixesList.add(name);
        }
        if (prefixes != null && prefixes.length > 0) {
            prefixesList.addAll(asList(prefixes));
        }
        return getLogger(getLoggerName(loggerName), prefixesList.toArray(new String[prefixesList.size()]));
    }

    public static ESLogger getLogger(ESLogger parentLogger, String s) {
        return getLogger(parentLogger.getName() + s, parentLogger.getPrefix());
    }

    public static ESLogger getLogger(String s) {
        return ESLoggerFactory.getLogger(s);
    }

    public static ESLogger getLogger(Class clazz) {
        return ESLoggerFactory.getLogger(getLoggerName(clazz));
    }

    public static ESLogger getLogger(Class clazz, String... prefixes) {
        return getLogger(getLoggerName(clazz), prefixes);
    }

    public static ESLogger getLogger(String name, String... prefixes) {
        String prefix = null;
        if (prefixes != null && prefixes.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (String prefixX : prefixes) {
                if (prefixX != null) {
                    sb.append("[").append(prefixX).append("]");
                }
            }
            if (sb.length() > 0) {
                sb.append(" ");
                prefix = sb.toString();
            }
        }
        return ESLoggerFactory.getLogger(prefix, getLoggerName(name));
    }

    private static String getLoggerName(Class clazz) {
        String name = clazz.getName();
        if (name.startsWith("org.elasticsearch.")) {
            name = Classes.getPackageName(clazz);
        }
        return getLoggerName(name);
    }

    private static String getLoggerName(String name) {
        if (name.startsWith("org.elasticsearch.")) {
            return name.substring("org.elasticsearch.".length());
        }
        return name;
    }
}
