/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.monitoring.exporter.local;

import com.carrotsearch.hppc.cursors.ObjectObjectCursor;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.util.Supplier;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesAction;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest.AliasActions;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesResponse;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexResponse;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateResponse;
import org.elasticsearch.action.ingest.PutPipelineRequest;
import org.elasticsearch.action.ingest.WritePipelineResponse;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.inject.internal.Nullable;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.regex.Regex;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.gateway.GatewayService;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.ingest.IngestMetadata;
import org.elasticsearch.ingest.PipelineConfiguration;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.xpack.XPackClient;
import org.elasticsearch.xpack.XPackSettings;
import org.elasticsearch.xpack.monitoring.cleaner.CleanerService;
import org.elasticsearch.xpack.monitoring.exporter.ClusterAlertsUtil;
import org.elasticsearch.xpack.monitoring.exporter.Exporter;
import org.elasticsearch.xpack.monitoring.exporter.MonitoringDoc;
import org.elasticsearch.xpack.monitoring.exporter.MonitoringTemplateUtils;
import org.elasticsearch.xpack.monitoring.resolver.MonitoringIndexNameResolver;
import org.elasticsearch.xpack.monitoring.resolver.ResolversRegistry;
import org.elasticsearch.xpack.security.InternalClient;
import org.elasticsearch.xpack.watcher.client.WatcherClient;
import org.elasticsearch.xpack.watcher.transport.actions.delete.DeleteWatchRequest;
import org.elasticsearch.xpack.watcher.transport.actions.get.GetWatchRequest;
import org.elasticsearch.xpack.watcher.transport.actions.get.GetWatchResponse;
import org.elasticsearch.xpack.watcher.transport.actions.put.PutWatchRequest;
import org.elasticsearch.xpack.watcher.watch.Watch;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.elasticsearch.common.Strings.collectionToCommaDelimitedString;
import static org.elasticsearch.xpack.monitoring.exporter.MonitoringTemplateUtils.LAST_UPDATED_VERSION;
import static org.elasticsearch.xpack.monitoring.exporter.MonitoringTemplateUtils.PIPELINE_IDS;
import static org.elasticsearch.xpack.monitoring.exporter.MonitoringTemplateUtils.loadPipeline;
import static org.elasticsearch.xpack.monitoring.exporter.MonitoringTemplateUtils.pipelineName;

public class LocalExporter extends Exporter implements ClusterStateListener, CleanerService.Listener {

    private static final Logger logger = Loggers.getLogger(LocalExporter.class);

    public static final String TYPE = "local";

    private final InternalClient client;
    private final ClusterService clusterService;
    private final XPackLicenseState licenseState;
    private final ResolversRegistry resolvers;
    private final CleanerService cleanerService;
    private final boolean useIngest;

    private final AtomicReference<State> state = new AtomicReference<>(State.INITIALIZED);
    private final AtomicBoolean installingSomething = new AtomicBoolean(false);
    private final AtomicBoolean waitedForSetup = new AtomicBoolean(false);
    private final AtomicBoolean watcherSetup = new AtomicBoolean(false);

    public LocalExporter(Exporter.Config config, InternalClient client, CleanerService cleanerService) {
        super(config);
        this.client = client;
        this.clusterService = config.clusterService();
        this.licenseState = config.licenseState();
        this.useIngest = config.settings().getAsBoolean(USE_INGEST_PIPELINE_SETTING, true);
        this.cleanerService = cleanerService;
        this.resolvers = new ResolversRegistry(config.settings());

        // Checks that required templates are loaded
        for (MonitoringIndexNameResolver resolver : resolvers) {
            if (resolver.template() == null) {
                throw new IllegalStateException("unable to find built-in template " + resolver.templateName());
            }
        }

        clusterService.addListener(this);
        cleanerService.add(this);
        licenseState.addListener(this::licenseChanged);
    }

