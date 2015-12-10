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
import org.elasticsearch.common.logging.ESLoggerFactory;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A basic setting service that can be used for per-index and per-cluster settings.
 * This service offers transactional application of updates settings.
 */
public abstract class AbstractScopedSettings extends AbstractComponent {
    private Settings lastSettingsApplied;
    private final List<SettingUpdater> settingUpdaters = new ArrayList<>();
    private final Map<String, Setting<?>> groupSettings = new HashMap<>();
    private final Map<String, Setting<?>> keySettings = new HashMap<>();
    private final Setting.Scope scope;

    protected AbstractScopedSettings(Settings settings, Set<Setting<?>> settingsSet, Setting.Scope scope) {
        super(settings);
        for (Setting<?> entry : settingsSet) {
            if (entry.getScope() != scope) {
                throw new IllegalArgumentException("Setting must be a cluster setting but was: " + entry.getScope());
            }
            if (entry.isGroupSetting()) {
                groupSettings.put(entry.getKey(), entry);
            } else {
                keySettings.put(entry.getKey(), entry);
            }
        }
        this.scope = scope;
    }

    public Setting.Scope getScope() {
        return this.scope;
    }

    /**
     * Applies the given settings to all listeners and rolls back the result after application. This
     * method will not change any settings but will fail if any of the settings can't be applied.
     */
    public synchronized Settings dryRun(Settings settings) {
        final Settings build = Settings.builder().put(this.settings).put(settings).build();
        try {
            List<RuntimeException> exceptions = new ArrayList<>();
            for (SettingUpdater settingUpdater : settingUpdaters) {
                try {
                    settingUpdater.prepareApply(build);
                } catch (RuntimeException ex) {
                    exceptions.add(ex);
                    logger.debug("failed to prepareCommit settings for [{}]", ex, settingUpdater);
                }
            }
            // here we are exhaustive and record all settings that failed.
            ExceptionsHelper.rethrowAndSuppress(exceptions);
        } finally {
            for (SettingUpdater settingUpdater : settingUpdaters) {
                try {
                    settingUpdater.rollback();
                } catch (Exception e) {
                    logger.error("failed to rollback settings for [{}]", e, settingUpdater);
                }
            }
        }
        return build;
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
        final Settings build = Settings.builder().put(this.settings).put(newSettings).build();
        boolean success = false;
        try {
            for (SettingUpdater settingUpdater : settingUpdaters) {
                try {
                    settingUpdater.prepareApply(build);
                } catch (Exception ex) {
                    logger.warn("failed to prepareCommit settings for [{}]", ex, settingUpdater);
                    throw ex;
                }
            }
            for (SettingUpdater settingUpdater : settingUpdaters) {
                settingUpdater.apply();
            }
            success = true;
        } catch (Exception ex) {
            logger.warn("failed to apply settings", ex);
            throw ex;
        } finally {
            if (success == false) {
                for (SettingUpdater settingUpdater : settingUpdaters) {
                    try {
                        settingUpdater.rollback();
                    } catch (Exception e) {
                        logger.error("failed to refresh settings for [{}]", e, settingUpdater);
                    }
                }
            }
        }
        return lastSettingsApplied = newSettings;
    }

    /**
     * Adds a settings consumer with a predicate that is only evaluated at update time.
     * <p>
     * Note: Only settings registered in {@link SettingsModule} can be changed dynamically.
     * </p>
     */
    public synchronized <T> void addSettingsUpdateConsumer(Setting<T> setting, Consumer<T> consumer, Consumer<T> predicate) {
        if (setting != get(setting.getKey())) {
            throw new IllegalArgumentException("Setting is not registered for key [" + setting.getKey() + "]");
        }
        this.settingUpdaters.add(setting.newUpdater(consumer, logger, settings, predicate));
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
        this.settingUpdaters.add(Setting.compoundUpdater(consumer, a, b, logger, settings));
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
     * Transactional interface to update settings.
     * @see Setting
     */
    public interface SettingUpdater {
        /**
         * Prepares applying the given settings to this updater. All the heavy lifting like parsing and validation
         * happens in this method. Yet the actual setting should not be changed by this call.
         * @param settings the settings to apply
         * @return <code>true</code> if this updater will update a setting on calling {@link #apply()} otherwise <code>false</code>
         */
        boolean prepareApply(Settings settings);

        /**
         * Applies the settings passed to {@link #prepareApply(Settings)}
         */
        void apply();

        /**
         * Rolls back to the state before {@link #prepareApply(Settings)} was called. All internal prepared state is cleared after this call.
         */
        void rollback();
    }

    /**
     * Returns the {@link Setting} for the given key or <code>null</code> if the setting can not be found.
     */
    public Setting get(String key) {
        Setting<?> setting = keySettings.get(key);
        if (setting == null) {
            for (Map.Entry<String, Setting<?>> entry : groupSettings.entrySet()) {
                if (entry.getValue().match(key)) {
                    return entry.getValue();
                }
            }
        } else {
            return setting;
        }
        return null;
    }

    /**
     * Returns <code>true</code> if the setting for the given key is dynamically updateable. Otherwise <code>false</code>.
     */
    public boolean hasDynamicSetting(String key) {
        final Setting setting = get(key);
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

}
