/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.monitoring.exporter;

import org.elasticsearch.xpack.template.TemplateUtils;

import java.util.Locale;
import java.util.regex.Pattern;

public final class MonitoringTemplateUtils {

    private static final String TEMPLATE_FILE = "/monitoring-%s.json";
    private static final String TEMPLATE_VERSION_PROPERTY = Pattern.quote("${monitoring.template.version}");

    /** Current version of es and data templates **/
    public static final String TEMPLATE_VERSION = "2";

    private MonitoringTemplateUtils() {
    }

    public static String loadTemplate(String id) {
        String resource = String.format(Locale.ROOT, TEMPLATE_FILE, id);
        return TemplateUtils.loadTemplate(resource, TEMPLATE_VERSION, TEMPLATE_VERSION_PROPERTY);
    }
}
