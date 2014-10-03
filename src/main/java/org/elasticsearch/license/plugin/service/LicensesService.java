/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license.plugin.service;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.*;
import org.elasticsearch.cluster.ack.ClusterStateUpdateRequest;
import org.elasticsearch.cluster.ack.ClusterStateUpdateResponse;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Injector;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.license.core.ESLicenses;
import org.elasticsearch.license.core.LicenseBuilders;
import org.elasticsearch.license.plugin.action.put.PutLicenseRequest;
import org.elasticsearch.license.plugin.cluster.LicensesMetaData;

/**
 * Service responsible for maintaining and providing access to licenses on nodes.
 *
 * TODO: Work in progress:
 *  - implement logic in clusterChanged
 *  - interface with LicenseManager
 */
public class LicensesService extends AbstractComponent implements ClusterStateListener {

 //   private final RepositoryTypesRegistry typesRegistry;

    private final Injector injector;

    private final ClusterService clusterService;

    //private volatile ESLicenses licenses = null;//ImmutableMap.of();

    @Inject
    public LicensesService(Settings settings, ClusterService clusterService, Injector injector) {
        super(settings);
        this.injector = injector;
        this.clusterService = clusterService;
        // Doesn't make sense to maintain repositories on non-master and non-data nodes
        // Nothing happens there anyway
        if (DiscoveryNode.dataNode(settings) || DiscoveryNode.masterNode(settings)) {
            clusterService.add(this);
        }
    }

    /**
     * Registers new repository in the cluster
     * <p/>
     * This method can be only called on the master node. It tries to create a new repository on the master
     * and if it was successful it adds new repository to cluster metadata.
     *
     * @param request  register repository request
     * @param listener register repository listener
     */
    public void registerLicenses(final String source, final PutLicenseRequest request, final ActionListener<ClusterStateUpdateResponse> listener) {
        final LicensesMetaData newLicenseMetaData = new LicensesMetaData(request.license());
        //TODO: add a source field to request
        clusterService.submitStateUpdateTask(source, new AckedClusterStateUpdateTask<ClusterStateUpdateResponse>(request, listener) {
            @Override
            protected ClusterStateUpdateResponse newResponse(boolean acknowledged) {
                return new ClusterStateUpdateResponse(acknowledged);
            }

            @Override
            public ClusterState execute(ClusterState currentState) throws Exception {
                // TODO check if newLicenseMetaData actually needs a cluster update
                MetaData metaData = currentState.metaData();
                MetaData.Builder mdBuilder = MetaData.builder(currentState.metaData());
                LicensesMetaData currentLicenses = metaData.custom(LicensesMetaData.TYPE);

                if (currentLicenses == null) {
                    // no licenses were registered
                    currentLicenses = newLicenseMetaData;
                } else {
                    // merge previous license with new one
                    currentLicenses = new LicensesMetaData(LicenseBuilders.merge(currentLicenses, newLicenseMetaData));
                }
                mdBuilder.putCustom(LicensesMetaData.TYPE, currentLicenses);
                return ClusterState.builder(currentState).metaData(mdBuilder).build();
            }
        });

        /*
        final RepositoryMetaData newRepositoryMetaData = new RepositoryMetaData(request.name, request.type, request.settings);

        clusterService.submitStateUpdateTask(request.cause, new AckedClusterStateUpdateTask<ClusterStateUpdateResponse>(request, listener) {
            @Override
            protected ClusterStateUpdateResponse newResponse(boolean acknowledged) {
                return new ClusterStateUpdateResponse(acknowledged);
            }

            @Override
            public ClusterState execute(ClusterState currentState) {
                ensureRepositoryNotInUse(currentState, request.name);
                // Trying to create the new repository on master to make sure it works
                if (!registerLicenses(newRepositoryMetaData)) {
                    // The new repository has the same settings as the old one - ignore
                    return currentState;
                }
                MetaData metaData = currentState.metaData();
                MetaData.Builder mdBuilder = MetaData.builder(currentState.metaData());
                RepositoriesMetaData repositories = metaData.custom(RepositoriesMetaData.TYPE);
                if (repositories == null) {
                    logger.info("put repository [{}]", request.name);
                    repositories = new RepositoriesMetaData(new RepositoryMetaData(request.name, request.type, request.settings));
                } else {
                    boolean found = false;
                    List<RepositoryMetaData> repositoriesMetaData = new ArrayList<>(repositories.repositories().size() + 1);

                    for (RepositoryMetaData repositoryMetaData : repositories.repositories()) {
                        if (repositoryMetaData.name().equals(newRepositoryMetaData.name())) {
                            found = true;
                            repositoriesMetaData.add(newRepositoryMetaData);
                        } else {
                            repositoriesMetaData.add(repositoryMetaData);
                        }
                    }
                    if (!found) {
                        logger.info("put repository [{}]", request.name);
                        repositoriesMetaData.add(new RepositoryMetaData(request.name, request.type, request.settings));
                    } else {
                        logger.info("update repository [{}]", request.name);
                    }
                    repositories = new RepositoriesMetaData(repositoriesMetaData.toArray(new RepositoryMetaData[repositoriesMetaData.size()]));
                }
                mdBuilder.putCustom(RepositoriesMetaData.TYPE, repositories);
                return ClusterState.builder(currentState).metaData(mdBuilder).build();
            }

            @Override
            public void onFailure(String source, Throwable t) {
                logger.warn("failed to create repository [{}]", t, request.name);
                super.onFailure(source, t);
            }

            @Override
            public boolean mustAck(DiscoveryNode discoveryNode) {
                return discoveryNode.masterNode();
            }
        });*/
    }

