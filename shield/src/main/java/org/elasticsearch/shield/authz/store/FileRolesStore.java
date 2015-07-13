/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authz.store;

import com.fasterxml.jackson.dataformat.yaml.snakeyaml.error.YAMLException;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.yaml.YamlXContent;
import org.elasticsearch.env.Environment;
import org.elasticsearch.shield.ShieldPlugin;
import org.elasticsearch.shield.authc.support.RefreshListener;
import org.elasticsearch.shield.authz.Permission;
import org.elasticsearch.shield.authz.Privilege;
import org.elasticsearch.shield.authz.SystemRole;
import org.elasticsearch.shield.support.NoOpLogger;
import org.elasticsearch.shield.support.Validation;
import org.elasticsearch.watcher.FileChangesListener;
import org.elasticsearch.watcher.FileWatcher;
import org.elasticsearch.watcher.ResourceWatcherService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 *
 */
public class FileRolesStore extends AbstractLifecycleComponent<RolesStore> implements RolesStore {

    private static final Pattern COMMA_DELIM = Pattern.compile("\\s*,\\s*");
    private static final Pattern IN_SEGMENT_LINE = Pattern.compile("^\\s+.+");
    private static final Pattern SKIP_LINE = Pattern.compile("(^#.*|^\\s*)");

    private final Path file;
    private final RefreshListener listener;
    private final ImmutableSet<Permission.Global.Role> reservedRoles;
    private final ResourceWatcherService watcherService;

    private volatile ImmutableMap<String, Permission.Global.Role> permissions;

    @Inject
    public FileRolesStore(Settings settings, Environment env, ResourceWatcherService watcherService, Set<Permission.Global.Role> reservedRoles) {
        this(settings, env, watcherService, reservedRoles, RefreshListener.NOOP);
    }

    public FileRolesStore(Settings settings, Environment env, ResourceWatcherService watcherService, Set<Permission.Global.Role> reservedRoles, RefreshListener listener) {
        super(settings);
        this.file = resolveFile(settings, env);
        this.listener = listener;
        this.watcherService = watcherService;
        this.reservedRoles = ImmutableSet.copyOf(reservedRoles);
        permissions = ImmutableMap.of();
    }

    @Override
    protected void doStart() throws ElasticsearchException {
        FileWatcher watcher = new FileWatcher(file.getParent());
        watcher.addListener(new FileListener());
        try {
            watcherService.add(watcher, ResourceWatcherService.Frequency.HIGH);
        } catch (IOException e) {
            throw new ElasticsearchException("failed to setup roles file watcher", e);
        }
        permissions = parseFile(file, reservedRoles, logger);
    }

    @Override
    protected void doStop() throws ElasticsearchException {
    }

    @Override
    protected void doClose() throws ElasticsearchException {
    }

    @Override
    public Permission.Global.Role role(String role) {
        return permissions.get(role);
    }

    public static Path resolveFile(Settings settings, Environment env) {
        String location = settings.get("shield.authz.store.files.roles");
        if (location == null) {
            return ShieldPlugin.resolveConfigFile(env, "roles.yml");
        }

        return env.homeFile().resolve(location);
    }

    public static ImmutableSet<String> parseFileForRoleNames(Path path, ESLogger logger) {
        ImmutableMap<String, Permission.Global.Role> roleMap = parseFile(path, Collections.<Permission.Global.Role>emptySet(), logger, false);
        if (roleMap == null) {
            return ImmutableSet.<String>builder().build();
        }
        return roleMap.keySet();
    }

    public static ImmutableMap<String, Permission.Global.Role> parseFile(Path path, Set<Permission.Global.Role> reservedRoles, ESLogger logger) {
        return parseFile(path, reservedRoles, logger, true);
    }

