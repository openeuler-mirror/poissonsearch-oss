/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authz;

import org.elasticsearch.action.CompositeIndicesRequest;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.common.base.Predicate;
import org.elasticsearch.common.collect.ImmutableList;
import org.elasticsearch.shield.User;
import org.elasticsearch.shield.authz.indicesresolver.DefaultIndicesResolver;
import org.elasticsearch.shield.authz.indicesresolver.IndicesResolver;
import org.elasticsearch.shield.support.AutomatonPredicate;
import org.elasticsearch.shield.support.Automatons;
import org.elasticsearch.transport.TransportRequest;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 * Represents a permission in the system. There are 3 types of permissions:
 *
 * <ul>
 *     <li>
 *         Cluster -    a permission that is based on privileges for cluster wide actions
 *     </li>
 *     <li>
 *         Indices -    a permission that is based on privileges for index related actions executed
 *                      on specific indices
 *     </li>
 *     <li>
 *         Global -     a composite permission that combines a both cluster & indices permissions
 *     </li>
 * </ul>
 */
public interface Permission {

    boolean check(User user, String action, TransportRequest request, MetaData metaData);

    static class Global implements Permission {

        private final Cluster cluster;
        private final Indices indices;

        Global() {
            this(null, null);
        }

        Global(Cluster cluster, Indices indices) {
            this.cluster = cluster;
            this.indices = indices;
        }

        public Cluster cluster() {
            return cluster;
        }

        public Indices indices() {
            return indices;
        }

        public boolean check(User user, String action, TransportRequest request, MetaData metaData) {
            if (cluster != null && cluster.check(user, action, request, metaData)) {
                return true;
            }
            if (indices != null && indices.check(user, action, request, metaData)) {
                return true;
            }
            return false;
        }

        public static Builder builder(AuthorizationService authzService) {
            return new Builder(authzService);
        }

        public static class Builder {

            private final AuthorizationService authzService;

            private Cluster cluster = Cluster.NONE;
            private ImmutableList.Builder<Indices.Group> groups;

            private Builder(AuthorizationService authzService) {
                this.authzService = authzService;
            }

            public Builder set(Privilege.Cluster privilege) {
                cluster = new Cluster(privilege);
                return this;
            }

            public Builder add(Privilege.Index privilege, String... indices) {
                if (groups == null) {
                    groups = ImmutableList.builder();
                }
                groups.add(new Indices.Group(privilege, indices));
                return this;
            }

            public Global build() {
                Indices indices = groups != null ? new Indices(authzService, groups.build()) : Indices.NONE;
                return new Global(cluster, indices);
            }
        }
    }

    static class Cluster implements Permission {

        public static final Cluster NONE = new Cluster(Privilege.Cluster.NONE) {
            @Override
            public boolean check(User user, String action, TransportRequest request, MetaData metaData) {
                return false;
            }
        };

        private final Privilege.Cluster privilege;
        private final Predicate<String> predicate;

        private Cluster(Privilege.Cluster privilege) {
            this.privilege = privilege;
            this.predicate = privilege.predicate();
        }

        public Privilege.Cluster privilege() {
            return privilege;
        }

        @Override
        public boolean check(User user, String action, TransportRequest request, MetaData metaData) {
            return predicate.apply(action);
        }
    }

    static class Indices implements Permission {

        public static final Indices NONE = new Indices() {
            @Override
            public boolean check(User user, String action, TransportRequest request, MetaData metaData) {
                return false;
            }
        };

        private final IndicesResolver[] indicesResolvers;
        private final Group[] groups;

        private Indices() {
            this.indicesResolvers = new IndicesResolver[0];
            this.groups = new Group[0];
        }


        public Indices(AuthorizationService authzService, Collection<Group> groups) {
            this.groups = groups.toArray(new Group[groups.size()]);
            this.indicesResolvers = new IndicesResolver[] {
                    // add special resolvers here
                    new DefaultIndicesResolver(authzService)
            };
        }

        public Group[] groups() {
            return groups;
        }

        /**
         * @return  A predicate that will match all the indices that this permission
         *          has the given privilege for.
         */
        public Predicate<String> allowedIndicesMatcher(Privilege.Index privilege) {
            ImmutableList.Builder<String> indices = ImmutableList.builder();
            for (Group group : groups) {
                if (group.privilege.implies(privilege)) {
                    indices.add(group.indices);
                }
            }
            return new AutomatonPredicate(Automatons.patterns(indices.build()));
        }

        /**
         * @return  A predicate that will match all the indices that this permission
         *          has the privilege for executing the given action on.
         */
        public Predicate<String> allowedIndicesMatcher(String action) {
            ImmutableList.Builder<String> indices = ImmutableList.builder();
            for (Group group : groups) {
                if (group.actionMatcher.apply(action)) {
                    indices.add(group.indices);
                }
            }
            return new AutomatonPredicate(Automatons.patterns(indices.build()));
        }

        @Override @SuppressWarnings("unchecked")
        public boolean check(User user, String action, TransportRequest request, MetaData metaData) {

            // some APIs are indices requests that are not actually associated with indices. For example,
            // search scroll request, is categorized under the indices context, but doesn't hold indices names
            // (in this case, the security check on the indices was done on the search request that initialized
            // the scroll... and we rely on the signed scroll id to provide security over this request).
            //
            // so we only check indices if indeed the request is an actual IndicesRequest, if it's not, we only
            // perform the check on the action name.
            Set<String> indices = null;
            if (request instanceof IndicesRequest || request instanceof CompositeIndicesRequest) {
                indices = Collections.emptySet();
                for (IndicesResolver resolver : indicesResolvers) {
                    if (resolver.requestType().isInstance(request)) {
                        indices = resolver.resolve(user, action, request, metaData);
                        break;
                    }
                }
            }

            for (Group group : groups) {
                if (group.check(action, indices)) {
                    return true;
                }
            }
            return false;
        }

        public static class Group {

            private final Privilege.Index privilege;
            private final Predicate<String> actionMatcher;
            private final String[] indices;
            private final Predicate<String> indexNameMatcher;

            public Group(Privilege.Index privilege, String... indices) {
                assert indices.length != 0;
                this.privilege = privilege;
                this.actionMatcher = privilege.predicate();
                this.indices = indices;
                this.indexNameMatcher = new AutomatonPredicate(Automatons.patterns(indices));
            }

            public Privilege.Index privilege() {
                return privilege;
            }

            public String[] indices() {
                return indices;
            }

            public boolean check(String action, Set<String> indices) {

                if (!actionMatcher.apply(action)) {
                    return false;
                }

                if (indices != null) {
                    for (String index : indices) {
                        if (!indexNameMatcher.apply(index)) {
                            return false;
                        }
                    }
                }

                return true;
            }


        }
    }

}