    /**
     * Unregisters repository in the cluster
     * <p/>
     * This method can be only called on the master node. It removes repository information from cluster metadata.
     *
     * @param request  unregister repository request
     * @param listener unregister repository listener
     */
    public void unregisterRepository(final UnregisterRepositoryRequest request, final ActionListener<ClusterStateUpdateResponse> listener) {
        /*
        clusterService.submitStateUpdateTask(request.cause, new AckedClusterStateUpdateTask<ClusterStateUpdateResponse>(request, listener) {
            @Override
            protected ClusterStateUpdateResponse newResponse(boolean acknowledged) {
                return new ClusterStateUpdateResponse(acknowledged);
            }

            @Override
            public ClusterState execute(ClusterState currentState) {
                ensureRepositoryNotInUse(currentState, request.name);
                MetaData metaData = currentState.metaData();
                MetaData.Builder mdBuilder = MetaData.builder(currentState.metaData());
                RepositoriesMetaData repositories = metaData.custom(RepositoriesMetaData.TYPE);
                if (repositories != null && repositories.repositories().size() > 0) {
                    List<RepositoryMetaData> repositoriesMetaData = new ArrayList<>(repositories.repositories().size());
                    boolean changed = false;
                    for (RepositoryMetaData repositoryMetaData : repositories.repositories()) {
                        if (Regex.simpleMatch(request.name, repositoryMetaData.name())) {
                            logger.info("delete repository [{}]", repositoryMetaData.name());
                            changed = true;
                        } else {
                            repositoriesMetaData.add(repositoryMetaData);
                        }
                    }
                    if (changed) {
                        repositories = new RepositoriesMetaData(repositoriesMetaData.toArray(new RepositoryMetaData[repositoriesMetaData.size()]));
                        mdBuilder.putCustom(RepositoriesMetaData.TYPE, repositories);
                        return ClusterState.builder(currentState).metaData(mdBuilder).build();
                    }
                }
                throw new RepositoryMissingException(request.name);
            }

            @Override
            public boolean mustAck(DiscoveryNode discoveryNode) {
                // Since operation occurs only on masters, it's enough that only master-eligible nodes acked
                return discoveryNode.masterNode();
            }
        });*/
    }

