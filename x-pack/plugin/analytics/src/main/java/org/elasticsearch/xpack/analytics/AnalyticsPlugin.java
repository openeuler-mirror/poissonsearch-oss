/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.analytics;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ContextParser;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.license.LicenseUtils;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.MapperPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.SearchPlugin;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.ResourceWatcherService;
import org.elasticsearch.xpack.analytics.action.TransportAnalyticsStatsAction;
import org.elasticsearch.xpack.analytics.boxplot.BoxplotAggregationBuilder;
import org.elasticsearch.xpack.analytics.boxplot.InternalBoxplot;
import org.elasticsearch.xpack.analytics.cumulativecardinality.CumulativeCardinalityPipelineAggregationBuilder;
import org.elasticsearch.xpack.analytics.cumulativecardinality.CumulativeCardinalityPipelineAggregator;
import org.elasticsearch.xpack.analytics.mapper.HistogramFieldMapper;
import org.elasticsearch.xpack.analytics.stringstats.InternalStringStats;
import org.elasticsearch.xpack.analytics.stringstats.StringStatsAggregationBuilder;
import org.elasticsearch.xpack.analytics.topmetrics.InternalTopMetrics;
import org.elasticsearch.xpack.analytics.topmetrics.TopMetricsAggregationBuilder;
import org.elasticsearch.xpack.analytics.topmetrics.TopMetricsAggregatorFactory;
import org.elasticsearch.xpack.core.XPackField;
import org.elasticsearch.xpack.core.XPackPlugin;
import org.elasticsearch.xpack.core.analytics.action.AnalyticsStatsAction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static java.util.Collections.singletonList;

public class AnalyticsPlugin extends Plugin implements SearchPlugin, ActionPlugin, MapperPlugin {
    private final AnalyticsUsage usage = new AnalyticsUsage();
    private final boolean transportClientMode;

    public AnalyticsPlugin(Settings settings) {
        this.transportClientMode = XPackPlugin.transportClientMode(settings);
    }

    public static XPackLicenseState getLicenseState() { return XPackPlugin.getSharedLicenseState(); }

    @Override
    public List<PipelineAggregationSpec> getPipelineAggregations() {
        return singletonList(
            new PipelineAggregationSpec(
                CumulativeCardinalityPipelineAggregationBuilder.NAME,
                CumulativeCardinalityPipelineAggregationBuilder::new,
                CumulativeCardinalityPipelineAggregator::new,
                usage.track(AnalyticsUsage.Item.CUMULATIVE_CARDINALITY,
                        checkLicense(CumulativeCardinalityPipelineAggregationBuilder.PARSER)))
        );
    }

    @Override
    public List<AggregationSpec> getAggregations() {
        return Arrays.asList(
            new AggregationSpec(
                StringStatsAggregationBuilder.NAME,
                StringStatsAggregationBuilder::new,
                usage.track(AnalyticsUsage.Item.STRING_STATS, checkLicense(StringStatsAggregationBuilder.PARSER)))
                .addResultReader(InternalStringStats::new),
            new AggregationSpec(
                BoxplotAggregationBuilder.NAME,
                BoxplotAggregationBuilder::new,
                usage.track(AnalyticsUsage.Item.BOXPLOT, checkLicense(BoxplotAggregationBuilder.PARSER)))
                .addResultReader(InternalBoxplot::new),
            new AggregationSpec(
                TopMetricsAggregationBuilder.NAME,
                TopMetricsAggregationBuilder::new,
                usage.track(AnalyticsUsage.Item.TOP_METRICS, checkLicense(TopMetricsAggregationBuilder.PARSER)))
                .addResultReader(InternalTopMetrics::new)
        );
    }

    @Override
    public List<ActionPlugin.ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
        return singletonList(
            new ActionHandler<>(AnalyticsStatsAction.INSTANCE, TransportAnalyticsStatsAction.class));
    }

    @Override
    public Collection<Module> createGuiceModules() {
        List<Module> modules = new ArrayList<>();

        if (transportClientMode) {
            return modules;
        }

        modules.add(b -> XPackPlugin.bindFeatureSet(b, AnalyticsFeatureSet.class));
        return modules;
    }

    @Override
    public List<Setting<?>> getSettings() {
        return singletonList(TopMetricsAggregatorFactory.MAX_BUCKET_SIZE);
    }

    @Override
    public Map<String, Mapper.TypeParser> getMappers() {
        return Collections.singletonMap(HistogramFieldMapper.CONTENT_TYPE, new HistogramFieldMapper.TypeParser());
    }

    @Override
    public Collection<Object> createComponents(Client client, ClusterService clusterService, ThreadPool threadPool,
            ResourceWatcherService resourceWatcherService, ScriptService scriptService, NamedXContentRegistry xContentRegistry,
            Environment environment, NodeEnvironment nodeEnvironment, NamedWriteableRegistry namedWriteableRegistry,
            IndexNameExpressionResolver indexNameExpressionResolver) {
        return singletonList(new AnalyticsUsage());
    }

    private static <T> ContextParser<String, T> checkLicense(ContextParser<String, T> realParser) {
        return (parser, name) -> {
            if (getLicenseState().isAnalyticsAllowed() == false) {
                throw LicenseUtils.newComplianceException(XPackField.ANALYTICS);
            }
            return realParser.parse(parser, name);
        };
    }
}
