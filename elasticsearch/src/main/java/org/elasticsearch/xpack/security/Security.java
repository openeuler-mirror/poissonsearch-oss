/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security;

import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.SetOnce;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.action.support.DestructiveOperations;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Booleans;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.inject.util.Providers;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.logging.LoggerMessageFormat;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.regex.Regex;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.env.Environment;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.index.IndexModule;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.ingest.Processor;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.IngestPlugin;
import org.elasticsearch.plugins.NetworkPlugin;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.transport.TransportInterceptor;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.transport.TransportRequestHandler;
import org.elasticsearch.tribe.TribeService;
import org.elasticsearch.watcher.ResourceWatcherService;
import org.elasticsearch.xpack.XPackPlugin;
import org.elasticsearch.xpack.XPackSettings;
import org.elasticsearch.xpack.extensions.XPackExtension;
import org.elasticsearch.xpack.security.action.SecurityActionModule;
import org.elasticsearch.xpack.security.action.filter.SecurityActionFilter;
import org.elasticsearch.xpack.security.action.realm.ClearRealmCacheAction;
import org.elasticsearch.xpack.security.action.realm.TransportClearRealmCacheAction;
import org.elasticsearch.xpack.security.action.role.ClearRolesCacheAction;
import org.elasticsearch.xpack.security.action.role.DeleteRoleAction;
import org.elasticsearch.xpack.security.action.role.GetRolesAction;
import org.elasticsearch.xpack.security.action.role.PutRoleAction;
import org.elasticsearch.xpack.security.action.role.TransportClearRolesCacheAction;
import org.elasticsearch.xpack.security.action.role.TransportDeleteRoleAction;
import org.elasticsearch.xpack.security.action.role.TransportGetRolesAction;
import org.elasticsearch.xpack.security.action.role.TransportPutRoleAction;
import org.elasticsearch.xpack.security.action.user.AuthenticateAction;
import org.elasticsearch.xpack.security.action.user.ChangePasswordAction;
import org.elasticsearch.xpack.security.action.user.DeleteUserAction;
import org.elasticsearch.xpack.security.action.user.GetUsersAction;
import org.elasticsearch.xpack.security.action.user.PutUserAction;
import org.elasticsearch.xpack.security.action.user.SetEnabledAction;
import org.elasticsearch.xpack.security.action.user.TransportAuthenticateAction;
import org.elasticsearch.xpack.security.action.user.TransportChangePasswordAction;
import org.elasticsearch.xpack.security.action.user.TransportDeleteUserAction;
import org.elasticsearch.xpack.security.action.user.TransportGetUsersAction;
import org.elasticsearch.xpack.security.action.user.TransportPutUserAction;
import org.elasticsearch.xpack.security.action.user.TransportSetEnabledAction;
import org.elasticsearch.xpack.security.audit.AuditTrail;
import org.elasticsearch.xpack.security.audit.AuditTrailService;
import org.elasticsearch.xpack.security.audit.index.IndexAuditTrail;
import org.elasticsearch.xpack.security.audit.index.IndexNameResolver;
import org.elasticsearch.xpack.security.audit.logfile.LoggingAuditTrail;
import org.elasticsearch.xpack.security.authc.AuthenticationFailureHandler;
import org.elasticsearch.xpack.security.authc.AuthenticationService;
import org.elasticsearch.xpack.security.authc.DefaultAuthenticationFailureHandler;
import org.elasticsearch.xpack.security.authc.Realm;
import org.elasticsearch.xpack.security.authc.Realms;
import org.elasticsearch.xpack.security.authc.activedirectory.ActiveDirectoryRealm;
import org.elasticsearch.xpack.security.authc.esnative.NativeRealm;
import org.elasticsearch.xpack.security.authc.esnative.NativeUsersStore;
import org.elasticsearch.xpack.security.authc.esnative.ReservedRealm;
import org.elasticsearch.xpack.security.authc.file.FileRealm;
import org.elasticsearch.xpack.security.authc.ldap.LdapRealm;
import org.elasticsearch.xpack.security.authc.ldap.support.SessionFactory;
import org.elasticsearch.xpack.security.authc.pki.PkiRealm;
import org.elasticsearch.xpack.security.authc.support.SecuredString;
import org.elasticsearch.xpack.security.authc.support.UsernamePasswordToken;
import org.elasticsearch.xpack.security.authz.AuthorizationService;
import org.elasticsearch.xpack.security.authz.accesscontrol.OptOutQueryCache;
import org.elasticsearch.xpack.security.authz.accesscontrol.SecurityIndexSearcherWrapper;
import org.elasticsearch.xpack.security.authz.accesscontrol.SetSecurityUserProcessor;
import org.elasticsearch.xpack.security.authz.store.CompositeRolesStore;
import org.elasticsearch.xpack.security.authz.store.FileRolesStore;
import org.elasticsearch.xpack.security.authz.store.NativeRolesStore;
import org.elasticsearch.xpack.security.authz.store.ReservedRolesStore;
import org.elasticsearch.xpack.security.crypto.CryptoService;
import org.elasticsearch.xpack.security.rest.SecurityRestModule;
import org.elasticsearch.xpack.security.rest.action.RestAuthenticateAction;
import org.elasticsearch.xpack.security.rest.action.realm.RestClearRealmCacheAction;
import org.elasticsearch.xpack.security.rest.action.role.RestClearRolesCacheAction;
import org.elasticsearch.xpack.security.rest.action.role.RestDeleteRoleAction;
import org.elasticsearch.xpack.security.rest.action.role.RestGetRolesAction;
import org.elasticsearch.xpack.security.rest.action.role.RestPutRoleAction;
import org.elasticsearch.xpack.security.rest.action.user.RestChangePasswordAction;
import org.elasticsearch.xpack.security.rest.action.user.RestDeleteUserAction;
import org.elasticsearch.xpack.security.rest.action.user.RestGetUsersAction;
import org.elasticsearch.xpack.security.rest.action.user.RestPutUserAction;
import org.elasticsearch.xpack.security.rest.action.user.RestSetEnabledAction;
import org.elasticsearch.xpack.security.transport.SecurityServerTransportInterceptor;
import org.elasticsearch.xpack.security.transport.filter.IPFilter;
import org.elasticsearch.xpack.security.transport.netty3.SecurityNetty3HttpServerTransport;
import org.elasticsearch.xpack.security.transport.netty3.SecurityNetty3Transport;
import org.elasticsearch.xpack.security.transport.netty4.SecurityNetty4HttpServerTransport;
import org.elasticsearch.xpack.security.transport.netty4.SecurityNetty4Transport;
import org.elasticsearch.xpack.security.user.AnonymousUser;
import org.elasticsearch.xpack.ssl.SSLService;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