    public static ImmutableMap<String, Permission.Global.Role> parseFile(Path path, Set<Permission.Global.Role> reservedRoles, ESLogger logger, boolean resolvePermission) {
        if (logger == null) {
            logger = NoOpLogger.INSTANCE;
        }

        logger.trace("reading roles file located at [{}]", path.toAbsolutePath());

        if (!Files.exists(path)) {
            return ImmutableMap.of();
        }

        Map<String, Permission.Global.Role> roles = new HashMap<>();

        try {

            List<String> roleSegments = roleSegments(path);
            for (String segment : roleSegments) {
                Permission.Global.Role role = parseRole(segment, path, logger, resolvePermission);
                if (role != null) {
                    if (SystemRole.NAME.equals(role.name())) {
                        logger.warn("role [{}] is reserved to the system. the relevant role definition in the mapping file will be ignored", SystemRole.NAME);
                    } else {
                        roles.put(role.name(), role);
                    }
                }
            }

        } catch (IOException ioe) {
            logger.error("failed to read roles file [{}]. skipping all roles...", ioe, path.toAbsolutePath());
        }

        // we now add all the fixed roles (overriding any attempts to override the fixed roles in the file)
        for (Permission.Global.Role reservedRole : reservedRoles) {
            if (roles.containsKey(reservedRole.name())) {
                logger.warn("role [{}] is reserved to the system. the relevant role definition in the mapping file will be ignored", reservedRole.name());
            }
            roles.put(reservedRole.name(), reservedRole);
        }

        return ImmutableMap.copyOf(roles);
    }

