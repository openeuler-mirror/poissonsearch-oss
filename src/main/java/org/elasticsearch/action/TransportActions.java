/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.action;

/**
 *
 */
public class TransportActions {

    public static final String BULK = "bulk";
    public static final String INDEX = "index";
    public static final String UPDATE = "update";
    public static final String COUNT = "count";
    public static final String DELETE = "delete";
    public static final String DELETE_BY_QUERY = "deleteByQuery";
    public static final String GET = "get";
    public static final String MULTI_GET = "mget";
    public static final String SEARCH = "search";
    public static final String SEARCH_SCROLL = "searchScroll";
    public static final String MORE_LIKE_THIS = "mlt";
    public static final String PERCOLATE = "percolate";

    public static class Admin {

        public static class Indices {
            public static final String CREATE = "indices/create";
            public static final String DELETE = "indices/delete";
            public static final String OPEN = "indices/open";
            public static final String CLOSE = "indices/close";
            public static final String FLUSH = "indices/flush";
            public static final String REFRESH = "indices/refresh";
            public static final String OPTIMIZE = "indices/optimize";
            public static final String STATUS = "indices/status";
            public static final String STATS = "indices/stats";
            public static final String SEGMENTS = "indices/segments";
            public static final String EXISTS = "indices/exists";
            public static final String ALIASES = "indices/aliases";
            public static final String UPDATE_SETTINGS = "indices/updateSettings";
            public static final String ANALYZE = "indices/analyze";

            public static class Gateway {
                public static final String SNAPSHOT = "indices/gateway/snapshot";
            }

            public static class Mapping {
                public static final String PUT = "indices/mapping/put";
                public static final String DELETE = "indices/mapping/delete";
            }

            public static class Template {
                public static final String PUT = "indices/template/put";
                public static final String DELETE = "indices/template/delete";
            }

            public static class Validate {
                public static final String QUERY = "indices/validate/query";
            }

            public static class Cache {
                public static final String CLEAR = "indices/cache/clear";
            }
        }

        public static class Cluster {

            public static final String STATE = "/cluster/state";
            public static final String HEALTH = "/cluster/health";
            public static final String UPDATE_SETTINGS = "/cluster/updateSettings";
            public static final String REROUTE = "/cluster/reroute";

            public static class Node {
                public static final String INFO = "/cluster/nodes/info";
                public static final String STATS = "/cluster/nodes/stats";
                public static final String SHUTDOWN = "/cluster/nodes/shutdown";
                public static final String RESTART = "/cluster/nodes/restart";
            }

            public static class Ping {
                public static final String SINGLE = "/cluster/ping/single";
                public static final String REPLICATION = "/cluster/ping/replication";
                public static final String BROADCAST = "/cluster/ping/broadcast";
            }
        }
    }
}