    ResolversRegistry getResolvers() {
        return resolvers;
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        if (state.get() == State.INITIALIZED) {
            resolveBulk(event.state(), true);
        }
    }

    /**
     * When the license changes, we need to ensure that Watcher is setup properly.
     */
    private void licenseChanged() {
        watcherSetup.set(false);
    }

    /**
     * Determine if this {@link LocalExporter} is ready to use.
     *
     * @return {@code true} if it is ready. {@code false} if not.
     */
    boolean isExporterReady() {
        // forces the setup to occur if it hasn't already
        final boolean running = resolveBulk(clusterService.state(), false) != null;

        return running && installingSomething.get() == false;
    }

    @Override
    public LocalBulk openBulk() {
        if (state.get() != State.RUNNING) {
            return null;
        }
        return resolveBulk(clusterService.state(), false);
    }

    @Override
    public void doClose() {
        if (state.getAndSet(State.TERMINATED) != State.TERMINATED) {
            logger.trace("stopped");
            // we also remove the listener in resolveBulk after we get to RUNNING, but it's okay to double-remove
            clusterService.removeListener(this);
            cleanerService.remove(this);
            licenseState.removeListener(this::licenseChanged);
        }
    }

    LocalBulk resolveBulk(ClusterState clusterState, boolean clusterStateChange) {
        if (clusterService.localNode() == null || clusterState == null) {
            return null;
        }

        if (clusterState.blocks().hasGlobalBlock(GatewayService.STATE_NOT_RECOVERED_BLOCK)) {
            // wait until the gateway has recovered from disk, otherwise we think may not have .monitoring-es-
            // indices but they may not have been restored from the cluster state on disk
            logger.debug("waiting until gateway has recovered from disk");
            return null;
        }

        // List of distinct templates
        final Map<String, String> templates = StreamSupport.stream(new ResolversRegistry(Settings.EMPTY).spliterator(), false)
                .collect(Collectors.toMap(MonitoringIndexNameResolver::templateName, MonitoringIndexNameResolver::template, (a, b) -> a));

        // templates not managed by resolvers
        // TODO: this should just become "templates" when we remove resolvers (and the above templates will disappear as a result)
        final Map<String, String> nonResolverTemplates = Arrays.stream(MonitoringTemplateUtils.TEMPLATE_IDS)
                .collect(Collectors.toMap(MonitoringTemplateUtils::templateName, MonitoringTemplateUtils::loadTemplate));

        // add templates that don't come from resolvers
        templates.putAll(nonResolverTemplates);

        boolean setup = true;

        // elected master node needs to setup templates; non-master nodes need to wait for it to be setup
        if (clusterService.state().nodes().isLocalNodeElectedMaster()) {
            setup = setupIfElectedMaster(clusterState, templates, clusterStateChange);
        } else if (setupIfNotElectedMaster(clusterState, templates.keySet()) == false) {
            // the first pass will be false so that we don't bother users if the master took one-go to setup
            if (waitedForSetup.getAndSet(true)) {
                logger.info("waiting for elected master node [{}] to setup local exporter [{}] (does it have x-pack installed?)",
                            clusterService.state().nodes().getMasterNode(), config.name());
            }

            setup = false;
        }

        // any failure/delay to setup the local exporter stops it until the next pass (10s by default)
        if (setup == false) {
            return null;
        }

        if (state.compareAndSet(State.INITIALIZED, State.RUNNING)) {
            logger.debug("started");

            // we no longer need to receive cluster state updates
            clusterService.removeListener(this);
        }

        return new LocalBulk(name(), logger, client, resolvers, useIngest);
    }

