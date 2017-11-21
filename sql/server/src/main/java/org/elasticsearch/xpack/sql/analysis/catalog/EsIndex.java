/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.analysis.catalog;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.xpack.sql.type.DataType;
import org.elasticsearch.xpack.sql.util.StringUtils;

import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

public class EsIndex {

    public static final EsIndex NOT_FOUND = new EsIndex(StringUtils.EMPTY, emptyMap(), emptyList(), Settings.EMPTY);

    // TODO Double check that we need these and that we're securing them properly.
    // Tracked by https://github.com/elastic/x-pack-elasticsearch/issues/3076
    private final String name;
    private final Map<String, DataType> mapping;
    private final List<String> aliases;
    private final Settings settings;

    public EsIndex(String name, Map<String, DataType> mapping, List<String> aliases, Settings settings) {
        this.name = name;
        this.mapping = mapping;
        this.aliases = aliases;
        this.settings = settings;
    }

    public String name() {
        return name;
    }

    public Map<String, DataType> mapping() {
        return mapping;
    }

    public List<String> aliases() {
        return aliases;
    }

    public Settings settings() {
        return settings;
    }

    @Override
    public String toString() {
        return name;
    }
}