    private static Permission.Global.Role parseRole(String segment, Path path, ESLogger logger, boolean resolvePermissions) {
        String roleName = null;
        try {
            XContentParser parser = YamlXContent.yamlXContent.createParser(segment);
            XContentParser.Token token = parser.nextToken();
            if (token == XContentParser.Token.START_OBJECT) {
                token = parser.nextToken();
                if (token == XContentParser.Token.FIELD_NAME) {
                    roleName = parser.currentName();
                    Validation.Error validationError = Validation.Roles.validateRoleName(roleName);
                    if (validationError != null) {
                        logger.error("invalid role definition [{}] in roles file [{}]. invalid role name - {}. skipping role... ", roleName, path.toAbsolutePath(), validationError);
                        return null;
                    }

                    Permission.Global.Role.Builder permission = Permission.Global.Role.builder(roleName);
                    if (resolvePermissions == false) {
                        return permission.build();
                    }

                    token = parser.nextToken();
                    if (token == XContentParser.Token.START_OBJECT) {
                        String currentFieldName = null;
                        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                            if (token == XContentParser.Token.FIELD_NAME) {
                                currentFieldName = parser.currentName();
                            } else if ("cluster".equals(currentFieldName)) {
                                Privilege.Name name = null;
                                if (token == XContentParser.Token.VALUE_STRING) {
                                    String namesStr = parser.text().trim();
                                    if (Strings.hasLength(namesStr)) {
                                        String[] names = COMMA_DELIM.split(namesStr);
                                        name = new Privilege.Name(names);
                                    }
                                } else if (token == XContentParser.Token.START_ARRAY) {
                                    Set<String> names = new HashSet<>();
                                    while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                                        if (token == XContentParser.Token.VALUE_STRING) {
                                            names.add(parser.text());
                                        }
                                    }
                                    if (!names.isEmpty()) {
                                        name = new Privilege.Name(names);
                                    }
                                } else {
                                    logger.error("invalid role definition [{}] in roles file [{}]. [cluster] field value can either " +
                                                    "be a string or a list of strings, but [{}] was found instead. skipping role...",
                                            roleName, path.toAbsolutePath(), token);
                                    return null;
                                }
                                if (name != null) {
                                    try {
                                        permission.set(Privilege.Cluster.get(name));
                                    } catch (IllegalArgumentException e) {
                                        logger.error("invalid role definition [{}] in roles file [{}]. could not resolve cluster privileges [{}]. skipping role...", roleName, path.toAbsolutePath(), name);
                                        return null;
                                    }
                                }
                            } else if ("indices".equals(currentFieldName)) {
                                if (token == XContentParser.Token.START_OBJECT) {
                                    while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                                        if (token == XContentParser.Token.FIELD_NAME) {
                                            currentFieldName = parser.currentName();
                                        } else if (Strings.hasLength(currentFieldName)) {
                                            String[] indices = COMMA_DELIM.split(currentFieldName);
                                            Privilege.Name name = null;
                                            if (token == XContentParser.Token.VALUE_STRING) {
                                                String namesStr = parser.text().trim();
                                                if (Strings.hasLength(namesStr)) {
                                                    String[] names = COMMA_DELIM.split(parser.text());
                                                    name = new Privilege.Name(names);
                                                }
                                            } else if (token == XContentParser.Token.START_ARRAY) {
                                                Set<String> names = new HashSet<>();
                                                while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                                                    if (token == XContentParser.Token.VALUE_STRING) {
                                                        names.add(parser.text());
                                                    } else {
                                                        logger.error("invalid role definition [{}] in roles file [{}]. could not parse " +
                                                                "[{}] as index privilege. privilege names must be strings. skipping role...", roleName, path.toAbsolutePath(), token);
                                                        return null;
                                                    }
                                                }
                                                if (!names.isEmpty()) {
                                                    name = new Privilege.Name(names);
                                                }
                                            } else {
                                                logger.error("invalid role definition [{}] in roles file [{}]. could not parse [{}] as index privileges. privilege lists must either " +
                                                        "be a comma delimited string or an array of strings. skipping role...", roleName, path.toAbsolutePath(), token);
                                                return null;
                                            }
                                            if (name != null) {
                                                try {
                                                    permission.add(Privilege.Index.get(name), indices);
                                                } catch (IllegalArgumentException e) {
                                                    logger.error("invalid role definition [{}] in roles file [{}]. could not resolve indices privileges [{}]. skipping role...", roleName, path.toAbsolutePath(), name);
                                                    return null;
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    logger.error("invalid role definition [{}] in roles file [{}]. [indices] field value must be an array of indices-privileges mappings defined as a string" +
                                                    " in the form <comma-separated list of index name patterns>::<comma-separated list of privileges> , but [{}] was found instead. skipping role...",
                                            roleName, path.toAbsolutePath(), token);
                                    return null;
                                }
                            }
                        }
                        return permission.build();
                    }
                    logger.error("invalid role definition [{}] in roles file [{}]. skipping role...", roleName, path.toAbsolutePath());
                }
            }
        } catch (YAMLException | IOException e) {
            if (roleName != null) {
                logger.error("invalid role definition [{}] in roles file [{}]. skipping role...", e, roleName, path);
            } else {
                logger.error("invalid role definition in roles file [{}]. skipping role...", e, path);
            }
        }
        return null;
    }

    private static List<String> roleSegments(Path path) throws IOException {
        List<String> segments = new ArrayList<>();
        StringBuilder builder = null;
        for (String line : Files.readAllLines(path, Charsets.UTF_8)) {
            if (!SKIP_LINE.matcher(line).matches()) {
                if (IN_SEGMENT_LINE.matcher(line).matches()) {
                    if (builder != null) {
                        builder.append(line).append("\n");
                    }
                } else {
                    if (builder != null) {
                        segments.add(builder.toString());
                    }
                    builder = new StringBuilder(line).append("\n");
                }
            }
        }
        if (builder != null) {
            segments.add(builder.toString());
        }
        return segments;
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
            if (file.equals(FileRolesStore.this.file)) {
                try {
                    permissions = parseFile(file, reservedRoles, logger);
                    logger.info("updated roles (roles file [{}] changed)", file.toAbsolutePath());
                } catch (Throwable t) {
                    logger.error("could not reload roles file [{}]. Current roles remain unmodified", t, file.toAbsolutePath());
                    return;
                }
                listener.onRefresh();
            }
        }
    }
}