    /**
     * Checks if new repositories appeared in or disappeared from cluster metadata and updates current list of
     * repositories accordingly.
     *
     * @param event cluster changed event
     */
    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        /*
        try {
            RepositoriesMetaData oldMetaData = event.previousState().getMetaData().custom(RepositoriesMetaData.TYPE);
            RepositoriesMetaData newMetaData = event.state().getMetaData().custom(RepositoriesMetaData.TYPE);

            // Check if repositories got changed
            if ((oldMetaData == null && newMetaData == null) || (oldMetaData != null && oldMetaData.equals(newMetaData))) {
                return;
            }

            logger.trace("processing new index repositories for state version [{}]", event.state().version());

            Map<String, RepositoryHolder> survivors = newHashMap();
            // First, remove repositories that are no longer there
            for (Map.Entry<String, RepositoryHolder> entry : repositories.entrySet()) {
                if (newMetaData == null || newMetaData.repository(entry.getKey()) == null) {
                    logger.debug("unregistering repository [{}]", entry.getKey());
                    closeRepository(entry.getKey(), entry.getValue());
                } else {
                    survivors.put(entry.getKey(), entry.getValue());
                }
            }

            ImmutableMap.Builder<String, RepositoryHolder> builder = ImmutableMap.builder();
            if (newMetaData != null) {
                // Now go through all repositories and update existing or create missing
                for (RepositoryMetaData repositoryMetaData : newMetaData.repositories()) {
                    RepositoryHolder holder = survivors.get(repositoryMetaData.name());
                    if (holder != null) {
                        // Found previous version of this repository
                        if (!holder.type.equals(repositoryMetaData.type()) || !holder.settings.equals(repositoryMetaData.settings())) {
                            // Previous version is different from the version in settings
                            logger.debug("updating repository [{}]", repositoryMetaData.name());
                            closeRepository(repositoryMetaData.name(), holder);
                            holder = createRepositoryHolder(repositoryMetaData);
                        }
                    } else {
                        holder = createRepositoryHolder(repositoryMetaData);
                    }
                    if (holder != null) {
                        logger.debug("registering repository [{}]", repositoryMetaData.name());
                        builder.put(repositoryMetaData.name(), holder);
                    }
                }
            }
            repositories = builder.build();
        } catch (Throwable ex) {
            logger.warn("failure updating cluster state ", ex);
        }*/
    }


    /**
     * Returns registered repository
     * <p/>
     * This method is called only on the master node
     *
     * @param repository repository name
     * @return registered repository
     * @throws RepositoryMissingException if repository with such name isn't registered
     */
    /*
    public Repository repository(String repository) {
        RepositoryHolder holder = repositories.get(repository);
        if (holder != null) {
            return holder.repository;
        }
        throw new RepositoryMissingException(repository);
    }*/

    /**
     * Returns registered index shard repository
     * <p/>
     * This method is called only on data nodes
     *
     * @param repository repository name
     * @return registered repository
     * @throws RepositoryMissingException if repository with such name isn't registered
     */
    /*
    public IndexShardRepository indexShardRepository(String repository) {
        RepositoryHolder holder = repositories.get(repository);
        if (holder != null) {
            return holder.indexShardRepository;
        }
        throw new RepositoryMissingException(repository);
    }*/

    /**
     * Creates a new repository and adds it to the list of registered repositories.
     * <p/>
     * If a repository with the same name but different types or settings already exists, it will be closed and
     * replaced with the new repository. If a repository with the same name exists but it has the same type and settings
     * the new repository is ignored.
     *
     * @param repositoryMetaData new repository metadata
     * @return {@code true} if new repository was added or {@code false} if it was ignored
     */
    /*
    private boolean registerLicenses(RepositoryMetaData repositoryMetaData) {
        RepositoryHolder previous = repositories.get(repositoryMetaData.name());
        if (previous != null) {
            if (!previous.type.equals(repositoryMetaData.type()) && previous.settings.equals(repositoryMetaData.settings())) {
                // Previous version is the same as this one - ignore it
                return false;
            }
        }
        RepositoryHolder holder = createRepositoryHolder(repositoryMetaData);
        if (previous != null) {
            // Closing previous version
            closeRepository(repositoryMetaData.name(), previous);
        }
        Map<String, RepositoryHolder> newRepositories = newHashMap(repositories);
        newRepositories.put(repositoryMetaData.name(), holder);
        repositories = ImmutableMap.copyOf(newRepositories);
        return true;
    }
*/
    /**
     * Closes the repository
     *
     * @param name   repository name
     * @param holder repository holder
     */
    /*
    private void closeRepository(String name, RepositoryHolder holder) {
        logger.debug("closing repository [{}][{}]", holder.type, name);
        if (holder.injector != null) {
            Injectors.close(holder.injector);
        }
        if (holder.repository != null) {
            holder.repository.close();
        }
    }*/

    /**
     * Creates repository holder
     */
    /*
    private RepositoryHolder createRepositoryHolder(RepositoryMetaData repositoryMetaData) {
        logger.debug("creating repository [{}][{}]", repositoryMetaData.type(), repositoryMetaData.name());
        Injector repositoryInjector = null;
        try {
            ModulesBuilder modules = new ModulesBuilder();
            RepositoryName name = new RepositoryName(repositoryMetaData.type(), repositoryMetaData.name());
            modules.add(new RepositoryNameModule(name));
            modules.add(new RepositoryModule(name, repositoryMetaData.settings(), this.settings, typesRegistry));

            repositoryInjector = modules.createChildInjector(injector);
            Repository repository = repositoryInjector.getInstance(Repository.class);
            IndexShardRepository indexShardRepository = repositoryInjector.getInstance(IndexShardRepository.class);
            repository.start();
            return new RepositoryHolder(repositoryMetaData.type(), repositoryMetaData.settings(), repositoryInjector, repository, indexShardRepository);
        } catch (Throwable t) {
            if (repositoryInjector != null) {
                Injectors.close(repositoryInjector);
            }
            logger.warn("failed to create repository [{}][{}]", t, repositoryMetaData.type(), repositoryMetaData.name());
            throw new RepositoryException(repositoryMetaData.name(), "failed to create repository", t);
        }
    }

    private void ensureRepositoryNotInUse(ClusterState clusterState, String repository) {
        if (SnapshotsService.isRepositoryInUse(clusterState, repository) || RestoreService.isRepositoryInUse(clusterState, repository)) {
            throw new ElasticsearchIllegalStateException("trying to modify or unregister repository that is currently used ");
        }
    }*/

    /**
     * Internal data structure for holding repository with its configuration information and injector
     */
   /* private static class RepositoryHolder {

        private final String type;
        private final Settings settings;
        private final Injector injector;
        private final Repository repository;
        private final IndexShardRepository indexShardRepository;

        public RepositoryHolder(String type, Settings settings, Injector injector, Repository repository, IndexShardRepository indexShardRepository) {
            this.type = type;
            this.settings = settings;
            this.repository = repository;
            this.indexShardRepository = indexShardRepository;
            this.injector = injector;
        }
    }
*/
    /**
     * Register repository request
     */
    public static class RegisterRepositoryRequest extends ClusterStateUpdateRequest<RegisterRepositoryRequest> {

        final String cause;

        final String name;

        final String type;

        Settings settings = null;

        /**
         * Constructs new register repository request
         *
         * @param cause repository registration cause
         * @param name  repository name
         * @param type  repository type
         */
        public RegisterRepositoryRequest(String cause, String name, String type) {
            this.cause = cause;
            this.name = name;
            this.type = type;
        }

        /**
         * Sets repository settings
         *
         * @param settings repository settings
         * @return this request
         */
        public RegisterRepositoryRequest settings(Settings settings) {
            this.settings = settings;
            return this;
        }
    }

    /**
     * Unregister repository request
     */
    public static class UnregisterRepositoryRequest extends ClusterStateUpdateRequest<UnregisterRepositoryRequest> {

        final String cause;

        final String name;

        /**
         * Creates a new unregister repository request
         *
         * @param cause repository unregistration cause
         * @param name  repository name
         */
        public UnregisterRepositoryRequest(String cause, String name) {
            this.cause = cause;
            this.name = name;
        }

    }
}