    /**
     * When not on the elected master, we require all resources (mapping types, templates, and pipelines) to be available before we
     * attempt to run the exporter. If those resources do not exist, then it means the elected master's exporter has not yet run, so the
     * monitoring cluster (this one, as the local exporter) is not setup yet.
     *
     * @param clusterState The current cluster state.
     * @param templates All template names that should exist.
     * @return {@code true} indicates that all resources are available and the exporter can be used. {@code false} to stop and wait.
     */
    private boolean setupIfNotElectedMaster(final ClusterState clusterState, final Set<String> templates) {
        // any required template is not yet installed in the given cluster state, we'll wait.
        for (final String template : templates) {
            if (hasTemplate(clusterState, template) == false) {
                logger.debug("monitoring index template [{}] does not exist, so service cannot start (waiting on master)",
                             template);
                return false;
            }
        }

        // if we don't have the ingest pipeline, then it's going to fail anyway
        if (useIngest) {
            for (final String pipelineId : PIPELINE_IDS) {
                if (hasIngestPipeline(clusterState, pipelineId) == false) {
                    logger.debug("monitoring ingest pipeline [{}] does not exist, so service cannot start (waiting on master)",
                                 pipelineName(pipelineId));
                    return false;
                }
            }
        }

        if (null != prepareAddAliasesTo2xIndices(clusterState)) {
            logger.debug("old monitoring indexes exist without aliases, waiting for them to get new aliases");
            return false;
        }

        logger.trace("monitoring index templates and pipelines are installed, service can start");

        // everything is setup
        return true;
    }

    /**
     * When on the elected master, we setup all resources (mapping types, templates, and pipelines) before we attempt to run the exporter.
     * If those resources do not exist, then we will create them.
     *
     * @param clusterState The current cluster state.
     * @param templates All template names that should exist.
     * @param clusterStateChange {@code true} if a cluster state change caused this call (don't block it!)
     * @return {@code true} indicates that all resources are "ready" and the exporter can be used. {@code false} to stop and wait.
     */
    private boolean setupIfElectedMaster(final ClusterState clusterState, final Map<String, String> templates,
                                         final boolean clusterStateChange) {
        // we are on the elected master
        // Check that there is nothing that could block metadata updates
        if (clusterState.blocks().hasGlobalBlock(ClusterBlockLevel.METADATA_WRITE)) {
            logger.debug("waiting until metadata writes are unblocked");
            return false;
        }

        if (installingSomething.get() == true) {
            logger.trace("already installing something, waiting for install to complete");
            return false;
        }

        // build a list of runnables for everything that is missing, but do not start execution
        final List<Runnable> asyncActions = new ArrayList<>();
        final AtomicInteger pendingResponses = new AtomicInteger(0);

        // Check that each required template exists, installing it if needed
        final List<Entry<String, String>> missingTemplates = templates.entrySet()
                .stream()
                .filter((e) -> hasTemplate(clusterState, e.getKey()) == false)
                .collect(Collectors.toList());

        if (missingTemplates.isEmpty() == false) {
            logger.debug((Supplier<?>) () -> new ParameterizedMessage("template {} not found",
                    missingTemplates.stream().map(Map.Entry::getKey).collect(Collectors.toList())));
            for (Entry<String, String> template : missingTemplates) {
                asyncActions.add(() -> putTemplate(template.getKey(), template.getValue(),
                        new ResponseActionListener<>("template", template.getKey(), pendingResponses)));
            }
        }

        if (useIngest) {
            final List<String> missingPipelines = Arrays.stream(PIPELINE_IDS)
                    .filter(id -> hasIngestPipeline(clusterState, id) == false)
                    .collect(Collectors.toList());

            // if we don't have the ingest pipeline, then install it
            if (missingPipelines.isEmpty() == false) {
                for (final String pipelineId : missingPipelines) {
                    final String pipelineName = pipelineName(pipelineId);
                    logger.debug("pipeline [{}] not found", pipelineName);
                    asyncActions.add(() -> putIngestPipeline(pipelineId,
                                                             new ResponseActionListener<>("pipeline",
                                                                                          pipelineName,
                                                                                          pendingResponses)));
                }
            } else {
                logger.trace("all pipelines found");
            }
        }

        IndicesAliasesRequest addAliasesTo2xIndices = prepareAddAliasesTo2xIndices(clusterState);
        if (addAliasesTo2xIndices == null) {
            logger.trace("there are no 2.x monitoring indices or they have all the aliases they need");
        } else {
            final List<String> monitoringIndices2x =  addAliasesTo2xIndices.getAliasActions().stream()
                    .flatMap((a) -> Arrays.stream(a.indices()))
                    .collect(Collectors.toList());
            logger.debug("there are 2.x monitoring indices {} and they are missing some aliases to make them compatible with 5.x",
                    monitoringIndices2x);
            asyncActions.add(() -> client.execute(IndicesAliasesAction.INSTANCE, addAliasesTo2xIndices,
                    new ActionListener<IndicesAliasesResponse>() {
                        @Override
                        public void onResponse(IndicesAliasesResponse response) {
                            responseReceived(pendingResponses, true, null);
                            if (response.isAcknowledged()) {
                                logger.info("Added modern aliases to 2.x monitoring indices {}", monitoringIndices2x);
                            } else {
                                logger.info("Unable to add modern aliases to 2.x monitoring indices {}, response not acknowledged.",
                                        monitoringIndices2x);
                            }
                        }

                        @Override
                        public void onFailure(Exception e) {
                            responseReceived(pendingResponses, false, null);
                            logger.error((Supplier<?>)
                                    () -> new ParameterizedMessage("Unable to add modern aliases to 2.x monitoring indices {}",
                                            monitoringIndices2x), e);
                        }
                    }));
        }

        // avoid constantly trying to setup Watcher, which requires a lot of overhead and avoid attempting to setup during a cluster state
        // change
        if (state.get() == State.RUNNING && clusterStateChange == false && canUseWatcher()) {
            final IndexRoutingTable watches = clusterState.routingTable().index(Watch.INDEX);
            final boolean indexExists = watches != null && watches.allPrimaryShardsActive();

            // we cannot do anything with watches until the index is allocated, so we wait until it's ready
            if (watches != null && watches.allPrimaryShardsActive() == false) {
                logger.trace("cannot manage cluster alerts because [.watches] index is not allocated");
            } else if ((watches == null || indexExists) && watcherSetup.compareAndSet(false, true)) {
                installClusterAlerts(indexExists, asyncActions, pendingResponses);
            }
        }

        if (asyncActions.size() > 0) {
            if (installingSomething.compareAndSet(false, true)) {
                pendingResponses.set(asyncActions.size());
                asyncActions.forEach(Runnable::run);
            } else {
                // let the cluster catch up since requested installations may be ongoing
                return false;
            }
        } else {
            logger.debug("monitoring index templates and pipelines are installed on master node, service can start");
        }

        // everything is setup (or running)
        return true;
    }

