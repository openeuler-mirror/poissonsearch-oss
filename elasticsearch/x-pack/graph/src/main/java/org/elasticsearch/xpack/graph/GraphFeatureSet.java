/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.graph;

import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.xpack.XPackFeatureSet;

import java.io.IOException;

/**
 *
 */
public class GraphFeatureSet implements XPackFeatureSet {

    private final boolean enabled;
    private final XPackLicenseState licenseState;

    @Inject
    public GraphFeatureSet(Settings settings, @Nullable XPackLicenseState licenseState, NamedWriteableRegistry namedWriteableRegistry) {
        this.enabled = Graph.enabled(settings);
        this.licenseState = licenseState;
        namedWriteableRegistry.register(Usage.class, Usage.writeableName(Graph.NAME), Usage::new);
    }

    @Override
    public String name() {
        return Graph.NAME;
    }

    @Override
    public String description() {
        return "Graph Data Exploration for the Elastic Stack";
    }

    @Override
    public boolean available() {
        return licenseState != null && licenseState.isGraphAllowed();
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public XPackFeatureSet.Usage usage() {
        return new Usage(available(), enabled());
    }

    static class Usage extends XPackFeatureSet.Usage {

        public Usage(StreamInput input) throws IOException {
            super(input);
        }

        public Usage(boolean available, boolean enabled) {
            super(Graph.NAME, available, enabled);
        }
    }
}
