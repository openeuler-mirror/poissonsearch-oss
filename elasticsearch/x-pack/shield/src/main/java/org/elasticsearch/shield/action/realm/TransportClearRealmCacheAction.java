/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.action.realm;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.FailedNodeException;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.nodes.TransportNodesAction;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.shield.authc.Realm;
import org.elasticsearch.shield.authc.Realms;
import org.elasticsearch.shield.authc.support.CachingRealm;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

/**
 *
 */
public class TransportClearRealmCacheAction extends TransportNodesAction<ClearRealmCacheRequest, ClearRealmCacheResponse,
        ClearRealmCacheRequest.Node, ClearRealmCacheResponse.Node> {

    private final Realms realms;

    @Inject
    public TransportClearRealmCacheAction(Settings settings, ClusterName clusterName, ThreadPool threadPool,
                                          ClusterService clusterService, TransportService transportService,
                                          ActionFilters actionFilters, Realms realms,
                                          IndexNameExpressionResolver indexNameExpressionResolver) {
        super(settings, ClearRealmCacheAction.NAME, clusterName, threadPool, clusterService, transportService, actionFilters,
              indexNameExpressionResolver, ClearRealmCacheRequest::new, ClearRealmCacheRequest.Node::new, ThreadPool.Names.MANAGEMENT,
              ClearRealmCacheResponse.Node.class);
        this.realms = realms;
    }

    @Override
    protected ClearRealmCacheResponse newResponse(ClearRealmCacheRequest request,
                                                  ClearRealmCacheResponse.Node[] responses, FailedNodeException[] failures) {
        return new ClearRealmCacheResponse(clusterName, responses, failures);
    }

    @Override
    protected ClearRealmCacheRequest.Node newNodeRequest(String nodeId, ClearRealmCacheRequest request) {
        return new ClearRealmCacheRequest.Node(request, nodeId);
    }

    @Override
    protected ClearRealmCacheResponse.Node newNodeResponse() {
        return new ClearRealmCacheResponse.Node();
    }

    @Override
    protected ClearRealmCacheResponse.Node nodeOperation(ClearRealmCacheRequest.Node nodeRequest) throws ElasticsearchException {
        if (nodeRequest.realms == null || nodeRequest.realms.length == 0) {
            for (Realm realm : realms) {
                clearCache(realm, nodeRequest.usernames);
            }
            return new ClearRealmCacheResponse.Node(clusterService.localNode());
        }

        for (String realmName : nodeRequest.realms) {
            Realm realm = realms.realm(realmName);
            if (realm == null) {
                throw new IllegalArgumentException("could not find active realm [" + realmName + "]");
            }
            clearCache(realm, nodeRequest.usernames);
        }
        return new ClearRealmCacheResponse.Node(clusterService.localNode());
    }

    private void clearCache(Realm realm, String[] usernames) {
        if (!(realm instanceof CachingRealm)) {
            return;
        }
        CachingRealm cachingRealm = (CachingRealm) realm;

        if (usernames != null && usernames.length != 0) {
            for (String username : usernames) {
                cachingRealm.expire(username);
            }
        } else {
            cachingRealm.expireAll();
        }
    }

    @Override
    protected boolean accumulateExceptions() {
        return false;
    }

}