    private void responseReceived(final AtomicInteger pendingResponses, final boolean success, final @Nullable AtomicBoolean setup) {
        if (setup != null && success == false) {
            setup.set(false);
        }

        if (pendingResponses.decrementAndGet() <= 0) {
            logger.trace("all installation requests returned a response");
            if (installingSomething.compareAndSet(true, false) == false) {
                throw new IllegalStateException("could not reset installing flag to false");
            }
        }
    }

    /**
     * Determine if the ingest pipeline for {@code pipelineId} exists in the cluster or not with an appropriate minimum version.
     *
     * @param clusterState The current cluster state
     * @param pipelineId The ID of the pipeline to check (e.g., "3")
     * @return {@code true} if the {@code clusterState} contains the pipeline with an appropriate minimum version
     */
    private boolean hasIngestPipeline(final ClusterState clusterState, final String pipelineId) {
        final String pipelineName = MonitoringTemplateUtils.pipelineName(pipelineId);
        final IngestMetadata ingestMetadata = clusterState.getMetaData().custom(IngestMetadata.TYPE);

        // we ensure that we both have the pipeline and its version represents the current (or later) version
        if (ingestMetadata != null) {
            final PipelineConfiguration pipeline = ingestMetadata.getPipelines().get(pipelineName);

            return pipeline != null && hasValidVersion(pipeline.getConfigAsMap().get("version"), LAST_UPDATED_VERSION);
        }

        return false;
    }

