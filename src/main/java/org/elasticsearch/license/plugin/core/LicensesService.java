/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license.plugin.core;

import net.nicholaswilliams.java.licensing.exception.InvalidLicenseException;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.*;
import org.elasticsearch.cluster.ack.ClusterStateUpdateResponse;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.collect.ImmutableSet;
import org.elasticsearch.common.collect.Sets;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.component.Lifecycle;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.EsRejectedExecutionException;
import org.elasticsearch.gateway.GatewayService;
import org.elasticsearch.license.core.ESLicense;
import org.elasticsearch.license.manager.ESLicenseManager;
import org.elasticsearch.license.plugin.action.delete.DeleteLicenseRequest;
import org.elasticsearch.license.plugin.action.put.PutLicenseRequest;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.elasticsearch.license.core.ESLicenses.reduceAndMap;

/**
 * Service responsible for managing {@link LicensesMetaData}
 * Interfaces through which this is exposed are:
 * - LicensesManagerService - responsible for managing signed and one-time-trial licenses
 * - LicensesClientService - responsible for feature registration and notification to consumer plugin(s)
 * <p/>
 * <p/>
 * Registration Scheme:
 * <p/>
 * A consumer plugin (feature) is registered with {@link LicensesClientService#register(String, TrialLicenseOptions, LicensesClientService.Listener)}
 * This method can be called at any time during the life-cycle of the consumer plugin.
 * If the feature can not be registered immediately, it is queued up and registered on the first clusterChanged event with
 * no {@link GatewayService#STATE_NOT_RECOVERED_BLOCK} block
 * Upon successful registration, the feature(s) are notified appropriately using the notification scheme
 * <p/>
 * <p/>
 * Notification Scheme:
 * <p/>
 * All registered feature(s) are notified using {@link #notifyFeatures(LicensesMetaData)} (depends on the current
 * {@link #registeredListeners}). It is idempotent with respect to all the feature listeners.
 * <p/>
 * The notification scheduling is done by {@link #notifyFeaturesAndScheduleNotification(LicensesMetaData)} which does the following:
 * - calls {@link #notifyFeatures(LicensesMetaData)} to notify all registered feature(s)
 * - if there is any license(s) with a future expiry date in the current cluster state:
 * - schedules a delayed {@link LicensingClientNotificationJob} on the MIN of all the expiry dates of all the registered feature(s)
 * <p/>
 * The {@link LicensingClientNotificationJob} calls {@link #notifyFeaturesAndScheduleNotification(LicensesMetaData)} to schedule
 * another delayed {@link LicensingClientNotificationJob} as stated above. It is a no-op in case of a global block on
 * {@link GatewayService#STATE_NOT_RECOVERED_BLOCK}
 * <p/>
 * Upon successful registration of a new feature:
 * - {@link #notifyFeaturesAndScheduleNotification(LicensesMetaData)} is called
 * <p/>
 * Upon clusterChanged():
 * - {@link #notifyFeaturesAndScheduleNotification(LicensesMetaData)} is called if:
 * - new trial/signed license(s) are found in the cluster state meta data
 * - if new feature(s) are added to the registeredListener
 * - if the previous cluster state had a global block on {@link GatewayService#STATE_NOT_RECOVERED_BLOCK}
 * - no-op in case of global block on {@link GatewayService#STATE_NOT_RECOVERED_BLOCK}
 */
@Singleton
public class LicensesService extends AbstractLifecycleComponent<LicensesService> implements ClusterStateListener, LicensesManagerService, LicensesClientService {

    public static final String REGISTER_TRIAL_LICENSE_ACTION_NAME = "internal:plugin/licenses/cluster/register_trial_license";

    private final ESLicenseManager licenseManager;

    private final ClusterService clusterService;

    private final ThreadPool threadPool;

    private final TransportService transportService;

    /**
     * Currently active consumers to notify to
     */
    private final List<ListenerHolder> registeredListeners = new CopyOnWriteArrayList<>();

    /**
     * Currently pending consumers that has to be registered
     */
    private final Queue<ListenerHolder> pendingListeners = new ConcurrentLinkedQueue<>();

