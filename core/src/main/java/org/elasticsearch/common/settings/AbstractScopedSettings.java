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

package org.elasticsearch.common.settings;

import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.regex.Regex;
import org.elasticsearch.common.util.set.Sets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * A basic setting service that can be used for per-index and per-cluster settings.
 * This service offers transactional application of updates settings.
 */
public abstract class AbstractScopedSettings extends AbstractComponent {
    private Settings lastSettingsApplied = Settings.EMPTY;
    private final List<SettingUpdater<?>> settingUpdaters = new CopyOnWriteArrayList<>();
    private final Map<String, Setting<?>> complexMatchers;
    private final Map<String, Setting<?>> keySettings;
    private final Setting.Scope scope;
    private static final Pattern KEY_PATTERN = Pattern.compile("^(?:[-\\w]+[.])*[-\\w]+$");
    private static final Pattern GROUP_KEY_PATTERN = Pattern.compile("^(?:[-\\w]+[.])+$");

    protected AbstractScopedSettings(Settings settings, Set<Setting<?>> settingsSet, Setting.Scope scope) {
        super(settings);
        this.lastSettingsApplied = Settings.EMPTY;
        this.scope = scope;
        Map<String, Setting<?>> complexMatchers = new HashMap<>();
        Map<String, Setting<?>> keySettings = new HashMap<>();
        for (Setting<?> setting : settingsSet) {
            if (setting.getScope() != scope) {
                throw new IllegalArgumentException("Setting must be a " + scope + " setting but was: " + setting.getScope());
            }
            if (isValidKey(setting.getKey()) == false && (setting.isGroupSetting() && isValidGroupKey(setting.getKey())) == false) {
                throw new IllegalArgumentException("illegal settings key: [" + setting.getKey() + "]");
            }
            if (setting.hasComplexMatcher()) {
                complexMatchers.putIfAbsent(setting.getKey(), setting);
            } else {
                keySettings.putIfAbsent(setting.getKey(), setting);
            }
        }
        this.complexMatchers = Collections.unmodifiableMap(complexMatchers);
        this.keySettings = Collections.unmodifiableMap(keySettings);
    }

    protected AbstractScopedSettings(Settings nodeSettings, Settings scopeSettings, AbstractScopedSettings other) {
        super(nodeSettings);
        this.lastSettingsApplied = scopeSettings;
        this.scope = other.scope;
        complexMatchers = other.complexMatchers;
        keySettings = other.keySettings;
        settingUpdaters.addAll(other.settingUpdaters);
    }

    /**
     * Returns <code>true</code> iff the given key is a valid settings key otherwise <code>false</code>
     */
    public static boolean isValidKey(String key) {
        return KEY_PATTERN.matcher(key).matches();
    }

    private static boolean isValidGroupKey(String key) {
        return GROUP_KEY_PATTERN.matcher(key).matches();
    }

    public Setting.Scope getScope() {
        return this.scope;
    }

    /**
     * Applies the given settings to all listeners and rolls back the result after application. This
     * method will not change any settings but will fail if any of the settings can't be applied.
     */
    public synchronized Settings dryRun(Settings settings) {
        final Settings current = Settings.builder().put(this.settings).put(settings).build();
        final Settings previous = Settings.builder().put(this.settings).put(this.lastSettingsApplied).build();
        List<RuntimeException> exceptions = new ArrayList<>();
        for (SettingUpdater<?> settingUpdater : settingUpdaters) {
            try {
                if (settingUpdater.hasChanged(current, previous)) {
                    settingUpdater.getValue(current, previous);
                }
            } catch (RuntimeException ex) {
                exceptions.add(ex);
                logger.debug("failed to prepareCommit settings for [{}]", ex, settingUpdater);
            }
        }
        // here we are exhaustive and record all settings that failed.
        ExceptionsHelper.rethrowAndSuppress(exceptions);
        return current;
    }

    /**
     * Applies the given settings to all the settings consumers or to none of them. The settings
     * will be merged with the node settings before they are applied while given settings override existing node
     * settings.
     * @param newSettings the settings to apply
     * @return the unmerged applied settings
    */
    public synchronized Settings applySettings(Settings newSettings) {
        if (lastSettingsApplied != null && newSettings.equals(lastSettingsApplied)) {
            // nothing changed in the settings, ignore
            return newSettings;
        }
        final Settings current = Settings.builder().put(this.settings).put(newSettings).build();
        final Settings previous = Settings.builder().put(this.settings).put(this.lastSettingsApplied).build();
        try {
            List<Runnable> applyRunnables = new ArrayList<>();
            for (SettingUpdater<?> settingUpdater : settingUpdaters) {
                try {
                    applyRunnables.add(settingUpdater.updater(current, previous));
                } catch (Exception ex) {
                    logger.warn("failed to prepareCommit settings for [{}]", ex, settingUpdater);
                    throw ex;
                }
            }
            for (Runnable settingUpdater : applyRunnables) {
                settingUpdater.run();
            }
        } catch (Exception ex) {
            logger.warn("failed to apply settings", ex);
            throw ex;
        } finally {
        }
        return lastSettingsApplied = newSettings;
    }

