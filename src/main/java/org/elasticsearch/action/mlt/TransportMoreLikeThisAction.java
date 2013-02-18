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

package org.elasticsearch.action.mlt;

import org.apache.lucene.document.Field;
import org.apache.lucene.index.Term;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.ElasticSearchIllegalStateException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.get.TransportGetAction;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.TransportSearchAction;
import org.elasticsearch.action.support.TransportAction;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.MutableShardRouting;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.ShardIterator;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.get.GetField;
import org.elasticsearch.index.mapper.*;
import org.elasticsearch.index.mapper.internal.SourceFieldMapper;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MoreLikeThisFieldQueryBuilder;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.*;

import java.util.Collections;
import java.util.Iterator;
import java.util.Set;

import static com.google.common.collect.Sets.newHashSet;
import static org.elasticsearch.client.Requests.getRequest;
import static org.elasticsearch.client.Requests.searchRequest;
import static org.elasticsearch.index.query.QueryBuilders.*;
import static org.elasticsearch.search.builder.SearchSourceBuilder.searchSource;

/**
 * The more like this action.
 */
public class TransportMoreLikeThisAction extends TransportAction<MoreLikeThisRequest, SearchResponse> {

    private final TransportSearchAction searchAction;

    private final TransportGetAction getAction;

    private final IndicesService indicesService;

    private final ClusterService clusterService;

    private final TransportService transportService;

    @Inject
    public TransportMoreLikeThisAction(Settings settings, ThreadPool threadPool, TransportSearchAction searchAction, TransportGetAction getAction,
                                       ClusterService clusterService, IndicesService indicesService, TransportService transportService) {
        super(settings, threadPool);
        this.searchAction = searchAction;
        this.getAction = getAction;
        this.indicesService = indicesService;
        this.clusterService = clusterService;
        this.transportService = transportService;

        transportService.registerHandler(MoreLikeThisAction.NAME, new TransportHandler());
    }

    @Override
    protected void doExecute(final MoreLikeThisRequest request, final ActionListener<SearchResponse> listener) {
        // update to actual index name
        ClusterState clusterState = clusterService.state();
        // update to the concrete index
        final String concreteIndex = clusterState.metaData().concreteIndex(request.getIndex());

        RoutingNode routingNode = clusterState.getRoutingNodes().nodesToShards().get(clusterService.localNode().getId());
        if (routingNode == null) {
            redirect(request, listener, clusterState);
            return;
        }
        boolean hasIndexLocally = false;
        for (MutableShardRouting shardRouting : routingNode.shards()) {
            if (concreteIndex.equals(shardRouting.index())) {
                hasIndexLocally = true;
                break;
            }
        }
        if (!hasIndexLocally) {
            redirect(request, listener, clusterState);
            return;
        }
        Set<String> getFields = newHashSet();
        if (request.getFields() != null) {
            Collections.addAll(getFields, request.getFields());
        }
        // add the source, in case we need to parse it to get fields
        getFields.add(SourceFieldMapper.NAME);

        GetRequest getRequest = getRequest(concreteIndex)
                .setFields(getFields.toArray(new String[getFields.size()]))
                .setType(request.getType())
                .setId(request.getId())
                .setRouting(request.getRouting())
                .setListenerThreaded(true)
                .setOperationThreaded(true);

        request.beforeLocalFork();
        getAction.execute(getRequest, new ActionListener<GetResponse>() {
            @Override
            public void onResponse(GetResponse getResponse) {
                if (!getResponse.isExists()) {
                    listener.onFailure(new ElasticSearchException("document missing"));
                    return;
                }
                final BoolQueryBuilder boolBuilder = boolQuery();
                try {
                    final DocumentMapper docMapper = indicesService.indexServiceSafe(concreteIndex).mapperService().documentMapper(request.getType());
                    if (docMapper == null) {
                        throw new ElasticSearchException("No DocumentMapper found for type [" + request.getType() + "]");
                    }
                    final Set<String> fields = newHashSet();
                    if (request.getFields() != null) {
                        for (String field : request.getFields()) {
                            FieldMappers fieldMappers = docMapper.mappers().smartName(field);
                            if (fieldMappers != null) {
                                fields.add(fieldMappers.mapper().names().indexName());
                            } else {
                                fields.add(field);
                            }
                        }
                    }

                    if (!fields.isEmpty()) {
                        // if fields are not empty, see if we got them in the response
                        for (Iterator<String> it = fields.iterator(); it.hasNext(); ) {
                            String field = it.next();
                            GetField getField = getResponse.getField(field);
                            if (getField != null) {
                                for (Object value : getField.getValues()) {
                                    addMoreLikeThis(request, boolBuilder, getField.getName(), value.toString());
                                }
                                it.remove();
                            }
                        }
                        if (!fields.isEmpty()) {
                            // if we don't get all the fields in the get response, see if we can parse the source
                            parseSource(getResponse, boolBuilder, docMapper, fields, request);
                        }
                    } else {
                        // we did not ask for any fields, try and get it from the source
                        parseSource(getResponse, boolBuilder, docMapper, fields, request);
                    }

                    if (!boolBuilder.hasClauses()) {
                        // no field added, fail
                        listener.onFailure(new ElasticSearchException("No fields found to fetch the 'likeText' from"));
                        return;
                    }

                    // exclude myself
                    Term uidTerm = docMapper.uidMapper().term(request.getType(), request.getId());
                    boolBuilder.mustNot(termQuery(uidTerm.field(), uidTerm.text()));
                } catch (Exception e) {
                    listener.onFailure(e);
                    return;
                }

                String[] searchIndices = request.getSearchIndices();
                if (searchIndices == null) {
                    searchIndices = new String[]{request.getIndex()};
                }
                String[] searchTypes = request.getSearchTypes();
                if (searchTypes == null) {
                    searchTypes = new String[]{request.getType()};
                }
                int size = request.getSearchSize() != 0 ? request.getSearchSize() : 10;
                int from = request.getSearchFrom() != 0 ? request.getSearchFrom() : 0;
                SearchRequest searchRequest = searchRequest(searchIndices)
                        .setTypes(searchTypes)
                        .setSearchType(request.getSearchType())
                        .setScroll(request.getSearchScroll())
                        .setExtraSource(searchSource()
                                .query(boolBuilder)
                                .from(from)
                                .size(size)
                        )
                        .setListenerThreaded(request.isListenerThreaded());

                if (request.getSearchSource() != null) {
                    searchRequest.setSource(request.getSearchSource(), request.isSearchSourceUnsafe());
                }
                searchAction.execute(searchRequest, new ActionListener<SearchResponse>() {
                    @Override
                    public void onResponse(SearchResponse response) {
                        listener.onResponse(response);
                    }

                    @Override
                    public void onFailure(Throwable e) {
                        listener.onFailure(e);
                    }
                });

            }

            @Override
            public void onFailure(Throwable e) {
                listener.onFailure(e);
            }
        });
    }

