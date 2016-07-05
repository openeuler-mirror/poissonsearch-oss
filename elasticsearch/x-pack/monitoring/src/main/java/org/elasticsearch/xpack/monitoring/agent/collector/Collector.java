/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.monitoring.agent.collector;

import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.xpack.monitoring.agent.exporter.MonitoringDoc;

import java.util.Collection;

public interface Collector extends LifecycleComponent {

    String name();

    Collection<MonitoringDoc> collect();
}