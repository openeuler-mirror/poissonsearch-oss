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

package org.elasticsearch.common.util;

import org.elasticsearch.common.inject.Binder;
import org.elasticsearch.common.inject.multibindings.MapBinder;
import org.elasticsearch.common.inject.multibindings.Multibinder;
import org.elasticsearch.common.settings.Settings;

import java.util.*;

/**
 * This class defines an official elasticsearch extension point. It registers
 * all extensions by a single name and ensures that extensions are not registered
 * more than once.
 */
public abstract class ExtensionPoint<T> {
    protected final String name;
    protected final Class<T> extensionClass;
    protected final Class<?>[] singletons;

    /**
     * Creates a new extension point
     *
     * @param name           the human readable underscore case name of the extension poing. This is used in error messages etc.
     * @param extensionClass the base class that should be extended
     * @param singletons     a list of singletons to bind with this extension point - these are bound in {@link #bind(Binder)}
     */
    public ExtensionPoint(String name, Class<T> extensionClass, Class<?>... singletons) {
        this.name = name;
        this.extensionClass = extensionClass;
        this.singletons = singletons;
    }

    /**
     * Binds the extension as well as the singletons to the given guice binder.
     *
     * @param binder the binder to use
     */
    public final void bind(Binder binder) {
        if (singletons == null || singletons.length == 0) {
            throw new IllegalStateException("Can't bind empty or null singletons");
        }
        for (Class<?> c : singletons) {
            binder.bind(c).asEagerSingleton();
        }
        bindExtensions(binder);
    }

    /**
     * Subclasses can bind their type, map or set exentions here.
     */
    protected abstract void bindExtensions(Binder binder);

    /**
     * A map based extension point which allows to register keyed implementations ie. parsers or some kind of strategies.
     */
    public static class MapExtensionPoint<T> extends ExtensionPoint<T> {
        private final Map<String, Class<? extends T>> extensions = new HashMap<>();
        private final Set<String> reservedKeys;

        /**
         * Creates a new {@link org.elasticsearch.common.util.ExtensionPoint.MapExtensionPoint}
         *
         * @param name           the human readable underscore case name of the extension poing. This is used in error messages etc.
         * @param extensionClass the base class that should be extended
         * @param singletons     a list of singletons to bind with this extension point - these are bound in {@link #bind(Binder)}
         * @param reservedKeys   a set of reserved keys by internal implementations
         */
        public MapExtensionPoint(String name, Class<T> extensionClass, Set<String> reservedKeys, Class<?>... singletons) {
            super(name, extensionClass, singletons);
            this.reservedKeys = reservedKeys;

        }

        /**
         * Returns the extension for the given key or <code>null</code>
         */
        public Class<? extends T> getExtension(String type) {
            return extensions.get(type);
        }

        /**
         * Registers an extension class for a given key. This method will thr
         *
         * @param key       the extensions key
         * @param extension the extension
         * @throws IllegalArgumentException iff the key is already registered or if the key is a reserved key for an internal implementation
         */
        public final void registerExtension(String key, Class<? extends T> extension) {
            if (extensions.containsKey(key) || reservedKeys.contains(key)) {
                throw new IllegalArgumentException("Can't register the same [" + this.name + "] more than once for [" + key + "]");
            }
            extensions.put(key, extension);
        }

        @Override
        protected final void bindExtensions(Binder binder) {
            MapBinder<String, T> parserMapBinder = MapBinder.newMapBinder(binder, String.class, extensionClass);
            for (Map.Entry<String, Class<? extends T>> clazz : extensions.entrySet()) {
                parserMapBinder.addBinding(clazz.getKey()).to(clazz.getValue());
            }
        }
    }

    /**
     * A Type extension point which basically allows to registerd keyed extensions like {@link org.elasticsearch.common.util.ExtensionPoint.MapExtensionPoint}
     * but doesn't instantiate and bind all the registered key value pairs but instead replace a singleton based on a given setting via {@link #bindType(Binder, Settings, String, String)}
     * Note: {@link #bind(Binder)} is not supported by this class
     */
    public static final class TypeExtensionPoint<T> extends MapExtensionPoint<T> {

        public TypeExtensionPoint(String name, Class<T> extensionClass) {
            super(name, extensionClass, Collections.EMPTY_SET);
        }

        /**
         * Binds the extension class to the class that is registered for the give configured for the settings key in
         * the settings object.
         *
         * @param binder       the binder to use
         * @param settings     the settings to look up the key to find the implemetation to bind
         * @param settingsKey  the key to use with the settings
         * @param defaultValue the default value if they settings doesn't contain the key
         * @return the actual bound type key
         */
        public String bindType(Binder binder, Settings settings, String settingsKey, String defaultValue) {
            final String type = settings.get(settingsKey, defaultValue);
            final Class<? extends T> instance = getExtension(type);
            if (instance == null) {
                throw new IllegalArgumentException("Unknown [" + this.name + "] type [" + type + "]");
            }
            binder.bind(extensionClass).to(instance).asEagerSingleton();
            return type;
        }

    }

    /**
     * A set based extension point which allows to register extended classes that might be used to chain additional functionality etc.
     */
    public final static class SetExtensionPoint<T> extends ExtensionPoint<T> {
        private final Set<Class<? extends T>> extensions = new HashSet<>();

        /**
         * Creates a new {@link org.elasticsearch.common.util.ExtensionPoint.SetExtensionPoint}
         *
         * @param name           the human readable underscore case name of the extension poing. This is used in error messages etc.
         * @param extensionClass the base class that should be extended
         * @param singletons     a list of singletons to bind with this extension point - these are bound in {@link #bind(Binder)}
         */
        public SetExtensionPoint(String name, Class<T> extensionClass, Class<?>... singletons) {
            super(name, extensionClass, singletons);
        }

        /**
         * Registers a new extension
         *
         * @param extension the extension to register
         * @throws IllegalArgumentException iff the class is already registered
         */
        public final void registerExtension(Class<? extends T> extension) {
            if (extensions.contains(extension)) {
                throw new IllegalArgumentException("Can't register the same [" + this.name + "] more than once for [" + extension.getName() + "]");
            }
            extensions.add(extension);
        }

        @Override
        protected final void bindExtensions(Binder binder) {
            Multibinder<T> allocationMultibinder = Multibinder.newSetBinder(binder, extensionClass);
            for (Class<? extends T> clazz : extensions) {
                allocationMultibinder.addBinding().to(clazz);
            }
        }
    }
}
