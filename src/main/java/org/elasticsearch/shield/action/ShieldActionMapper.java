/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.action;

import org.elasticsearch.action.admin.indices.analyze.AnalyzeAction;
import org.elasticsearch.action.admin.indices.analyze.AnalyzeRequest;
import org.elasticsearch.action.search.ClearScrollAction;
import org.elasticsearch.action.search.ClearScrollRequest;
import org.elasticsearch.transport.TransportRequest;

/**
 * This class analyzes an incoming request and its action name, and returns the shield action name for it.
 * In many cases the action name is the same as the original one used in es core, but in some exceptional cases it might need
 * to be converted. For instance a clear_scroll that targets all opened scrolls gets converted to a different action that requires
 * cluster privileges instead of the default indices privileges, still valid for clear scrolls that target specific scroll ids.
 */
public class ShieldActionMapper {

    static final String CLUSTER_PERMISSION_SCROLL_CLEAR_ALL_NAME = "cluster:admin/indices/scroll/clear_all";
    static final String CLUSTER_PERMISSION_ANALYZE = "cluster:admin/analyze";

    /**
     * Returns the shield specific action name given the incoming action name and request
     */
    public String action(String action, TransportRequest request) {
        switch (action) {
            case ClearScrollAction.NAME:
                assert request instanceof ClearScrollRequest;
                boolean isClearAllScrollRequest =  ((ClearScrollRequest) request).scrollIds().contains("_all");
                if (isClearAllScrollRequest) {
                    return CLUSTER_PERMISSION_SCROLL_CLEAR_ALL_NAME;
                }
                break;
            case AnalyzeAction.NAME:
                assert request instanceof AnalyzeRequest;
                String[] indices = ((AnalyzeRequest) request).indices();
                if (indices == null || indices.length == 0) {
                    return CLUSTER_PERMISSION_ANALYZE;
                }
                break;
        }
        return action;
    }
}
