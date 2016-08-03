/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.license.XPackLicenseState.AllowedRealmType;
import org.elasticsearch.xpack.security.authc.activedirectory.ActiveDirectoryRealm;
import org.elasticsearch.xpack.security.authc.esnative.NativeRealm;
import org.elasticsearch.xpack.security.authc.esnative.ReservedRealm;
import org.elasticsearch.xpack.security.authc.file.FileRealm;
import org.elasticsearch.xpack.security.authc.ldap.LdapRealm;
import org.elasticsearch.xpack.security.authc.pki.PkiRealm;

import static org.elasticsearch.xpack.security.Security.setting;

/**
 * Serves as a realms registry (also responsible for ordering the realms appropriately)
 */
public class Realms extends AbstractLifecycleComponent implements Iterable<Realm> {

    static final List<String> INTERNAL_REALM_TYPES =
        Arrays.asList(ReservedRealm.TYPE, NativeRealm.TYPE, FileRealm.TYPE, ActiveDirectoryRealm.TYPE, LdapRealm.TYPE, PkiRealm.TYPE);

    public static final Setting<Settings> REALMS_GROUPS_SETTINGS = Setting.groupSetting(setting("authc.realms."), Property.NodeScope);

    private final Environment env;
    private final Map<String, Realm.Factory> factories;
    private final XPackLicenseState licenseState;
    private final ReservedRealm reservedRealm;

    protected List<Realm> realms = Collections.emptyList();
    // a list of realms that are considered default in that they are provided by x-pack and not a third party
    protected List<Realm> internalRealmsOnly = Collections.emptyList();
    // a list of realms that are considered native, that is they only interact with x-pack and no 3rd party auth sources
    protected List<Realm> nativeRealmsOnly = Collections.emptyList();

    public Realms(Settings settings, Environment env, Map<String, Realm.Factory> factories, XPackLicenseState licenseState,
                  ReservedRealm reservedRealm) {
        super(settings);
        this.env = env;
        this.factories = factories;
        this.licenseState = licenseState;
        this.reservedRealm = reservedRealm;
    }

    @Override
    protected void doStart() throws ElasticsearchException {
        assert factories.get(ReservedRealm.TYPE) == null;
        this.realms = initRealms();
        // pre-computing a list of internal only realms allows us to have much cheaper iteration than a custom iterator
        // and is also simpler in terms of logic. These lists are small, so the duplication should not be a real issue here
        List<Realm> internalRealms = new ArrayList<>();
        List<Realm> nativeRealms = new ArrayList<>();
        for (Realm realm : realms) {
            // don't add the reserved realm here otherwise we end up with only this realm...
            if (INTERNAL_REALM_TYPES.contains(realm.type()) && ReservedRealm.TYPE.equals(realm.type()) == false) {
                internalRealms.add(realm);
            }

            if (FileRealm.TYPE.equals(realm.type()) || NativeRealm.TYPE.equals(realm.type())) {
                nativeRealms.add(realm);
            }
        }

        for (List<Realm> realmList : Arrays.asList(internalRealms, nativeRealms)) {
            if (realmList.isEmpty()) {
                addNativeRealms(realmList);
            }

            assert realmList.contains(reservedRealm) == false;
            realmList.add(0, reservedRealm);
            assert realmList.get(0) == reservedRealm;
        }

        this.internalRealmsOnly = Collections.unmodifiableList(internalRealms);
        this.nativeRealmsOnly = Collections.unmodifiableList(nativeRealms);
    }

    @Override
    protected void doStop() throws ElasticsearchException {
    }

    @Override
    protected void doClose() throws ElasticsearchException {
    }

    @Override
    public Iterator<Realm> iterator() {
        if (licenseState.isAuthAllowed() == false) {
            return Collections.emptyIterator();
        }

        AllowedRealmType allowedRealmType = licenseState.allowedRealmType();
        switch (allowedRealmType) {
            case ALL:
                return realms.iterator();
            case DEFAULT:
                return internalRealmsOnly.iterator();
            case NATIVE:
                return nativeRealmsOnly.iterator();
            default:
                throw new IllegalStateException("authentication should not be enabled");
        }
    }

    public Realm realm(String name) {
        for (Realm realm : realms) {
            if (name.equals(realm.config.name)) {
                return realm;
            }
        }
        return null;
    }

    public Realm.Factory realmFactory(String type) {
        return factories.get(type);
    }

    protected List<Realm> initRealms() {
        Settings realmsSettings = REALMS_GROUPS_SETTINGS.get(settings);
        Set<String> internalTypes = new HashSet<>();
        List<Realm> realms = new ArrayList<>();
        for (String name : realmsSettings.names()) {
            Settings realmSettings = realmsSettings.getAsSettings(name);
            String type = realmSettings.get("type");
            if (type == null) {
                throw new IllegalArgumentException("missing realm type for [" + name + "] realm");
            }
            Realm.Factory factory = factories.get(type);
            if (factory == null) {
                throw new IllegalArgumentException("unknown realm type [" + type + "] set for realm [" + name + "]");
            }
            RealmConfig config = new RealmConfig(name, realmSettings, settings, env);
            if (!config.enabled()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("realm [{}/{}] is disabled", type, name);
                }
                continue;
            }
            if (FileRealm.TYPE.equals(type) || NativeRealm.TYPE.equals(type)) {
                // this is an internal realm factory, let's make sure we didn't already registered one
                // (there can only be one instance of an internal realm)
                if (internalTypes.contains(type)) {
                    throw new IllegalArgumentException("multiple [" + type + "] realms are configured. [" + type +
                            "] is an internal realm and therefore there can only be one such realm configured");
                }
                internalTypes.add(type);
            }
            realms.add(factory.create(config));
        }