    /**
     * Create the pipeline required to handle past data as well as to future-proof ingestion for <em>current</em> documents (the pipeline
     * is initially empty, but it can be replaced later with one that translates it as-needed).
     * <p>
     * This should only be invoked by the <em>elected</em> master node.
     * <p>
     * Whenever we eventually make a backwards incompatible change, then we need to override any pipeline that already exists that is
     * older than this one. This uses the Elasticsearch version, down to the alpha portion, to determine the version of the last change.
     * <pre><code>
     * {
     *   "description": "...",
     *   "pipelines" : [ ... ],
     *   "version": 6000001
     * }
     * </code></pre>
     */
    private void putIngestPipeline(final String pipelineId, final ActionListener<WritePipelineResponse> listener) {
        final String pipelineName = pipelineName(pipelineId);
        final BytesReference pipeline = loadPipeline(pipelineId, XContentType.JSON).bytes();
        final PutPipelineRequest request = new PutPipelineRequest(pipelineName, pipeline, XContentType.JSON);

        logger.debug("installing ingest pipeline [{}]", pipelineName);

        client.admin().cluster().putPipeline(request, listener);
    }

    private boolean hasTemplate(final ClusterState clusterState, final String templateName) {
        final IndexTemplateMetaData template = clusterState.getMetaData().getTemplates().get(templateName);

        return template != null && hasValidVersion(template.getVersion(), LAST_UPDATED_VERSION);
    }

    private void putTemplate(String template, String source, ActionListener<PutIndexTemplateResponse> listener) {
        logger.debug("installing template [{}]", template);

        PutIndexTemplateRequest request = new PutIndexTemplateRequest(template).source(source, XContentType.JSON);
        assert !Thread.currentThread().isInterrupted() : "current thread has been interrupted before putting index template!!!";

        // async call, so we won't block cluster event thread
        client.admin().indices().putTemplate(request, listener);
    }

    /**
     * Determine if the {@code version} is defined and greater than or equal to the {@code minimumVersion}.
     *
     * @param version The version to check
     * @param minimumVersion The minimum version required to be a "valid" version
     * @return {@code true} if the version exists and it's &gt;= to the minimum version. {@code false} otherwise.
     */
    private boolean hasValidVersion(final Object version, final long minimumVersion) {
        return version instanceof Number && ((Number)version).intValue() >= minimumVersion;
    }

    /**
     * Install Cluster Alerts (Watches) into the cluster
     *
     * @param asyncActions Asynchronous actions are added to for each Watch.
     * @param pendingResponses Pending response countdown we use to track completion.
     */
    private void installClusterAlerts(final boolean indexExists, final List<Runnable> asyncActions, final AtomicInteger pendingResponses) {
        final XPackClient xpackClient = new XPackClient(client);
        final WatcherClient watcher = xpackClient.watcher();
        final boolean canAddWatches = licenseState.isMonitoringClusterAlertsAllowed();

        for (final String watchId : ClusterAlertsUtil.WATCH_IDS) {
            final String uniqueWatchId = ClusterAlertsUtil.createUniqueWatchId(clusterService, watchId);

            // we aren't sure if no watches exist yet, so add them
            if (indexExists) {
                if (canAddWatches) {
                    logger.trace("checking monitoring watch [{}]", uniqueWatchId);

                    asyncActions.add(() -> watcher.getWatch(new GetWatchRequest(uniqueWatchId),
                                                            new GetAndPutWatchResponseActionListener(watcher, watchId, uniqueWatchId,
                                                                                                     pendingResponses)));
                } else {
                    logger.trace("pruning monitoring watch [{}]", uniqueWatchId);

                    asyncActions.add(() -> watcher.deleteWatch(new DeleteWatchRequest(uniqueWatchId),
                                                               new ResponseActionListener<>("watch", uniqueWatchId, pendingResponses)));
                }
            } else if (canAddWatches) {
                asyncActions.add(() -> putWatch(watcher, watchId, uniqueWatchId, pendingResponses));
            }
        }
    }