    /**
     * Currently active scheduledNotifications
     * All finished notifications will be cleared by {@link #scheduleNextNotification(long)}
     */
    private final Queue<ScheduledFuture> scheduledNotifications = new ConcurrentLinkedQueue<>();

    /**
     * The last licensesMetaData that has been notified by {@link #notifyFeatures(LicensesMetaData)}
     */
    private final AtomicReference<LicensesMetaData> lastObservedLicensesState;

    @Inject
    public LicensesService(Settings settings, ClusterService clusterService, ThreadPool threadPool, TransportService transportService, ESLicenseManager licenseManager) {
        super(settings);
        this.clusterService = clusterService;
        this.licenseManager = licenseManager;
        this.threadPool = threadPool;
        this.transportService = transportService;
        this.lastObservedLicensesState = new AtomicReference<>(null);
        if (DiscoveryNode.masterNode(settings)) {
            transportService.registerHandler(REGISTER_TRIAL_LICENSE_ACTION_NAME, new RegisterTrialLicenseRequestHandler());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerLicenses(final PutLicenseRequestHolder requestHolder, final ActionListener<LicensesUpdateResponse> listener) {
        final PutLicenseRequest request = requestHolder.request;
        final Set<ESLicense> newLicenses = Sets.newHashSet(request.licenses());
        LicensesStatus status = checkLicenses(newLicenses);
        if (status == LicensesStatus.VALID) {
            clusterService.submitStateUpdateTask(requestHolder.source, new AckedClusterStateUpdateTask<LicensesUpdateResponse>(request, listener) {
                @Override
                protected LicensesUpdateResponse newResponse(boolean acknowledged) {
                    return new LicensesUpdateResponse(acknowledged, LicensesStatus.VALID);
                }

                @Override
                public ClusterState execute(ClusterState currentState) throws Exception {
                    MetaData metaData = currentState.metaData();
                    MetaData.Builder mdBuilder = MetaData.builder(currentState.metaData());
                    LicensesMetaData currentLicenses = metaData.custom(LicensesMetaData.TYPE);
                    final LicensesWrapper licensesWrapper = LicensesWrapper.wrap(currentLicenses);
                    Set<String> newSignatures = licenseManager.toSignatures(newLicenses);
                    Set<String> newLicenseSignatures = Sets.union(licensesWrapper.signatures, newSignatures);
                    if (newLicenseSignatures.size() != licensesWrapper.signatures.size()) {
                        LicensesMetaData newLicensesMetaData = new LicensesMetaData(newLicenseSignatures, licensesWrapper.encodedTrialLicenses);
                        mdBuilder.putCustom(LicensesMetaData.TYPE, newLicensesMetaData);
                        return ClusterState.builder(currentState).metaData(mdBuilder).build();
                    }
                    return currentState;
                }
            });
        } else {
            listener.onResponse(new LicensesUpdateResponse(true, status));
        }
    }

    public static class LicensesUpdateResponse extends ClusterStateUpdateResponse {
        private final LicensesStatus status;

        public LicensesUpdateResponse(boolean acknowledged, LicensesStatus status) {
            super(acknowledged);
            this.status = status;
        }

        public LicensesStatus status() {
            return status;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeLicenses(final DeleteLicenseRequestHolder requestHolder, final ActionListener<ClusterStateUpdateResponse> listener) {
        final DeleteLicenseRequest request = requestHolder.request;
        clusterService.submitStateUpdateTask(requestHolder.source, new AckedClusterStateUpdateTask<ClusterStateUpdateResponse>(request, listener) {
            @Override
            protected ClusterStateUpdateResponse newResponse(boolean acknowledged) {
                return new ClusterStateUpdateResponse(acknowledged);
            }

            @Override
            public ClusterState execute(ClusterState currentState) throws Exception {
                MetaData metaData = currentState.metaData();
                LicensesMetaData currentLicenses = metaData.custom(LicensesMetaData.TYPE);
                final LicensesWrapper licensesWrapper = LicensesWrapper.wrap(currentLicenses);

                Set<ESLicense> currentSignedLicenses = licensesWrapper.signedLicenses(licenseManager);
                Set<ESLicense> licensesToDelete = new HashSet<>();
                for (ESLicense license : currentSignedLicenses) {
                    if (request.features().contains(license.feature())) {
                        licensesToDelete.add(license);
                    }
                }
                if (!licensesToDelete.isEmpty()) {
                    Set<ESLicense> reducedLicenses = Sets.difference(currentSignedLicenses, licensesToDelete);
                    Set<String> newSignatures = licenseManager.toSignatures(reducedLicenses);
                    LicensesMetaData newLicensesMetaData = new LicensesMetaData(newSignatures, licensesWrapper.encodedTrialLicenses);
                    MetaData.Builder mdBuilder = MetaData.builder(currentState.metaData());
                    mdBuilder.putCustom(LicensesMetaData.TYPE, newLicensesMetaData);
                    return ClusterState.builder(currentState).metaData(mdBuilder).build();
                } else {
                    return currentState;
                }
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<String> enabledFeatures() {
        Set<String> enabledFeatures = Sets.newHashSet();
        for (ListenerHolder holder : registeredListeners) {
            if (holder.enabled.get()) {
                enabledFeatures.add(holder.feature);
            }
        }
        return enabledFeatures;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ESLicense> getLicenses() {
        LicensesMetaData currentMetaData = clusterService.state().metaData().custom(LicensesMetaData.TYPE);
        if (currentMetaData != null) {
            // don't use ESLicenses.reduceAndMap, as it will merge out expired licenses
            Set<ESLicense> licenses = Sets.union(licenseManager.fromSignatures(currentMetaData.getSignatures()),
                    TrialLicenseUtils.fromEncodedTrialLicenses(currentMetaData.getEncodedTrialLicenses()));

            // bucket license for feature with the latest expiry date
            Map<String, ESLicense> licenseMap = new HashMap<>();
            for (ESLicense license : licenses) {
                if (!licenseMap.containsKey(license.feature())) {
                    licenseMap.put(license.feature(), license);
                } else {
                    ESLicense prevLicense = licenseMap.get(license.feature());
                    if (license.expiryDate() > prevLicense.expiryDate()) {
                        licenseMap.put(license.feature(), license);
                    }
                }
            }

            // sort the licenses by issue date
            List<ESLicense> reducedLicenses = new ArrayList<>(licenseMap.values());
            Collections.sort(reducedLicenses, new Comparator<ESLicense>() {
                @Override
                public int compare(ESLicense license1, ESLicense license2) {
                    return (int) (license2.issueDate() - license1.issueDate());
                }
            });
            return reducedLicenses;
        }
        return Collections.emptyList();
    }

    private LicensesStatus checkLicenses(Set<ESLicense> licenses) {
        final ImmutableMap<String, ESLicense> map = reduceAndMap(licenses);
        return checkLicenses(map);
    }

    private LicensesStatus checkLicenses(Map<String, ESLicense> licenseMap) {
        LicensesStatus status = LicensesStatus.VALID;
        try {
            licenseManager.verifyLicenses(licenseMap);
        } catch (InvalidLicenseException e) {
            status = LicensesStatus.INVALID;
        }
        return status;
    }

    /**
     * Master-only operation to generate a one-time trial license for a feature.
     * The trial license is only generated and stored if the current cluster state metaData
     * has no signed/one-time-trial license for the feature in question
     */
    private void registerTrialLicense(final RegisterTrialLicenseRequest request) {
        clusterService.submitStateUpdateTask("register trial license []", new ProcessedClusterStateUpdateTask() {
            @Override
            public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                LicensesMetaData licensesMetaData = newState.metaData().custom(LicensesMetaData.TYPE);
                logLicenseMetaDataStats("after trial license registration", licensesMetaData);
            }

            @Override
            public ClusterState execute(ClusterState currentState) throws Exception {
                MetaData metaData = currentState.metaData();
                MetaData.Builder mdBuilder = MetaData.builder(currentState.metaData());
                LicensesMetaData currentLicensesMetaData = metaData.custom(LicensesMetaData.TYPE);
                final LicensesWrapper licensesWrapper = LicensesWrapper.wrap(currentLicensesMetaData);
                // do not generate a trial license for a feature that already has a signed/trial license
                if (checkTrialLicenseGenerationCondition(request.feature, licensesWrapper)) {
                    Set<String> newTrialLicenses = Sets.union(licensesWrapper.encodedTrialLicenses,
                            Sets.newHashSet(generateEncodedTrialLicense(request.feature, request.duration, request.maxNodes)));
                    final LicensesMetaData newLicensesMetaData = new LicensesMetaData(
                            licensesWrapper.signatures, newTrialLicenses);
                    mdBuilder.putCustom(LicensesMetaData.TYPE, newLicensesMetaData);
                    return ClusterState.builder(currentState).metaData(mdBuilder).build();
                }
                return currentState;
            }

            @Override
            public void onFailure(String source, @Nullable Throwable t) {
                logger.debug("LicensesService: " + source, t);
            }

            private boolean checkTrialLicenseGenerationCondition(String feature, LicensesWrapper licensesWrapper) {
                for (ESLicense license : Sets.union(licensesWrapper.signedLicenses(licenseManager),
                        licensesWrapper.trialLicenses())) {
                    if (license.feature().equals(feature)) {
                        return false;
                    }
                }
                return true;
            }

            private String generateEncodedTrialLicense(String feature, TimeValue duration, int maxNodes) {
                return TrialLicenseUtils.toEncodedTrialLicense(
                        TrialLicenseUtils.builder()
                                .issuedTo(clusterService.state().getClusterName().value())
                                .issueDate(System.currentTimeMillis())
                                .duration(duration)
                                .feature(feature)
                                .maxNodes(maxNodes)
                                .build()
                );
            }
        });
    }

    @Override
    protected void doStart() throws ElasticsearchException {
        clusterService.add(this);
    }

    @Override
    protected void doStop() throws ElasticsearchException {
        clusterService.remove(this);

        // cancel all notifications
        for (ScheduledFuture scheduledNotification : scheduledNotifications) {
            scheduledNotification.cancel(true);
        }

        // notify features to be disabled
        for (ListenerHolder holder : registeredListeners) {
            holder.disableFeatureIfNeeded();
        }
        // clear all handlers
        registeredListeners.clear();

        // empty out notification queue
        scheduledNotifications.clear();

        lastObservedLicensesState.set(null);
    }

    @Override
    protected void doClose() throws ElasticsearchException {
        transportService.removeHandler(REGISTER_TRIAL_LICENSE_ACTION_NAME);
    }

    /**
     * When there is no global block on {@link GatewayService#STATE_NOT_RECOVERED_BLOCK}:
     * - tries to register any {@link #pendingListeners} by calling {@link #registeredListeners}
     * - if any {@link #pendingListeners} are registered successfully or if previous cluster state had a block on
     * {@link GatewayService#STATE_NOT_RECOVERED_BLOCK}, calls
     * {@link #notifyFeaturesAndScheduleNotification(LicensesMetaData)}
     * - else calls {@link #notifyFeaturesAndScheduleNotificationIfNeeded(LicensesMetaData)}
     */
    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        final ClusterState previousClusterState = event.previousState();
        final ClusterState currentClusterState = event.state();
        if (!currentClusterState.blocks().hasGlobalBlock(GatewayService.STATE_NOT_RECOVERED_BLOCK)) {
            final LicensesMetaData oldLicensesMetaData = previousClusterState.getMetaData().custom(LicensesMetaData.TYPE);
            final LicensesMetaData currentLicensesMetaData = currentClusterState.getMetaData().custom(LicensesMetaData.TYPE);
            logLicenseMetaDataStats("old", oldLicensesMetaData);
            logLicenseMetaDataStats("new", currentLicensesMetaData);

            // register any pending listeners
            if (!pendingListeners.isEmpty()) {
                ListenerHolder pendingRegistrationListener;
                while ((pendingRegistrationListener = pendingListeners.poll()) != null) {
                    boolean masterAvailable = registerListener(pendingRegistrationListener);
                    if (logger.isDebugEnabled()) {
                        logger.debug("trying to register pending listener for " + pendingRegistrationListener.feature + " masterAvailable: " + masterAvailable);
                    }
                    if (!masterAvailable) {
                        // if the master is not available do not, break out of trying pendingListeners
                        pendingListeners.add(pendingRegistrationListener);
                        break;
                    }
                }
            }

            // notify all interested plugins
            if (previousClusterState.blocks().hasGlobalBlock(GatewayService.STATE_NOT_RECOVERED_BLOCK)) {
                notifyFeaturesAndScheduleNotification(currentLicensesMetaData);
            } else {
                notifyFeaturesAndScheduleNotificationIfNeeded(currentLicensesMetaData);
            }
        } else if (logger.isDebugEnabled()) {
            logger.debug("clusterChanged: no action [has STATE_NOT_RECOVERED_BLOCK]");
        }
    }

    /**
     * Calls {@link #notifyFeaturesAndScheduleNotification(LicensesMetaData)} with <code>currentLicensesMetaData</code>
     * if it was not already notified on.
     * <p/>
     * Upon completion sets <code>currentLicensesMetaData</code> to {@link #lastObservedLicensesState}
     * to ensure the same license(s) are not notified on from
     * {@link #clusterChanged(ClusterChangedEvent)}
     */
    private void notifyFeaturesAndScheduleNotificationIfNeeded(final LicensesMetaData currentLicensesMetaData) {
        final LicensesMetaData lastNotifiedLicensesMetaData = lastObservedLicensesState.get();
        if (lastNotifiedLicensesMetaData != null && lastNotifiedLicensesMetaData.equals(currentLicensesMetaData)) {
            if (logger.isDebugEnabled()) {
                logger.debug("currentLicensesMetaData has been already notified on");
            }
            return;
        }
        notifyFeaturesAndScheduleNotification(currentLicensesMetaData);
        lastObservedLicensesState.set(currentLicensesMetaData);
        logLicenseMetaDataStats("Setting last observed metaData", currentLicensesMetaData);
    }

    /**
     * Calls {@link #notifyFeatures(LicensesMetaData)} with <code>currentLicensesMetaData</code>
     * and schedules the earliest expiry (if any) notification for registered feature(s)
     */
    private void notifyFeaturesAndScheduleNotification(final LicensesMetaData currentLicensesMetaData) {
        long nextScheduleFrequency = notifyFeatures(currentLicensesMetaData);
        if (nextScheduleFrequency != -1l) {
            scheduleNextNotification(nextScheduleFrequency);
        }
    }

    /**
     * Checks license expiry for all the registered feature(s)
     *
     * @return -1 if there are no expiring license(s) for any registered feature(s), else
     * returns the minimum of the expiry times of all the registered feature(s) to
     * schedule an expiry notification
     */
    private long notifyFeatures(final LicensesMetaData currentLicensesMetaData) {
        long nextScheduleFrequency = -1l;
        for (ListenerHolder listenerHolder : registeredListeners) {
            long expiryDate = expiryDateForFeature(listenerHolder.feature, currentLicensesMetaData);
            long expiryDuration = expiryDate - System.currentTimeMillis();

            if (expiryDuration > 0l) {
                listenerHolder.enableFeatureIfNeeded();
                if (nextScheduleFrequency == -1l) {
                    nextScheduleFrequency = expiryDuration;
                } else {
                    nextScheduleFrequency = Math.min(expiryDuration, nextScheduleFrequency);
                }
            } else {
                listenerHolder.disableFeatureIfNeeded();
            }

            if (logger.isDebugEnabled()) {
                String status;
                if (expiryDate != -1l) {
                    status = " status: license expires in : " + TimeValue.timeValueMillis(expiryDate - System.currentTimeMillis());
                } else {
                    status = " status: no trial/signed license found";
                }
                if (expiryDuration > 0l) {
                    status += " action: enableFeatureIfNeeded";
                } else {
                    status += " action: disableFeatureIfNeeded";
                }
                logger.debug(listenerHolder.toString() + "" + status);
            }
        }

        if (logger.isDebugEnabled()) {
            if (nextScheduleFrequency == -1l) {
                logger.debug("no need to schedule next notification");
            } else {
                logger.debug("next notification time: " + TimeValue.timeValueMillis(nextScheduleFrequency).toString());
            }
        }

        return nextScheduleFrequency;

    }

    private void logLicenseMetaDataStats(String prefix, LicensesMetaData licensesMetaData) {
        if (logger.isDebugEnabled()) {
            if (licensesMetaData != null) {
                StringBuilder signedFeatures = new StringBuilder();
                for (ESLicense license : licenseManager.fromSignatures(licensesMetaData.getSignatures())) {
                    signedFeatures.append(license.feature());
                    signedFeatures.append(", ");
                }
                StringBuilder trialFeatures = new StringBuilder();
                for (ESLicense license : TrialLicenseUtils.fromEncodedTrialLicenses(licensesMetaData.getEncodedTrialLicenses())) {
                    trialFeatures.append(license.feature());
                    trialFeatures.append(", ");
                }
                logger.debug(prefix + " LicensesMetaData: signedLicenses: [" + signedFeatures.toString() + "] trialLicenses: [" + trialFeatures.toString() + "]");
            } else {
                logger.debug(prefix + " LicensesMetaData: signedLicenses: [] trialLicenses: []");
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void register(String feature, TrialLicenseOptions trialLicenseOptions, Listener listener) {
        final ListenerHolder listenerHolder = new ListenerHolder(feature, trialLicenseOptions, listener);
        // don't trust the clusterState for blocks just yet!
        final Lifecycle.State clusterServiceState = clusterService.lifecycleState();
        if (clusterServiceState != Lifecycle.State.STARTED) {
            pendingListeners.add(listenerHolder);
        } else {
            if (!registerListener(listenerHolder)) {
                pendingListeners.add(listenerHolder);
            }
        }
    }

    /**
     * Notifies new feature listener if it already has a signed license
     * if new feature has a non-null trial license option, a master node request is made to generate the trial license
     * then notifies features if needed
     *
     * @param listenerHolder of the feature to register
     * @return true if registration has been completed, false otherwise (if masterNode is not available & trail license spec is provided)
     * or if there is a global block on {@link GatewayService#STATE_NOT_RECOVERED_BLOCK}
     */
    private boolean registerListener(final ListenerHolder listenerHolder) {
        logger.debug("Registering listener for " + listenerHolder.feature);
        final ClusterState currentState = clusterService.state();
        if (currentState.blocks().hasGlobalBlock(GatewayService.STATE_NOT_RECOVERED_BLOCK)) {
            return false;
        }

        final LicensesMetaData currentMetaData = currentState.metaData().custom(LicensesMetaData.TYPE);
        if (expiryDateForFeature(listenerHolder.feature, currentMetaData) == -1l) {
            // does not have any license so generate a trial license
            TrialLicenseOptions options = listenerHolder.trialLicenseOptions;
            if (options != null) {
                // Trial license option is provided
                RegisterTrialLicenseRequest request = new RegisterTrialLicenseRequest(listenerHolder.feature,
                        options.duration, options.maxNodes);
                if (currentState.nodes().localNodeMaster()) {
                    registerTrialLicense(request);
                } else {
                    DiscoveryNode masterNode = currentState.nodes().masterNode();
                    if (masterNode != null) {
                        transportService.sendRequest(masterNode,
                                REGISTER_TRIAL_LICENSE_ACTION_NAME, request, EmptyTransportResponseHandler.INSTANCE_SAME);
                    } else {
                        // could not sent register trial license request to master
                        logger.debug("Store as pendingRegistration [master not available yet]");
                        return false;
                    }
                }
            } else if (logger.isDebugEnabled()) {
                // notify feature as clusterChangedEvent may not happen
                // as no trial or signed license has been found for feature
                logger.debug("Calling notifyFeaturesAndScheduleNotification [no trial license spec provided]");
            }
        } else if (logger.isDebugEnabled()) {
            // signed license already found for the new registered
            // feature, notify feature on registration
            logger.debug("Calling notifyFeaturesAndScheduleNotification [signed/trial license available]");
        }
        registeredListeners.add(listenerHolder);
        notifyFeaturesAndScheduleNotification(currentMetaData);
        return true;
    }

    private long expiryDateForFeature(String feature, final LicensesMetaData currentLicensesMetaData) {
        final Map<String, ESLicense> effectiveLicenses = getEffectiveLicenses(currentLicensesMetaData);
        ESLicense featureLicense;
        if ((featureLicense = effectiveLicenses.get(feature)) != null) {
            return featureLicense.expiryDate();
        }
        return -1l;
    }

    private Map<String, ESLicense> getEffectiveLicenses(final LicensesMetaData metaData) {
        Map<String, ESLicense> map = new HashMap<>();
        if (metaData != null) {
            Set<ESLicense> esLicenses = new HashSet<>();
            esLicenses.addAll(licenseManager.fromSignatures(metaData.getSignatures()));
            esLicenses.addAll(TrialLicenseUtils.fromEncodedTrialLicenses(metaData.getEncodedTrialLicenses()));
            return reduceAndMap(esLicenses);
        }
        return ImmutableMap.copyOf(map);

    }

    /**
     * Clears out any completed notification future from
     * {@link #scheduledNotifications}
     */
    private void clearFinishedNotifications() {
        while (!scheduledNotifications.isEmpty()) {
            ScheduledFuture notification = scheduledNotifications.peek();
            if (notification != null && notification.isDone()) {
                // remove the notifications that are done
                scheduledNotifications.poll();
            } else {
                // stop emptying out the queue as soon as the first undone future hits
                break;
            }
        }
    }

    private String executorName() {
        return ThreadPool.Names.GENERIC;
    }

    /**
     * Schedules an expiry notification with a delay of <code>nextScheduleDelay</code>
     */
    private void scheduleNextNotification(long nextScheduleDelay) {
        clearFinishedNotifications();

        try {
            final TimeValue delay = TimeValue.timeValueMillis(nextScheduleDelay);
            scheduledNotifications.add(threadPool.schedule(delay, executorName(), new LicensingClientNotificationJob()));
            if (logger.isDebugEnabled()) {
                logger.debug("Scheduling next notification after: " + delay);
            }
        } catch (EsRejectedExecutionException ex) {
            logger.debug("Couldn't re-schedule licensing client notification job", ex);
        }
    }

    /**
     * Job for notifying on expired license(s) to registered feature(s)
     * In case of a global block on {@link GatewayService#STATE_NOT_RECOVERED_BLOCK},
     * the notification is not run, instead the feature(s) would be notified on the next
     * {@link #clusterChanged(ClusterChangedEvent)} with no global block
     */
    private class LicensingClientNotificationJob implements Runnable {

        public LicensingClientNotificationJob() {
        }

        @Override
        public void run() {
            logger.debug("Performing LicensingClientNotificationJob");

            // next clusterChanged event will deal with the missed notifications
            final ClusterState currentClusterState = clusterService.state();
            if (!currentClusterState.blocks().hasGlobalBlock(GatewayService.STATE_NOT_RECOVERED_BLOCK)) {
                final LicensesMetaData currentLicensesMetaData = currentClusterState.metaData().custom(LicensesMetaData.TYPE);
                notifyFeaturesAndScheduleNotification(currentLicensesMetaData);
            } else if (logger.isDebugEnabled()) {
                logger.debug("skip notification [STATE_NOT_RECOVERED_BLOCK]");
            }
        }
    }


    public static class PutLicenseRequestHolder {
        private final PutLicenseRequest request;
        private final String source;

        public PutLicenseRequestHolder(PutLicenseRequest request, String source) {
            this.request = request;
            this.source = source;
        }
    }

    public static class DeleteLicenseRequestHolder {
        private final DeleteLicenseRequest request;
        private final String source;

        public DeleteLicenseRequestHolder(DeleteLicenseRequest request, String source) {
            this.request = request;
            this.source = source;
        }
    }

    public static class TrialLicenseOptions {
        final TimeValue duration;
        final int maxNodes;

        public TrialLicenseOptions(TimeValue duration, int maxNodes) {
            this.duration = duration;
            this.maxNodes = maxNodes;
        }
    }

    /**
     * Stores configuration and listener for a feature
     */
    private class ListenerHolder {
        final String feature;
        final TrialLicenseOptions trialLicenseOptions;
        final Listener listener;

        final AtomicBoolean enabled = new AtomicBoolean(false); // by default, a consumer plugin should be disabled

        private ListenerHolder(String feature, TrialLicenseOptions trialLicenseOptions, Listener listener) {
            this.feature = feature;
            this.trialLicenseOptions = trialLicenseOptions;
            this.listener = listener;
        }

        private void enableFeatureIfNeeded() {
            if (enabled.compareAndSet(false, true)) {
                listener.onEnabled();
                if (logger.isDebugEnabled()) {
                    logger.debug("feature: " + feature + " calling onEnabled");
                }
            }
        }

        private void disableFeatureIfNeeded() {
            if (enabled.compareAndSet(true, false)) {
                listener.onDisabled();
                if (logger.isDebugEnabled()) {
                    logger.debug("feature: " + feature + " calling onDisabled");
                }
            }
        }

        public String toString() {
            return "(feature: " + feature + ", enabled: " + enabled.get() + ")";
        }
    }

    /**
     * Thin wrapper to work with {@link LicensesMetaData}
     * Never mutates the wrapped metaData
     */
    private static class LicensesWrapper {

        public static LicensesWrapper wrap(LicensesMetaData licensesMetaData) {
            return new LicensesWrapper(licensesMetaData);
        }

        private ImmutableSet<String> signatures = ImmutableSet.of();
        private ImmutableSet<String> encodedTrialLicenses = ImmutableSet.of();

        private LicensesWrapper(LicensesMetaData licensesMetaData) {
            if (licensesMetaData != null) {
                this.signatures = ImmutableSet.copyOf(licensesMetaData.getSignatures());
                this.encodedTrialLicenses = ImmutableSet.copyOf(licensesMetaData.getEncodedTrialLicenses());
            }
        }

        public Set<ESLicense> signedLicenses(ESLicenseManager licenseManager) {
            return licenseManager.fromSignatures(signatures);
        }

        public Set<ESLicense> trialLicenses() {
            return TrialLicenseUtils.fromEncodedTrialLicenses(encodedTrialLicenses);
        }
    }

    /**
     * Request for trial license generation to master
     */
    private static class RegisterTrialLicenseRequest extends TransportRequest {
        private int maxNodes;
        private String feature;
        private TimeValue duration;

        private RegisterTrialLicenseRequest() {
        }

        private RegisterTrialLicenseRequest(String feature, TimeValue duration, int maxNodes) {
            this.maxNodes = maxNodes;
            this.feature = feature;
            this.duration = duration;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            maxNodes = in.readVInt();
            feature = in.readString();
            duration = new TimeValue(in.readVLong(), TimeUnit.MILLISECONDS);
        }


        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeVInt(maxNodes);
            out.writeString(feature);
            out.writeVLong(duration.getMillis());
        }
    }

    /**
     * Request handler for trial license generation to master
     */
    private class RegisterTrialLicenseRequestHandler extends BaseTransportRequestHandler<RegisterTrialLicenseRequest> {
        @Override
        public RegisterTrialLicenseRequest newInstance() {
            return new RegisterTrialLicenseRequest();
        }

        @Override
        public void messageReceived(RegisterTrialLicenseRequest request, TransportChannel channel) throws Exception {
            registerTrialLicense(request);
            channel.sendResponse(TransportResponse.Empty.INSTANCE);
        }

        @Override
        public String executor() {
            return ThreadPool.Names.SAME;
        }
    }
}