        if (!realms.isEmpty()) {
            Collections.sort(realms);
        } else {
            // there is no "realms" configuration, add the defaults
            addNativeRealms(realms);
        }
        // always add built in first!
        realms.add(0, reservedRealm);
        return realms;
    }

    public Map<String, Object> usageStats() {
        Map<String, Object> realmMap = new HashMap<>();
        for (Realm realm : this) {
            if (ReservedRealm.TYPE.equals(realm.type())) {
                continue;
            }
            realmMap.compute(realm.type(), (key, value) -> {
                if (value == null) {
                    Object realmTypeUsage = convertToMapOfLists(realm.usageStats());
                    return realmTypeUsage;
                }
                assert value instanceof Map;
                combineMaps((Map<String, Object>) value, realm.usageStats());
                return value;
            });
        }

        final AllowedRealmType allowedRealmType = licenseState.allowedRealmType();
        // iterate over the factories so we can add enabled & available info
        for (String type : factories.keySet()) {
            assert ReservedRealm.TYPE.equals(type) == false;
            realmMap.compute(type, (key, value) -> {
                if (value == null) {
                    return MapBuilder.<String, Object>newMapBuilder()
                            .put("enabled", false)
                            .put("available", isRealmTypeAvailable(allowedRealmType, type))
                            .map();
                }

                assert value instanceof Map;
                Map<String, Object> realmTypeUsage = (Map<String, Object>) value;
                realmTypeUsage.put("enabled", true);
                // the realms iterator returned this type so it must be enabled
                assert isRealmTypeAvailable(allowedRealmType, type);
                realmTypeUsage.put("available", true);
                return value;
            });
        }

        return realmMap;
    }

    /**
     * returns the settings for the {@link FileRealm}. Typically, this realms may or may
     * not be configured. If it is not configured, it will work OOTB using default settings. If it is
     * configured, there can only be one configured instance.
     */
    public static Settings fileRealmSettings(Settings settings) {
        Settings realmsSettings = REALMS_GROUPS_SETTINGS.get(settings);
        Settings result = null;
        for (String name : realmsSettings.names()) {
            Settings realmSettings = realmsSettings.getAsSettings(name);
            String type = realmSettings.get("type");
            if (type == null) {
                throw new IllegalArgumentException("missing realm type for [" + name + "] realm");
            }
            if (FileRealm.TYPE.equals(type)) {
                if (result != null) {
                    throw new IllegalArgumentException("multiple [" + FileRealm.TYPE +
                            "]realms are configured. only one may be configured");
                }
                result = realmSettings;
            }
        }
        return result != null ? result : Settings.EMPTY;
    }

    private void addNativeRealms(List<Realm> realms) {
        Realm.Factory fileRealm = factories.get(FileRealm.TYPE);
        if (fileRealm != null) {

            realms.add(fileRealm.create(new RealmConfig("default_" + FileRealm.TYPE, Settings.EMPTY, settings, env)));
        }
        Realm.Factory indexRealmFactory = factories.get(NativeRealm.TYPE);
        if (indexRealmFactory != null) {
            realms.add(indexRealmFactory.create(new RealmConfig("default_" + NativeRealm.TYPE, Settings.EMPTY, settings, env)));
        }
    }

    public static void addSettings(List<Setting<?>> settingsModule) {
        settingsModule.add(REALMS_GROUPS_SETTINGS);
    }

    private static void combineMaps(Map<String, Object> mapA, Map<String, Object> mapB) {
        for (Entry<String, Object> entry : mapB.entrySet()) {
            mapA.compute(entry.getKey(), (key, value) -> {
                if (value == null) {
                    return new ArrayList<>(Collections.singletonList(entry.getValue()));
                }

                assert value instanceof List;
                ((List) value).add(entry.getValue());
                return value;
            });
        }
    }

    private static Map<String, Object> convertToMapOfLists(Map<String, Object> map) {
        Map<String, Object> converted = new HashMap<>(map.size());
        for (Entry<String, Object> entry : map.entrySet()) {
            converted.put(entry.getKey(), new ArrayList<>(Collections.singletonList(entry.getValue())));
        }
        return converted;
    }

    private static boolean isRealmTypeAvailable(AllowedRealmType enabledRealmType, String type) {
        switch (enabledRealmType) {
            case ALL:
                return true;
            case NONE:
                return false;
            case NATIVE:
                return FileRealm.TYPE.equals(type) || NativeRealm.TYPE.equals(type);
            case DEFAULT:
                return INTERNAL_REALM_TYPES.contains(type);
            default:
                throw new IllegalStateException("unknown enabled realm type [" + enabledRealmType + "]");
        }
    }
}