public class Security implements ActionPlugin, IngestPlugin, NetworkPlugin {

    private static final Logger logger = Loggers.getLogger(XPackPlugin.class);

    public static final String NAME3 = XPackPlugin.SECURITY + "3";
    public static final String NAME4 = XPackPlugin.SECURITY + "4";
    public static final Setting<Optional<String>> USER_SETTING =
            new Setting<>(setting("user"), (String) null, Optional::ofNullable, Property.NodeScope);

    public static final Setting<List<String>> AUDIT_OUTPUTS_SETTING =
        Setting.listSetting(setting("audit.outputs"),
            s -> s.getAsMap().containsKey(setting("audit.outputs")) ?
                Collections.emptyList() : Collections.singletonList(LoggingAuditTrail.NAME),
            Function.identity(), Property.NodeScope);

    private final Settings settings;
    private final Environment env;
    private final boolean enabled;
    private final boolean transportClientMode;
    private final XPackLicenseState licenseState;
    private final CryptoService cryptoService;
    private final SSLService sslService;
    /* what a PITA that we need an extra indirection to initialize this. Yet, once we got rid of guice we can thing about how
     * to fix this or make it simpler. Today we need several service that are created in createComponents but we need to register
     * an instance of TransportInterceptor way earlier before createComponents is called. */
    private final SetOnce<TransportInterceptor> securityIntercepter = new SetOnce<>();
    private final SetOnce<IPFilter> ipFilter = new SetOnce<>();

    public Security(Settings settings, Environment env, XPackLicenseState licenseState, SSLService sslService) throws IOException {
        this.settings = settings;
        this.env = env;
        this.transportClientMode = XPackPlugin.transportClientMode(settings);
        this.enabled = XPackSettings.SECURITY_ENABLED.get(settings);
        if (enabled && transportClientMode == false) {
            validateAutoCreateIndex(settings);
            cryptoService = new CryptoService(settings, env);
        } else {
            cryptoService = null;
        }
        this.licenseState = licenseState;
        this.sslService = sslService;
    }