    /**
     * Adds a settings consumer with a predicate that is only evaluated at update time.
     * <p>
     * Note: Only settings registered in {@link SettingsModule} can be changed dynamically.
     * </p>
     * @param validator an additional validator that is only applied to updates of this setting.
     *                  This is useful to add additional validation to settings at runtime compared to at startup time.
     */
    public synchronized <T> void addSettingsUpdateConsumer(Setting<T> setting, Consumer<T> consumer, Consumer<T> validator) {
        if (setting != get(setting.getKey())) {
            throw new IllegalArgumentException("Setting is not registered for key [" + setting.getKey() + "]");
        }
        addSettingsUpdater(setting.newUpdater(consumer, logger, validator));
    }

    synchronized void addSettingsUpdater(SettingUpdater<?> updater) {
        this.settingUpdaters.add(updater);
    }

    /**
     * Adds a settings consumer that accepts the values for two settings. The consumer if only notified if one or both settings change.
     * <p>
     * Note: Only settings registered in {@link SettingsModule} can be changed dynamically.
     * </p>
     * This method registers a compound updater that is useful if two settings are depending on each other. The consumer is always provided
     * with both values even if only one of the two changes.
     */
    public synchronized <A, B> void addSettingsUpdateConsumer(Setting<A> a, Setting<B> b, BiConsumer<A, B> consumer) {
        if (a != get(a.getKey())) {
            throw new IllegalArgumentException("Setting is not registered for key [" + a.getKey() + "]");
        }
        if (b != get(b.getKey())) {
            throw new IllegalArgumentException("Setting is not registered for key [" + b.getKey() + "]");
        }
        addSettingsUpdater(Setting.compoundUpdater(consumer, a, b, logger));
    }

    /**
     * Adds a settings consumer.
     * <p>
     * Note: Only settings registered in {@link org.elasticsearch.cluster.ClusterModule} can be changed dynamically.
     * </p>
     */
    public synchronized <T> void addSettingsUpdateConsumer(Setting<T> setting, Consumer<T> consumer) {
       addSettingsUpdateConsumer(setting, consumer, (s) -> {});
    }

    /**
     * Validates that all settings in the builder are registered and valid
     */
    public final void validate(Settings.Builder settingsBuilder) {
        validate(settingsBuilder.build());
    }

    /**
     * * Validates that all given settings are registered and valid
     */
    public final void validate(Settings settings) {
        for (Map.Entry<String, String> entry : settings.getAsMap().entrySet()) {
            validate(entry.getKey(), settings);
        }
    }


    /**
     * Validates that the setting is valid
     */
    public final void validate(String key, Settings settings) {
        Setting setting = get(key);
        if (setting == null) {
            throw new IllegalArgumentException("unknown setting [" + key + "]");
        }
        setting.get(settings);
    }

    /**
     * Transactional interface to update settings.
     * @see Setting
     * @param <T> the type of the value of the setting
     */
    public interface SettingUpdater<T> {

        /**
         * Returns true if this updaters setting has changed with the current update
         * @param current the current settings
         * @param previous the previous setting
         * @return true if this updaters setting has changed with the current update
         */
        boolean hasChanged(Settings current, Settings previous);

        /**
         * Returns the instance value for the current settings. This method is stateless and idempotent.
         * This method will throw an exception if the source of this value is invalid.
         */
        T getValue(Settings current, Settings previous);

        /**
         * Applies the given value to the updater. This methods will actually run the update.
         */
        void apply(T value, Settings current, Settings previous);

        /**
         * Updates this updaters value if it has changed.
         * @return <code>true</code> iff the value has been updated.
         */
        default boolean apply(Settings current, Settings previous) {
            if (hasChanged(current, previous)) {
                T value = getValue(current, previous);
                apply(value, current, previous);
                return true;
            }
            return false;
        }

        /**
         * Returns a callable runnable that calls {@link #apply(Object, Settings, Settings)} if the settings
         * actually changed. This allows to defer the update to a later point in time while keeping type safety.
         * If the value didn't change the returned runnable is a noop.
         */
        default Runnable updater(Settings current, Settings previous) {
            if (hasChanged(current, previous)) {
                T value = getValue(current, previous);
                return () -> { apply(value, current, previous);};
            }
            return () -> {};
        }
    }

    /**
     * Returns the {@link Setting} for the given key or <code>null</code> if the setting can not be found.
     */
    public Setting<?> get(String key) {
        Setting<?> setting = keySettings.get(key);
        if (setting != null) {
            return setting;
        }
        for (Map.Entry<String, Setting<?>> entry : complexMatchers.entrySet()) {
            if (entry.getValue().match(key)) {
                return entry.getValue().getConcreteSetting(key);
            }
        }
        return null;
    }