    private void putWatch(final WatcherClient watcher, final String watchId, final String uniqueWatchId,
                          final AtomicInteger pendingResponses) {
        final String watch = ClusterAlertsUtil.loadWatch(clusterService, watchId);

        logger.trace("adding monitoring watch [{}]", uniqueWatchId);

        watcher.putWatch(new PutWatchRequest(uniqueWatchId, new BytesArray(watch), XContentType.JSON),
                         new ResponseActionListener<>("watch", uniqueWatchId, pendingResponses, watcherSetup));
    }

    /**
     * Determine if the cluster can use Watcher.
     *
     * @return {@code true} to use Cluster Alerts.
     */
    private boolean canUseWatcher() {
        return XPackSettings.WATCHER_ENABLED.get(config.globalSettings());
    }

    @Override
    public void onCleanUpIndices(TimeValue retention) {
        if (state.get() != State.RUNNING) {
            logger.debug("exporter not ready");
            return;
        }

        if (clusterService.state().nodes().isLocalNodeElectedMaster()) {
            // Reference date time will be compared to index.creation_date settings,
            // that's why it must be in UTC
            DateTime expiration = new DateTime(DateTimeZone.UTC).minus(retention.millis());
            logger.debug("cleaning indices [expiration={}, retention={}]", expiration, retention);

            ClusterState clusterState = clusterService.state();
            if (clusterState != null) {
                long expirationTime = expiration.getMillis();

                // Get the list of monitoring index patterns
                String[] patterns = StreamSupport.stream(getResolvers().spliterator(), false)
                                                .map(MonitoringIndexNameResolver::indexPattern)
                                                .distinct()
                                                .toArray(String[]::new);

                MonitoringDoc monitoringDoc = new MonitoringDoc(null, null, null, null, null,
                        System.currentTimeMillis(), (MonitoringDoc.Node) null);

                // Get the names of the current monitoring indices
                Set<String> currents = StreamSupport.stream(getResolvers().spliterator(), false)
                                                    .map(r -> r.index(monitoringDoc))
                                                    .collect(Collectors.toSet());

                Set<String> indices = new HashSet<>();
                for (ObjectObjectCursor<String, IndexMetaData> index : clusterState.getMetaData().indices()) {
                    String indexName =  index.key;

                    if (Regex.simpleMatch(patterns, indexName)) {

                        // Never delete the data index or a current index
                        if (currents.contains(indexName)) {
                            continue;
                        }

                        long creationDate = index.value.getCreationDate();
                        if (creationDate <= expirationTime) {
                            if (logger.isDebugEnabled()) {
                                logger.debug("detected expired index [name={}, created={}, expired={}]",
                                        indexName, new DateTime(creationDate, DateTimeZone.UTC), expiration);
                            }
                            indices.add(indexName);
                        }
                    }
                }

                if (!indices.isEmpty()) {
                    logger.info("cleaning up [{}] old indices", indices.size());
                    deleteIndices(indices);
                } else {
                    logger.debug("no old indices found for clean up");
                }
            }
        }
    }

    private void deleteIndices(Set<String> indices) {
        logger.trace("deleting {} indices: [{}]", indices.size(), collectionToCommaDelimitedString(indices));
        client.admin().indices().delete(new DeleteIndexRequest(indices.toArray(new String[indices.size()])),
                new ActionListener<DeleteIndexResponse>() {
            @Override
            public void onResponse(DeleteIndexResponse response) {
                if (response.isAcknowledged()) {
                    logger.debug("{} indices deleted", indices.size());
                } else {
                    // Probably means that the delete request has timed out,
                    // the indices will survive until the next clean up.
                    logger.warn("deletion of {} indices wasn't acknowledged", indices.size());
                }
            }

            @Override
            public void onFailure(Exception e) {
                logger.error("failed to delete indices", e);
            }
        });
    }