    public CryptoService getCryptoService() {
        return cryptoService;
    }

    public Collection<Module> nodeModules() {
        List<Module> modules = new ArrayList<>();
        if (enabled == false || transportClientMode) {
            modules.add(b -> b.bind(IPFilter.class).toProvider(Providers.of(null)));
        }

        if (transportClientMode) {
            if (enabled == false) {
                return modules;
            }
            modules.add(b -> {
                // for transport client we still must inject these ssl classes with guice
                b.bind(SSLService.class).toInstance(sslService);
            });

            return modules;
        }
        modules.add(b -> XPackPlugin.bindFeatureSet(b, SecurityFeatureSet.class));

        
        if (enabled == false) {
            modules.add(b -> {
                b.bind(CryptoService.class).toProvider(Providers.of(null));
                b.bind(Realms.class).toProvider(Providers.of(null)); // for SecurityFeatureSet
                b.bind(CompositeRolesStore.class).toProvider(Providers.of(null)); // for SecurityFeatureSet
                b.bind(AuditTrailService.class)
                    .toInstance(new AuditTrailService(settings, Collections.emptyList(), licenseState));
            });
            return modules;
        }

        // we can't load that at construction time since the license plugin might not have been loaded at that point
        // which might not be the case during Plugin class instantiation. Once nodeModules are pulled
        // everything should have been loaded
        modules.add(b -> {
            b.bind(CryptoService.class).toInstance(cryptoService);
            if (XPackSettings.AUDIT_ENABLED.get(settings)) {
                b.bind(AuditTrail.class).to(AuditTrailService.class); // interface used by some actions...
            }
        });
        modules.add(new SecurityRestModule(settings));
        modules.add(new SecurityActionModule(settings));
        return modules;
    }