    // Redirects the request to a data node, that has the index meta data locally available.
    private void redirect(MoreLikeThisRequest request, final ActionListener<SearchResponse> listener, ClusterState clusterState) {
        ShardIterator shardIterator = clusterService.operationRouting().getShards(clusterState, request.getIndex(), request.getType(), request.getId(), null, null);
        ShardRouting shardRouting = shardIterator.firstOrNull();
        if (shardRouting == null) {
            throw new ElasticSearchException("No shards for index " + request.getIndex());
        }
        String nodeId = shardRouting.currentNodeId();
        DiscoveryNode discoveryNode = clusterState.nodes().get(nodeId);
        transportService.sendRequest(discoveryNode, MoreLikeThisAction.NAME, request, new TransportResponseHandler<SearchResponse>() {

            @Override
            public SearchResponse newInstance() {
                return new SearchResponse();
            }

            @Override
            public void handleResponse(SearchResponse response) {
                listener.onResponse(response);
            }

            @Override
            public void handleException(TransportException exp) {
                listener.onFailure(exp);
            }

            @Override
            public String executor() {
                return ThreadPool.Names.SAME;
            }
        });
    }

    private void parseSource(GetResponse getResponse, final BoolQueryBuilder boolBuilder, DocumentMapper docMapper, final Set<String> fields, final MoreLikeThisRequest request) {
        if (getResponse.isSourceEmpty()) {
            return;
        }
        docMapper.parse(SourceToParse.source(getResponse.getSourceAsBytesRef()).type(request.getType()).id(request.getId()), new DocumentMapper.ParseListenerAdapter() {
            @Override
            public boolean beforeFieldAdded(FieldMapper fieldMapper, Field field, Object parseContext) {
                if (fieldMapper instanceof InternalMapper) {
                    return true;
                }
                String value = fieldMapper.value(convertField(field)).toString();
                if (value == null) {
                    return false;
                }

                if (fields.isEmpty() || fields.contains(field.name())) {
                    addMoreLikeThis(request, boolBuilder, fieldMapper, field);
                }

                return false;
            }
        });
    }

    private Object convertField(Field field) {
        if (field.stringValue() != null) {
            return field.stringValue();
        } else if (field.binaryValue() != null) {
            return BytesRef.deepCopyOf(field.binaryValue()).bytes;
        } else if (field.numericValue() != null) {
            return field.numericValue();
        } else {
            throw new ElasticSearchIllegalStateException("Field should have either a string, numeric or binary value");
        }
    }

    private void addMoreLikeThis(MoreLikeThisRequest request, BoolQueryBuilder boolBuilder, FieldMapper fieldMapper, Field field) {
        addMoreLikeThis(request, boolBuilder, field.name(), fieldMapper.value(convertField(field)).toString());
    }

    private void addMoreLikeThis(MoreLikeThisRequest request, BoolQueryBuilder boolBuilder, String fieldName, String likeText) {
        MoreLikeThisFieldQueryBuilder mlt = moreLikeThisFieldQuery(fieldName)
                .likeText(likeText)
                .percentTermsToMatch(request.getPercentTermsToMatch())
                .boostTerms(request.getBoostTerms())
                .minDocFreq(request.getMinDocFreq())
                .maxDocFreq(request.getMaxDocFreq())
                .minWordLen(request.getMinWordLen())
                .maxWordLen(request.getMaxWordLen())
                .minTermFreq(request.getMinTermFreq())
                .maxQueryTerms(request.getMaxQueryTerms())
                .stopWords(request.getStopWords());
        boolBuilder.should(mlt);
    }

    private class TransportHandler extends BaseTransportRequestHandler<MoreLikeThisRequest> {

        @Override
        public MoreLikeThisRequest newInstance() {
            return new MoreLikeThisRequest();
        }

        @Override
        public void messageReceived(MoreLikeThisRequest request, final TransportChannel channel) throws Exception {
            // no need to have a threaded listener since we just send back a response
            request.setListenerThreaded(false);
            execute(request, new ActionListener<SearchResponse>() {
                @Override
                public void onResponse(SearchResponse result) {
                    try {
                        channel.sendResponse(result);
                    } catch (Exception e) {
                        onFailure(e);
                    }
                }

                @Override
                public void onFailure(Throwable e) {
                    try {
                        channel.sendResponse(e);
                    } catch (Exception e1) {
                        logger.warn("Failed to send response for get", e1);
                    }
                }
            });
        }

        @Override
        public String executor() {
            return ThreadPool.Names.SAME;
        }
    }
}