    private IndicesAliasesRequest prepareAddAliasesTo2xIndices(ClusterState clusterState) {
        IndicesAliasesRequest request = null;
        for (IndexMetaData index : clusterState.metaData()) {
            String name = index.getIndex().getName();
            if (name.startsWith(".marvel-es-1-")) {
                // we add a suffix so that it will not collide with today's monitoring index following an upgrade
                String alias = ".monitoring-es-2-" + name.substring(".marvel-es-1-".length()) + "-alias";
                if (index.getAliases().containsKey(alias)) continue;
                if (request == null) {
                    request = new IndicesAliasesRequest();
                }
                request.addAliasAction(AliasActions.add().index(name).alias(alias));
            }
        }
        return request;
    }

    enum State {
        INITIALIZED,
        RUNNING,
        TERMINATED
    }

    /**
     * Acknowledge success / failure for any given creation attempt (e.g., template or pipeline).
     */
    private class ResponseActionListener<Response> implements ActionListener<Response> {

        private final String type;
        private final String name;
        private final AtomicInteger countDown;
        private final AtomicBoolean setup;

        private ResponseActionListener(String type, String name, AtomicInteger countDown) {
            this(type, name, countDown, null);
        }

        private ResponseActionListener(String type, String name, AtomicInteger countDown, @Nullable AtomicBoolean setup) {
            this.type = Objects.requireNonNull(type);
            this.name = Objects.requireNonNull(name);
            this.countDown = Objects.requireNonNull(countDown);
            this.setup = setup;
        }

        @Override
        public void onResponse(Response response) {
            responseReceived(countDown, true, setup);
            if (response instanceof AcknowledgedResponse) {
                if (((AcknowledgedResponse)response).isAcknowledged()) {
                    logger.trace("successfully set monitoring {} [{}]", type, name);
                } else {
                    logger.error("failed to set monitoring {} [{}]", type, name);
                }
            } else {
                logger.trace("successfully handled monitoring {} [{}]", type, name);
            }
        }

        @Override
        public void onFailure(Exception e) {
            responseReceived(countDown, false, setup);
            logger.error((Supplier<?>) () -> new ParameterizedMessage("failed to set monitoring {} [{}]", type, name), e);
        }
    }

    private class GetAndPutWatchResponseActionListener implements ActionListener<GetWatchResponse> {

        private final WatcherClient watcher;
        private final String watchId;
        private final String uniqueWatchId;
        private final AtomicInteger countDown;

        private GetAndPutWatchResponseActionListener(final WatcherClient watcher,
                                                     final String watchId, final String uniqueWatchId,
                                                     final AtomicInteger countDown) {
            this.watcher = Objects.requireNonNull(watcher);
            this.watchId = Objects.requireNonNull(watchId);
            this.uniqueWatchId = Objects.requireNonNull(uniqueWatchId);
            this.countDown = Objects.requireNonNull(countDown);
        }

        @Override
        public void onResponse(GetWatchResponse response) {
            if (response.isFound() &&
                hasValidVersion(response.getSource().getValue("metadata.xpack.version_created"), ClusterAlertsUtil.LAST_UPDATED_VERSION)) {
                logger.trace("found monitoring watch [{}]", uniqueWatchId);

                responseReceived(countDown, true, watcherSetup);
            } else {
                putWatch(watcher, watchId, uniqueWatchId, countDown);
            }
        }

        @Override
        public void onFailure(Exception e) {
            responseReceived(countDown, false, watcherSetup);

            if ((e instanceof IndexNotFoundException) == false) {
                logger.error((Supplier<?>) () ->
                             new ParameterizedMessage("failed to get monitoring watch [{}]", uniqueWatchId), e);
            }
        }

    }

}