    public Collection<Object> createComponents(InternalClient client, ThreadPool threadPool, ClusterService clusterService,
                                               ResourceWatcherService resourceWatcherService, List<XPackExtension> extensions) {
        if (enabled == false) {
            return Collections.emptyList();
        }

        List<Object> components = new ArrayList<>();
        final SecurityContext securityContext = new SecurityContext(settings, threadPool, cryptoService);
        components.add(securityContext);

        // realms construction
        final NativeUsersStore nativeUsersStore = new NativeUsersStore(settings, client);
        final AnonymousUser anonymousUser = new AnonymousUser(settings);
        final ReservedRealm reservedRealm = new ReservedRealm(env, settings, nativeUsersStore, anonymousUser);
        Map<String, Realm.Factory> realmFactories = new HashMap<>();
        realmFactories.put(FileRealm.TYPE, config -> new FileRealm(config, resourceWatcherService));
        realmFactories.put(NativeRealm.TYPE, config -> new NativeRealm(config, nativeUsersStore));
        realmFactories.put(ActiveDirectoryRealm.TYPE,
            config -> new ActiveDirectoryRealm(config, resourceWatcherService, sslService));
        realmFactories.put(LdapRealm.TYPE, config -> new LdapRealm(config, resourceWatcherService, sslService));
        realmFactories.put(PkiRealm.TYPE, config -> new PkiRealm(config, resourceWatcherService, sslService));
        for (XPackExtension extension : extensions) {
            Map<String, Realm.Factory> newRealms = extension.getRealms(resourceWatcherService);
            for (Map.Entry<String, Realm.Factory> entry : newRealms.entrySet()) {
                if (realmFactories.put(entry.getKey(), entry.getValue()) != null) {
                    throw new IllegalArgumentException("Realm type [" + entry.getKey() + "] is already registered");
                }
            }
        }
        final Realms realms = new Realms(settings, env, realmFactories, licenseState, reservedRealm);
        components.add(nativeUsersStore);
        components.add(realms);
        components.add(reservedRealm);

        // audit trails construction
        IndexAuditTrail indexAuditTrail = null;
        Set<AuditTrail> auditTrails = new LinkedHashSet<>();
        if (XPackSettings.AUDIT_ENABLED.get(settings)) {
            List<String> outputs = AUDIT_OUTPUTS_SETTING.get(settings);
            if (outputs.isEmpty()) {
                throw new IllegalArgumentException("Audit logging is enabled but there are zero output types in "
                    + XPackSettings.AUDIT_ENABLED.getKey());
            }

            for (String output : outputs) {
                switch (output) {
                    case LoggingAuditTrail.NAME:
                        auditTrails.add(new LoggingAuditTrail(settings, clusterService, threadPool));
                        break;
                    case IndexAuditTrail.NAME:
                        indexAuditTrail = new IndexAuditTrail(settings, client, threadPool, clusterService);
                        auditTrails.add(indexAuditTrail);
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown audit trail output [" + output + "]");
                }
            }
        }
        final AuditTrailService auditTrailService =
            new AuditTrailService(settings, auditTrails.stream().collect(Collectors.toList()), licenseState);
        components.add(auditTrailService);

        AuthenticationFailureHandler failureHandler = null;
        String extensionName = null;
        for (XPackExtension extension : extensions) {
            AuthenticationFailureHandler extensionFailureHandler = extension.getAuthenticationFailureHandler();
            if (extensionFailureHandler != null && failureHandler != null) {
                throw new IllegalStateException("Extensions [" + extensionName +"] and [" + extension.name() + "] " +
                    "both set an authentication failure handler");
            }
            failureHandler = extensionFailureHandler;
            extensionName = extension.name();
        }
        if (failureHandler == null) {
            logger.debug("Using default authentication failure handler");
            failureHandler = new DefaultAuthenticationFailureHandler();
        } else {
            logger.debug("Using authentication failure handler from extension [" + extensionName + "]");
        }

        final AuthenticationService authcService = new AuthenticationService(settings, realms, auditTrailService,
            cryptoService, failureHandler, threadPool, anonymousUser);
        components.add(authcService);

        final FileRolesStore fileRolesStore = new FileRolesStore(settings, env, resourceWatcherService);
        final NativeRolesStore nativeRolesStore = new NativeRolesStore(settings, client);
        final ReservedRolesStore reservedRolesStore = new ReservedRolesStore(securityContext);
        final CompositeRolesStore allRolesStore = new CompositeRolesStore(settings, fileRolesStore, nativeRolesStore, reservedRolesStore);
        final AuthorizationService authzService = new AuthorizationService(settings, allRolesStore, clusterService,
            auditTrailService, failureHandler, threadPool, anonymousUser);
        components.add(fileRolesStore); // has lifecycle
        components.add(nativeRolesStore); // used by roles actions
        components.add(reservedRolesStore); // used by roles actions
        components.add(allRolesStore); // for SecurityFeatureSet
        components.add(authzService);

        components.add(new SecurityLifecycleService(settings, clusterService, threadPool, indexAuditTrail,
            nativeUsersStore, nativeRolesStore, client));

        ipFilter.set(new IPFilter(settings, auditTrailService, clusterService.getClusterSettings(), licenseState));
        components.add(ipFilter.get());
        DestructiveOperations destructiveOperations = new DestructiveOperations(settings, clusterService.getClusterSettings());
        securityIntercepter.set(new SecurityServerTransportInterceptor(settings, threadPool, authcService, authzService, licenseState,
                sslService, securityContext, destructiveOperations));
        return components;
    }

    public Settings additionalSettings() {
        if (enabled == false) {
            return Settings.EMPTY;
        }

        return additionalSettings(settings, transportClientMode);
    }

    // visible for tests
    static Settings additionalSettings(Settings settings, boolean transportClientMode) {
        final Settings.Builder settingsBuilder = Settings.builder();

        if (NetworkModule.TRANSPORT_TYPE_SETTING.exists(settings)) {
            final String transportType = NetworkModule.TRANSPORT_TYPE_SETTING.get(settings);
            if (NAME3.equals(transportType) == false && NAME4.equals(transportType) == false) {
                throw new IllegalArgumentException("transport type setting [" + NetworkModule.TRANSPORT_TYPE_KEY + "] must be one of [" +
                        NAME3 + "," + NAME4 + "]");
            }
        } else {
            // default to security4
            settingsBuilder.put(NetworkModule.TRANSPORT_TYPE_KEY, NAME4);
        }

        if (NetworkModule.HTTP_TYPE_SETTING.exists(settings)) {
            final String httpType = NetworkModule.HTTP_TYPE_SETTING.get(settings);
            if (httpType.equals(NAME3)) {
                SecurityNetty3HttpServerTransport.overrideSettings(settingsBuilder, settings);
            } else if (httpType.equals(NAME4)) {
                SecurityNetty4HttpServerTransport.overrideSettings(settingsBuilder, settings);
            } else {
                throw new IllegalArgumentException("http type setting [" + NetworkModule.HTTP_TYPE_KEY + "] must be one of [" +
                        NAME3 + "," + NAME4 + "]");
            }
        } else {
            // default to security4
            settingsBuilder.put(NetworkModule.HTTP_TYPE_KEY, NAME4);
            SecurityNetty4HttpServerTransport.overrideSettings(settingsBuilder, settings);
        }

        addUserSettings(settings, settingsBuilder);
        addTribeSettings(settings, settingsBuilder);
        return settingsBuilder.build();
    }

