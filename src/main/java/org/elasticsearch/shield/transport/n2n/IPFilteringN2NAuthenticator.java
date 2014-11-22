/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.transport.n2n;

import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.jackson.dataformat.yaml.snakeyaml.error.YAMLException;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.net.InetAddresses;
import org.elasticsearch.common.netty.handler.ipfilter.IpFilterRule;
import org.elasticsearch.common.netty.handler.ipfilter.IpSubnetFilterRule;
import org.elasticsearch.common.netty.handler.ipfilter.PatternRule;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.yaml.YamlXContent;
import org.elasticsearch.env.Environment;
import org.elasticsearch.shield.ShieldPlugin;
import org.elasticsearch.watcher.FileChangesListener;
import org.elasticsearch.watcher.FileWatcher;
import org.elasticsearch.watcher.ResourceWatcherService;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class IPFilteringN2NAuthenticator extends AbstractComponent implements N2NAuthenticator {

    private static final Pattern COMMA_DELIM = Pattern.compile("\\s*,\\s*");
    private static final String DEFAULT_FILE = "ip_filter.yml";
    private static final ProfileIpFilterRule[] NO_RULES = new ProfileIpFilterRule[0];

    private final Path file;

    private volatile ProfileIpFilterRule[] rules = NO_RULES;

    @Inject
    public IPFilteringN2NAuthenticator(Settings settings, Environment env, ResourceWatcherService watcherService) {
        super(settings);
        file = resolveFile(componentSettings, env);
        rules = parseFile(file, logger);
        FileWatcher watcher = new FileWatcher(file.getParent().toFile());
        watcher.addListener(new FileListener());
        watcherService.add(watcher, ResourceWatcherService.Frequency.HIGH);
    }

    private Path resolveFile(Settings settings, Environment env) {
        String location = settings.get("ip_filter.file");
        if (location == null) {
            return ShieldPlugin.resolveConfigFile(env, DEFAULT_FILE);
        }
        return Paths.get(location);
    }

    public static ProfileIpFilterRule[]  parseFile(Path path, ESLogger logger) {
        if (!Files.exists(path)) {
            return NO_RULES;
        }

        List<ProfileIpFilterRule> rules = new ArrayList<>();

        try (XContentParser parser = YamlXContent.yamlXContent.createParser(Files.newInputStream(path))) {
            XContentParser.Token token;
            String currentFieldName = null;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT && token != null) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                    if (!currentFieldName.endsWith("allow") && !currentFieldName.endsWith("deny")) {
                        throw new ElasticsearchParseException("Field name [" + currentFieldName + "] not valid. Must be [allow] or [deny] or using a profile");
                    }
                } else if (token == XContentParser.Token.VALUE_STRING && currentFieldName != null) {
                    String value = parser.text();
                    if (!Strings.hasLength(value)) {
                        throw new ElasticsearchParseException("Field value for fieldname [" + currentFieldName + "] must not be empty");
                    }

                    boolean isAllowRule = currentFieldName.endsWith("allow");
                    String profile = currentFieldName.contains(".") ? currentFieldName.substring(0, currentFieldName.indexOf(".")) : "default";

                    if (value.contains(",")) {
                        for (String rule : COMMA_DELIM.split(parser.text().trim())) {
                            rules.add(new ProfileIpFilterRule(profile, getRule(isAllowRule, rule)));
                        }
                    } else {
                        rules.add(new ProfileIpFilterRule(profile, getRule(isAllowRule, value)));
                    }

                }
            }
        } catch (IOException | YAMLException e) {
            throw new ElasticsearchParseException("Failed to read & parse host access file [" + path.toAbsolutePath() + "]", e);
        }

        if (rules.size() == 0) {
            return NO_RULES;
        }

        logger.debug("Loaded {} ip filtering rules", rules.size());
        return rules.toArray(new ProfileIpFilterRule[rules.size()]);
    }

    private static IpFilterRule getRule(boolean isAllowRule, String value) throws UnknownHostException {
        if ("all".equals(value)) {
            return new PatternRule(isAllowRule, "n:*");
        } else if (value.contains("/")) {
            return new IpSubnetFilterRule(isAllowRule, value);
        }

        boolean isInetAddress = InetAddresses.isInetAddress(value);
        String prefix = isInetAddress ? "i:" : "n:";
        return new PatternRule(isAllowRule, prefix + value);
    }

    @Override
    public boolean authenticate(@Nullable Principal peerPrincipal, String profile, InetAddress peerAddress, int peerPort) {
        for (ProfileIpFilterRule rule : rules) {
            if (rule.contains(profile, peerAddress)) {
                boolean isAllowed = rule.isAllowRule();
                logger.trace("Authentication rule matched for host [{}]: {}", peerAddress, isAllowed);
                return isAllowed;
            }
        }

        logger.trace("Allowing host {}", peerAddress);
        return true;
    }

    private class FileListener extends FileChangesListener {
        @Override
        public void onFileCreated(File file) {
            if (file.equals(IPFilteringN2NAuthenticator.this.file.toFile())) {
                rules = parseFile(file.toPath(), logger);
            }
        }

        @Override
        public void onFileDeleted(File file) {
            if (file.equals(IPFilteringN2NAuthenticator.this.file.toFile())) {
                rules = NO_RULES;
            }
        }

        @Override
        public void onFileChanged(File file) {
            if (file.equals(IPFilteringN2NAuthenticator.this.file.toFile())) {
                rules = parseFile(file.toPath(), logger);
            }
        }
    }
}