    /**
     * Returns <code>true</code> if the setting for the given key is dynamically updateable. Otherwise <code>false</code>.
     */
    public boolean hasDynamicSetting(String key) {
        final Setting<?> setting = get(key);
        return setting != null && setting.isDynamic();
    }

    /**
     * Returns a settings object that contains all settings that are not
     * already set in the given source. The diff contains either the default value for each
     * setting or the settings value in the given default settings.
     */
    public Settings diff(Settings source, Settings defaultSettings) {
        Settings.Builder builder = Settings.builder();
        for (Setting<?> setting : keySettings.values()) {
            if (setting.exists(source) == false) {
                builder.put(setting.getKey(), setting.getRaw(defaultSettings));
            }
        }
        return builder.build();
    }

    /**
     * Returns the value for the given setting.
     */
    public <T> T get(Setting<T> setting) {
        if (setting.getScope() != scope) {
            throw new IllegalArgumentException("settings scope doesn't match the setting scope [" + this.scope + "] != [" + setting.getScope() + "]");
        }
        if (get(setting.getKey()) == null) {
            throw new IllegalArgumentException("setting " + setting.getKey() + " has not been registered");
        }
        return setting.get(this.lastSettingsApplied, settings);
    }

    /**
     * Updates a target settings builder with new, updated or deleted settings from a given settings builder.
     * <p>
     * Note: This method will only allow updates to dynamic settings. if a non-dynamic setting is updated an {@link IllegalArgumentException} is thrown instead.
     *</p>
     * @param toApply the new settings to apply
     * @param target the target settings builder that the updates are applied to. All keys that have explicit null value in toApply will be removed from this builder
     * @param updates a settings builder that holds all updates applied to target
     * @param type a free text string to allow better exceptions messages
     * @return <code>true</code> if the target has changed otherwise <code>false</code>
     */
    public boolean updateDynamicSettings(Settings toApply, Settings.Builder target, Settings.Builder updates, String type) {
        return updateSettings(toApply, target, updates, type, true);
    }

    /**
     * Updates a target settings builder with new, updated or deleted settings from a given settings builder.
     * @param toApply the new settings to apply
     * @param target the target settings builder that the updates are applied to. All keys that have explicit null value in toApply will be removed from this builder
     * @param updates a settings builder that holds all updates applied to target
     * @param type a free text string to allow better exceptions messages
     * @return <code>true</code> if the target has changed otherwise <code>false</code>
     */
    public boolean updateSettings(Settings toApply, Settings.Builder target, Settings.Builder updates, String type) {
        return updateSettings(toApply, target, updates, type, false);
    }

    /**
     * Updates a target settings builder with new, updated or deleted settings from a given settings builder.
     * @param toApply the new settings to apply
     * @param target the target settings builder that the updates are applied to. All keys that have explicit null value in toApply will be removed from this builder
     * @param updates a settings builder that holds all updates applied to target
     * @param type a free text string to allow better exceptions messages
     * @param onlyDynamic  if <code>false</code> all settings are updated otherwise only dynamic settings are updated. if set to <code>true</code> and a non-dynamic setting is updated an exception is thrown.
     * @return <code>true</code> if the target has changed otherwise <code>false</code>
     */
    private boolean updateSettings(Settings toApply, Settings.Builder target, Settings.Builder updates, String type, boolean onlyDynamic) {
        boolean changed = false;
        final Set<String> toRemove = new HashSet<>();
        Settings.Builder settingsBuilder = Settings.settingsBuilder();
        for (Map.Entry<String, String> entry : toApply.getAsMap().entrySet()) {
            if (entry.getValue() == null) {
                toRemove.add(entry.getKey());
            } else if ((onlyDynamic == false && get(entry.getKey()) != null) || hasDynamicSetting(entry.getKey())) {
                validate(entry.getKey(), toApply);
                settingsBuilder.put(entry.getKey(), entry.getValue());
                updates.put(entry.getKey(), entry.getValue());
                changed = true;
            } else {
                throw new IllegalArgumentException(type + " setting [" + entry.getKey() + "], not dynamically updateable");
            }

        }
        changed |= applyDeletes(toRemove, target);
        target.put(settingsBuilder.build());
        return changed;
    }

    private static final boolean applyDeletes(Set<String> deletes, Settings.Builder builder) {
        boolean changed = false;
        for (String entry : deletes) {
            Set<String> keysToRemove = new HashSet<>();
            Set<String> keySet = builder.internalMap().keySet();
            for (String key : keySet) {
                if (Regex.simpleMatch(entry, key)) {
                    keysToRemove.add(key);
                }
            }
            for (String key : keysToRemove) {
                builder.remove(key);
                changed = true;
            }
        }
        return changed;
    }

}