    public static List<Setting<?>> getSettings(boolean transportClientMode) {
        List<Setting<?>> settingsList = new ArrayList<>();
        // always register for both client and node modes
        settingsList.add(USER_SETTING);

        if (transportClientMode) {
            return settingsList;
        }

        // The following just apply in node mode

        // IP Filter settings
        IPFilter.addSettings(settingsList);

        // audit settings
        settingsList.add(AUDIT_OUTPUTS_SETTING);
        LoggingAuditTrail.registerSettings(settingsList);
        IndexAuditTrail.registerSettings(settingsList);

        // authentication settings
        AnonymousUser.addSettings(settingsList);
        Realms.addSettings(settingsList);
        NativeRolesStore.addSettings(settingsList);
        AuthenticationService.addSettings(settingsList);
        AuthorizationService.addSettings(settingsList);

        // encryption settings
        CryptoService.addSettings(settingsList);

        // hide settings
        settingsList.add(Setting.listSetting(setting("hide_settings"), Collections.emptyList(), Function.identity(),
                Property.NodeScope, Property.Filtered));
        return settingsList;
    }

    
    public List<String> getSettingsFilter() {
        ArrayList<String> settingsFilter = new ArrayList<>();
        String[] asArray = settings.getAsArray(setting("hide_settings"));
        for (String pattern : asArray) {
            settingsFilter.add(pattern);
        }

        settingsFilter.add(setting("authc.realms.*.bind_dn"));
        settingsFilter.add(setting("authc.realms.*.bind_password"));
        settingsFilter.add(setting("authc.realms.*." + SessionFactory.HOSTNAME_VERIFICATION_SETTING));
        settingsFilter.add(setting("authc.realms.*.truststore.password"));
        settingsFilter.add(setting("authc.realms.*.truststore.path"));
        settingsFilter.add(setting("authc.realms.*.truststore.algorithm"));

        // hide settings where we don't define them - they are part of a group...
        settingsFilter.add("transport.profiles.*." + setting("*"));
        return settingsFilter;
    }

    public void onIndexModule(IndexModule module) {
        if (enabled == false) {
            return;
        }

        assert licenseState != null;
        if (XPackSettings.DLS_FLS_ENABLED.get(settings)) {
            module.setSearcherWrapper(indexService ->
                new SecurityIndexSearcherWrapper(indexService.getIndexSettings(),
                    shardId -> indexService.newQueryShardContext(shardId.id(),
                            // we pass a null index reader, which is legal and will disable rewrite optimizations
                            // based on index statistics, which is probably safer...
                            null,
                            () -> {
                                throw new IllegalArgumentException("permission filters are not allowed to use the current timestamp");
                            }),
                    indexService.mapperService(), indexService.cache().bitsetFilterCache(),
                    indexService.getThreadPool().getThreadContext(), licenseState,
                    indexService.getScriptService()));
        }
        if (transportClientMode == false) {
            /*  We need to forcefully overwrite the query cache implementation to use security's opt out query cache implementation.
             *  This impl. disabled the query cache if field level security is used for a particular request. If we wouldn't do
             *  forcefully overwrite the query cache implementation then we leave the system vulnerable to leakages of data to
             *  unauthorized users. */
            module.forceQueryCacheProvider(OptOutQueryCache::new);
        }
    }

    @Override
    public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
        if (enabled == false) {
            return emptyList();
        }
        return Arrays.asList(new ActionHandler<>(ClearRealmCacheAction.INSTANCE, TransportClearRealmCacheAction.class),
                new ActionHandler<>(ClearRolesCacheAction.INSTANCE, TransportClearRolesCacheAction.class),
                new ActionHandler<>(GetUsersAction.INSTANCE, TransportGetUsersAction.class),
                new ActionHandler<>(PutUserAction.INSTANCE, TransportPutUserAction.class),
                new ActionHandler<>(DeleteUserAction.INSTANCE, TransportDeleteUserAction.class),
                new ActionHandler<>(GetRolesAction.INSTANCE, TransportGetRolesAction.class),
                new ActionHandler<>(PutRoleAction.INSTANCE, TransportPutRoleAction.class),
                new ActionHandler<>(DeleteRoleAction.INSTANCE, TransportDeleteRoleAction.class),
                new ActionHandler<>(ChangePasswordAction.INSTANCE, TransportChangePasswordAction.class),
                new ActionHandler<>(AuthenticateAction.INSTANCE, TransportAuthenticateAction.class),
                new ActionHandler<>(SetEnabledAction.INSTANCE, TransportSetEnabledAction.class));
    }

    @Override
    public List<Class<? extends ActionFilter>> getActionFilters() {
        if (enabled == false) {
            return emptyList();
        }
        // registering the security filter only for nodes
        if (transportClientMode == false) {
            return singletonList(SecurityActionFilter.class);
        }
        return emptyList();
    }

    @Override
    public List<Class<? extends RestHandler>> getRestHandlers() {
        if (enabled == false) {
            return emptyList();
        }
        return Arrays.asList(RestAuthenticateAction.class,
                RestClearRealmCacheAction.class,
                RestClearRolesCacheAction.class,
                RestGetUsersAction.class,
                RestPutUserAction.class,
                RestDeleteUserAction.class,
                RestGetRolesAction.class,
                RestPutRoleAction.class,
                RestDeleteRoleAction.class,
                RestChangePasswordAction.class,
                RestSetEnabledAction.class);
    }

    @Override
    public Map<String, Processor.Factory> getProcessors(Processor.Parameters parameters) {
        return Collections.singletonMap(SetSecurityUserProcessor.TYPE, new SetSecurityUserProcessor.Factory(parameters.threadContext));
    }

    private static void addUserSettings(Settings settings, Settings.Builder settingsBuilder) {
        String authHeaderSettingName = ThreadContext.PREFIX + "." + UsernamePasswordToken.BASIC_AUTH_HEADER;
        if (settings.get(authHeaderSettingName) != null) {
            return;
        }
        Optional<String> userOptional = USER_SETTING.get(settings);
        userOptional.ifPresent(userSetting -> {
            final int i = userSetting.indexOf(":");
            if (i < 0 || i == userSetting.length() - 1) {
                throw new IllegalArgumentException("invalid [" + USER_SETTING.getKey() + "] setting. must be in the form of " +
                        "\"<username>:<password>\"");
            }
            String username = userSetting.substring(0, i);
            String password = userSetting.substring(i + 1);
            settingsBuilder.put(authHeaderSettingName, UsernamePasswordToken.basicAuthHeaderValue(username, new SecuredString(password
                    .toCharArray())));
        });
    }

    /**
     * If the current node is a tribe node, we inject additional settings on each tribe client. We do this to make sure
     * that every tribe cluster has x-pack installed and security is enabled. We do that by:
     *
     *    - making it mandatory on the tribe client (this means that the tribe node will fail at startup if x-pack is
     *      not loaded on any tribe due to missing mandatory plugin)
     *
     *    - forcibly enabling it (that means it's not possible to disable security on the tribe clients)
     */
    private static void addTribeSettings(Settings settings, Settings.Builder settingsBuilder) {
        Map<String, Settings> tribesSettings = settings.getGroups("tribe", true);
        if (tribesSettings.isEmpty()) {
            // it's not a tribe node
            return;
        }

        final Map<String, String> settingsMap = settings.getAsMap();
        for (Map.Entry<String, Settings> tribeSettings : tribesSettings.entrySet()) {
            String tribePrefix = "tribe." + tribeSettings.getKey() + ".";

            final String tribeEnabledSetting = tribePrefix + XPackSettings.SECURITY_ENABLED.getKey();
            if (settings.get(tribeEnabledSetting) != null) {
                boolean enabled = XPackSettings.SECURITY_ENABLED.get(tribeSettings.getValue());
                if (!enabled) {
                    throw new IllegalStateException("tribe setting [" + tribeEnabledSetting + "] must be set to true but the value is ["
                            + settings.get(tribeEnabledSetting) + "]");
                }
            } else {
                //x-pack security must be enabled on every tribe if it's enabled on the tribe node
                settingsBuilder.put(tribeEnabledSetting, true);
            }

            // we passed all the checks now we need to copy in all of the x-pack security settings
            for (Map.Entry<String, String> entry : settingsMap.entrySet()) {
                String key = entry.getKey();
                if (key.startsWith("xpack.security.")) {
                    settingsBuilder.put(tribePrefix + key, entry.getValue());
                }
            }
        }

        Map<String, Settings> realmsSettings = settings.getGroups(setting("authc.realms"), true);
        final boolean hasNativeRealm = XPackSettings.RESERVED_REALM_ENABLED_SETTING.get(settings) ||
                realmsSettings.isEmpty() ||
                realmsSettings.entrySet().stream()
                        .anyMatch((e) -> NativeRealm.TYPE.equals(e.getValue().get("type")) && e.getValue().getAsBoolean("enabled", true));
        if (hasNativeRealm) {
            if (TribeService.ON_CONFLICT_SETTING.get(settings).startsWith("prefer_") == false) {
                throw new IllegalArgumentException("use of security on tribe nodes requires setting [tribe.on_conflict] to specify the " +
                        "name of the tribe to prefer such as [prefer_t1] as the security index can exist in multiple tribes but only one" +
                        " can be used by the tribe node");
            }
        }
    }

    public static String settingPrefix() {
        return XPackPlugin.featureSettingPrefix(XPackPlugin.SECURITY) + ".";
    }

    public static String setting(String setting) {
        assert setting != null && setting.startsWith(".") == false;
        return settingPrefix() + setting;
    }

    static boolean indexAuditLoggingEnabled(Settings settings) {
        if (XPackSettings.AUDIT_ENABLED.get(settings)) {
            List<String> outputs = AUDIT_OUTPUTS_SETTING.get(settings);
            for (String output : outputs) {
                if (output.equals(IndexAuditTrail.NAME)) {
                    return true;
                }
            }
        }
        return false;
    }

    static void validateAutoCreateIndex(Settings settings) {
        String value = settings.get("action.auto_create_index");
        if (value == null) {
            return;
        }

        final boolean indexAuditingEnabled = Security.indexAuditLoggingEnabled(settings);
        final String auditIndex = indexAuditingEnabled ? "," + IndexAuditTrail.INDEX_NAME_PREFIX + "*" : "";
        String errorMessage = LoggerMessageFormat.format("the [action.auto_create_index] setting value [{}] is too" +
                " restrictive. disable [action.auto_create_index] or set it to " +
                "[{}{}]", (Object) value, SecurityTemplateService.SECURITY_INDEX_NAME, auditIndex);
        if (Booleans.isExplicitFalse(value)) {
            throw new IllegalArgumentException(errorMessage);
        }

        if (Booleans.isExplicitTrue(value)) {
            return;
        }

        String[] matches = Strings.commaDelimitedListToStringArray(value);
        List<String> indices = new ArrayList<>();
        indices.add(SecurityTemplateService.SECURITY_INDEX_NAME);
        if (indexAuditingEnabled) {
            DateTime now = new DateTime(DateTimeZone.UTC);
            // just use daily rollover
            indices.add(IndexNameResolver.resolve(IndexAuditTrail.INDEX_NAME_PREFIX, now, IndexNameResolver.Rollover.DAILY));
            indices.add(IndexNameResolver.resolve(IndexAuditTrail.INDEX_NAME_PREFIX, now.plusDays(1), IndexNameResolver.Rollover.DAILY));
            indices.add(IndexNameResolver.resolve(IndexAuditTrail.INDEX_NAME_PREFIX, now.plusMonths(1), IndexNameResolver.Rollover.DAILY));
            indices.add(IndexNameResolver.resolve(IndexAuditTrail.INDEX_NAME_PREFIX, now.plusMonths(2), IndexNameResolver.Rollover.DAILY));
            indices.add(IndexNameResolver.resolve(IndexAuditTrail.INDEX_NAME_PREFIX, now.plusMonths(3), IndexNameResolver.Rollover.DAILY));
            indices.add(IndexNameResolver.resolve(IndexAuditTrail.INDEX_NAME_PREFIX, now.plusMonths(4), IndexNameResolver.Rollover.DAILY));
            indices.add(IndexNameResolver.resolve(IndexAuditTrail.INDEX_NAME_PREFIX, now.plusMonths(5), IndexNameResolver.Rollover.DAILY));
            indices.add(IndexNameResolver.resolve(IndexAuditTrail.INDEX_NAME_PREFIX, now.plusMonths(6), IndexNameResolver.Rollover.DAILY));
        }

        for (String index : indices) {
            boolean matched = false;
            for (String match : matches) {
                char c = match.charAt(0);
                if (c == '-') {
                    if (Regex.simpleMatch(match.substring(1), index)) {
                        throw new IllegalArgumentException(errorMessage);
                    }
                } else if (c == '+') {
                    if (Regex.simpleMatch(match.substring(1), index)) {
                        matched = true;
                        break;
                    }
                } else {
                    if (Regex.simpleMatch(match, index)) {
                        matched = true;
                        break;
                    }
                }
            }
            if (!matched) {
                throw new IllegalArgumentException(errorMessage);
            }
        }

        if (indexAuditingEnabled) {
            logger.warn("the [action.auto_create_index] setting is configured to be restrictive [{}]. " +
                    " for the next 6 months audit indices are allowed to be created, but please make sure" +
                    " that any future history indices after 6 months with the pattern " +
                    "[.security_audit_log*] are allowed to be created", value);
        }
    }

    @Override
    public List<TransportInterceptor> getTransportInterceptors(NamedWriteableRegistry namedWriteableRegistry) {
        if (transportClientMode || enabled == false) { // don't register anything if we are not enabled
            // interceptors are not installed if we are running on the transport client
            return Collections.emptyList();
        }
       return Collections.singletonList(new TransportInterceptor() {
            @Override
            public <T extends TransportRequest> TransportRequestHandler<T> interceptHandler(String action, String executor,
                                                                                            TransportRequestHandler<T> actualHandler) {
                assert securityIntercepter.get() != null;
                return securityIntercepter.get().interceptHandler(action, executor, actualHandler);
            }

            @Override
            public AsyncSender interceptSender(AsyncSender sender) {
                assert securityIntercepter.get() != null;
                return securityIntercepter.get().interceptSender(sender);
            }
        });
    }

    @Override
    public Map<String, Supplier<Transport>> getTransports(Settings settings, ThreadPool threadPool, BigArrays bigArrays,
                                                          CircuitBreakerService circuitBreakerService,
                                                          NamedWriteableRegistry namedWriteableRegistry,
                                                          NetworkService networkService) {
        if (enabled == false) { // don't register anything if we are not enabled
            return Collections.emptyMap();
        }
        Map<String, Supplier<Transport>> map = new HashMap<>();
        map.put(Security.NAME3, () -> new SecurityNetty3Transport(settings, threadPool, networkService, bigArrays, ipFilter.get(),
                sslService, namedWriteableRegistry, circuitBreakerService));
        map.put(Security.NAME4, () -> new SecurityNetty4Transport(settings, threadPool, networkService, bigArrays,
                namedWriteableRegistry, circuitBreakerService, ipFilter.get(), sslService));
        return Collections.unmodifiableMap(map);

    }

    @Override
    public Map<String, Supplier<HttpServerTransport>> getHttpTransports(Settings settings, ThreadPool threadPool, BigArrays bigArrays,
                                                                        CircuitBreakerService circuitBreakerService,
                                                                        NamedWriteableRegistry namedWriteableRegistry,
                                                                        NetworkService networkService) {
        if (enabled == false) { // don't register anything if we are not enabled
            return Collections.emptyMap();
        }
        Map<String, Supplier<HttpServerTransport>> map = new HashMap<>();
        map.put(Security.NAME3, () -> new SecurityNetty3HttpServerTransport(settings, networkService, bigArrays, ipFilter.get(), sslService,
                threadPool));
        map.put(Security.NAME4, () -> new SecurityNetty4HttpServerTransport(settings, networkService, bigArrays, ipFilter.get(), sslService,
                threadPool));
        return Collections.unmodifiableMap(map);
    }

}
