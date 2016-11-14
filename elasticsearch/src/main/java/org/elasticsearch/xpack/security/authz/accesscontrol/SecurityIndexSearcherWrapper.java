/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authz.accesscontrol;

import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BulkScorer;
import org.apache.lucene.search.CollectionTerminatedException;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.ConjunctionDISI;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.BitSetIterator;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.SparseFixedBitSet;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.Action;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestBuilder;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.FilterClient;
import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.logging.LoggerMessageFormat;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.cache.bitset.BitsetFilterCache;
import org.elasticsearch.index.engine.EngineException;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.FieldNamesFieldMapper;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.ParentFieldMapper;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.BoostingQueryBuilder;
import org.elasticsearch.index.query.ConstantScoreQueryBuilder;
import org.elasticsearch.index.query.GeoShapeQueryBuilder;
import org.elasticsearch.index.query.HasChildQueryBuilder;
import org.elasticsearch.index.query.HasParentQueryBuilder;
import org.elasticsearch.index.query.ParsedQuery;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryRewriteContext;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.index.query.TermsQueryBuilder;
import org.elasticsearch.index.query.functionscore.FunctionScoreQueryBuilder;
import org.elasticsearch.index.shard.IndexSearcherWrapper;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.shard.ShardUtils;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.script.ExecutableScript;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.xpack.security.authc.Authentication;
import org.elasticsearch.xpack.security.authz.AuthorizationService;
import org.elasticsearch.xpack.security.authz.accesscontrol.DocumentSubsetReader.DocumentSubsetDirectoryReader;
import org.elasticsearch.xpack.security.support.Exceptions;
import org.elasticsearch.xpack.security.user.User;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.apache.lucene.search.BooleanClause.Occur.SHOULD;

/**
 * An {@link IndexSearcherWrapper} implementation that is used for field and document level security.
 * <p>
 * Based on the {@link ThreadContext} this class will enable field and/or document level security.
 * <p>
 * Field level security is enabled by wrapping the original {@link DirectoryReader} in a {@link FieldSubsetReader}
 * in the {@link #wrap(DirectoryReader)} method.
 * <p>
 * Document level security is enabled by wrapping the original {@link DirectoryReader} in a {@link DocumentSubsetReader}
 * instance.
 */
public class SecurityIndexSearcherWrapper extends IndexSearcherWrapper {

    private final MapperService mapperService;
    private final Set<String> allowedMetaFields;
    private final Function<ShardId, QueryShardContext> queryShardContextProvider;
    private final BitsetFilterCache bitsetFilterCache;
    private final XPackLicenseState licenseState;
    private final ThreadContext threadContext;
    private final Logger logger;
    private final ScriptService scriptService;

    public SecurityIndexSearcherWrapper(IndexSettings indexSettings, Function<ShardId, QueryShardContext> queryShardContextProvider,
                                        MapperService mapperService, BitsetFilterCache bitsetFilterCache,
                                        ThreadContext threadContext, XPackLicenseState licenseState,
                                        ScriptService scriptService) {
        this.scriptService = scriptService;
        this.logger = Loggers.getLogger(getClass(), indexSettings.getSettings());
        this.mapperService = mapperService;
        this.queryShardContextProvider = queryShardContextProvider;
        this.bitsetFilterCache = bitsetFilterCache;
        this.threadContext = threadContext;
        this.licenseState = licenseState;

        Set<String> allowedMetaFields = new HashSet<>();
        allowedMetaFields.addAll(Arrays.asList(MapperService.getAllMetaFields()));
        allowedMetaFields.add(FieldNamesFieldMapper.NAME); // TODO: add _field_names to MapperService#META_FIELDS?
        allowedMetaFields.add("_source"); // TODO: add _source to MapperService#META_FIELDS?
        allowedMetaFields.add("_version"); // TODO: add _version to MapperService#META_FIELDS?
        allowedMetaFields.remove("_all"); // The _all field contains actual data and we can't include that by default.

        this.allowedMetaFields = Collections.unmodifiableSet(allowedMetaFields);
    }

    @Override
    protected DirectoryReader wrap(DirectoryReader reader) {
        if (licenseState.isDocumentAndFieldLevelSecurityAllowed() == false) {
            return reader;
        }

        final Set<String> allowedMetaFields = this.allowedMetaFields;
        try {
            final IndicesAccessControl indicesAccessControl = getIndicesAccessControl();

            ShardId shardId = ShardUtils.extractShardId(reader);
            if (shardId == null) {
                throw new IllegalStateException(LoggerMessageFormat.format("couldn't extract shardId from reader [{}]", reader));
            }

            IndicesAccessControl.IndexAccessControl permissions = indicesAccessControl.getIndexPermissions(shardId.getIndexName());
            // No permissions have been defined for an index, so don't intercept the index reader for access control
            if (permissions == null) {
                return reader;
            }

            if (permissions.getQueries() != null) {
                BooleanQuery.Builder filter = new BooleanQuery.Builder();
                for (BytesReference bytesReference : permissions.getQueries()) {
                    QueryShardContext queryShardContext = queryShardContextProvider.apply(shardId);
                    bytesReference = evaluateTemplate(bytesReference);
                    try (XContentParser parser = XContentFactory.xContent(bytesReference).createParser(bytesReference)) {
                        Optional<QueryBuilder> queryBuilder = queryShardContext.newParseContext(parser).parseInnerQueryBuilder();
                        if (queryBuilder.isPresent()) {
                            verifyRoleQuery(queryBuilder.get());
                            failIfQueryUsesClient(scriptService, queryBuilder.get(), queryShardContext);
                            ParsedQuery parsedQuery = queryShardContext.toQuery(queryBuilder.get());
                            filter.add(parsedQuery.query(), SHOULD);
                        }
                    }
                }
                // at least one of the queries should match
                filter.setMinimumNumberShouldMatch(1);
                reader = DocumentSubsetReader.wrap(reader, bitsetFilterCache, new ConstantScoreQuery(filter.build()));
            }

            if (permissions.getFieldPermissions().hasFieldLevelSecurity()) {
                // now add the allowed fields based on the current granted permissions and :
                Set<String> allowedFields = permissions.getFieldPermissions().resolveAllowedFields(allowedMetaFields, mapperService);
                resolveParentChildJoinFields(allowedFields);
                reader = FieldSubsetReader.wrap(reader, allowedFields);
            }

            return reader;
        } catch (IOException e) {
            logger.error("Unable to apply field level security");
            throw ExceptionsHelper.convertToElastic(e);
        }
    }

    @Override
    protected IndexSearcher wrap(IndexSearcher searcher) throws EngineException {
        if (licenseState.isDocumentAndFieldLevelSecurityAllowed() == false) {
            return searcher;
        }

        final DirectoryReader directoryReader = (DirectoryReader) searcher.getIndexReader();
        if (directoryReader instanceof DocumentSubsetDirectoryReader) {
            // The reasons why we return a custom searcher:
            // 1) in the case the role query is sparse then large part of the main query can be skipped
            // 2) If the role query doesn't match with any docs in a segment, that a segment can be skipped
            IndexSearcher indexSearcher = new IndexSearcherWrapper((DocumentSubsetDirectoryReader) directoryReader);
            indexSearcher.setQueryCache(indexSearcher.getQueryCache());
            indexSearcher.setQueryCachingPolicy(indexSearcher.getQueryCachingPolicy());
            indexSearcher.setSimilarity(indexSearcher.getSimilarity(true));
            return indexSearcher;
        }
        return searcher;
    }

    static class IndexSearcherWrapper extends IndexSearcher {

        public IndexSearcherWrapper(DocumentSubsetDirectoryReader r) {
            super(r);
        }

        @Override
        protected void search(List<LeafReaderContext> leaves, Weight weight, Collector collector) throws IOException {
            for (LeafReaderContext ctx : leaves) { // search each subreader
                final LeafCollector leafCollector;
                try {
                    leafCollector = collector.getLeafCollector(ctx);
                } catch (CollectionTerminatedException e) {
                    // there is no doc of interest in this reader context
                    // continue with the following leaf
                    continue;
                }
                // The reader is always of type DocumentSubsetReader when we get here:
                DocumentSubsetReader reader = (DocumentSubsetReader) ctx.reader();

                BitSet roleQueryBits = reader.getRoleQueryBits();
                if (roleQueryBits == null) {
                    // nothing matches with the role query, so skip this segment:
                    continue;
                }

                // if the role query result set is sparse then we should use the SparseFixedBitSet for advancing:
                if (roleQueryBits instanceof SparseFixedBitSet) {
                    Scorer scorer = weight.scorer(ctx);
                    if (scorer != null) {
                        SparseFixedBitSet sparseFixedBitSet = (SparseFixedBitSet) roleQueryBits;
                        Bits realLiveDocs = reader.getWrappedLiveDocs();
                        try {
                            intersectScorerAndRoleBits(scorer, sparseFixedBitSet, leafCollector, realLiveDocs);
                        } catch (CollectionTerminatedException e) {
                            // collection was terminated prematurely
                            // continue with the following leaf
                        }
                    }
                } else {
                    BulkScorer bulkScorer = weight.bulkScorer(ctx);
                    if (bulkScorer != null) {
                        Bits liveDocs = reader.getLiveDocs();
                        try {
                            bulkScorer.score(leafCollector, liveDocs);
                        } catch (CollectionTerminatedException e) {
                            // collection was terminated prematurely
                            // continue with the following leaf
                        }
                    }
                }
            }
        }
    }

    public Set<String> getAllowedMetaFields() {
        return allowedMetaFields;
    }

    private void resolveParentChildJoinFields(Set<String> allowedFields) {
        for (DocumentMapper mapper : mapperService.docMappers(false)) {
            ParentFieldMapper parentFieldMapper = mapper.parentFieldMapper();
            if (parentFieldMapper.active()) {
                String joinField = ParentFieldMapper.joinField(parentFieldMapper.type());
                allowedFields.add(joinField);
            }
        }
    }

    static void intersectScorerAndRoleBits(Scorer scorer, SparseFixedBitSet roleBits, LeafCollector collector, Bits acceptDocs) throws
            IOException {
        // ConjunctionDISI uses the DocIdSetIterator#cost() to order the iterators, so if roleBits has the lowest cardinality it should
        // be used first:
        DocIdSetIterator iterator = ConjunctionDISI.intersectIterators(Arrays.asList(new BitSetIterator(roleBits,
                roleBits.approximateCardinality()), scorer.iterator()));
        for (int docId = iterator.nextDoc(); docId < DocIdSetIterator.NO_MORE_DOCS; docId = iterator.nextDoc()) {
            if (acceptDocs == null || acceptDocs.get(docId)) {
                collector.collect(docId);
            }
        }
    }

    BytesReference evaluateTemplate(BytesReference querySource) throws IOException {
        try (XContentParser parser = XContentFactory.xContent(querySource).createParser(querySource)) {
            XContentParser.Token token = parser.nextToken();
            if (token != XContentParser.Token.START_OBJECT) {
                throw new ElasticsearchParseException("Unexpected token [" + token + "]");
            }
            token = parser.nextToken();
            if (token != XContentParser.Token.FIELD_NAME) {
                throw new ElasticsearchParseException("Unexpected token [" + token + "]");
            }
            if ("template".equals(parser.currentName())) {
                token = parser.nextToken();
                if (token != XContentParser.Token.START_OBJECT) {
                    throw new ElasticsearchParseException("Unexpected token [" + token + "]");
                }
                Script script = Script.parse(parser, ParseFieldMatcher.EMPTY);
                // Add the user details to the params
                Map<String, Object> params = new HashMap<>();
                if (script.getParams() != null) {
                    params.putAll(script.getParams());
                }
                User user = getUser();
                Map<String, Object> userModel = new HashMap<>();
                userModel.put("username", user.principal());
                userModel.put("full_name", user.fullName());
                userModel.put("email", user.email());
                userModel.put("roles", Arrays.asList(user.roles()));
                userModel.put("metadata", Collections.unmodifiableMap(user.metadata()));
                params.put("_user", userModel);
                // Always enforce mustache script lang:
                script = new Script(script.getType(), "mustache", script.getIdOrCode(), script.getOptions(), params);
                ExecutableScript executable = scriptService.executable(script, ScriptContext.Standard.SEARCH);
                return (BytesReference) executable.run();
            } else {
                return querySource;
            }
        }
    }

    protected IndicesAccessControl getIndicesAccessControl() {
        IndicesAccessControl indicesAccessControl = threadContext.getTransient(AuthorizationService.INDICES_PERMISSIONS_KEY);
        if (indicesAccessControl == null) {
            throw Exceptions.authorizationError("no indices permissions found");
        }
        return indicesAccessControl;
    }

    protected User getUser(){
        Authentication authentication = Authentication.getAuthentication(threadContext);
        return authentication.getUser();
    }

    /**
     * Checks whether the role query contains queries we know can't be used as DLS role query.
     */
    static void verifyRoleQuery(QueryBuilder queryBuilder) throws IOException {
        if (queryBuilder instanceof TermsQueryBuilder) {
            TermsQueryBuilder termsQueryBuilder = (TermsQueryBuilder) queryBuilder;
            if (termsQueryBuilder.termsLookup() != null) {
                throw new IllegalArgumentException("terms query with terms lookup isn't supported as part of a role query");
            }
        } else if (queryBuilder instanceof GeoShapeQueryBuilder) {
            GeoShapeQueryBuilder geoShapeQueryBuilder = (GeoShapeQueryBuilder) queryBuilder;
            if (geoShapeQueryBuilder.shape() == null) {
                throw new IllegalArgumentException("geoshape query referring to indexed shapes isn't support as part of a role query");
            }
        } else if (queryBuilder.getName().equals("percolate")) {
            // actually only if percolate query is referring to an existing document then this is problematic,
            // a normal percolate query does work. However we can't check that here as this query builder is inside
            // another module. So we don't allow the entire percolate query. I don't think users would ever use
            // a percolate query as role query, so this restriction shouldn't prohibit anyone from using dls.
            throw new IllegalArgumentException("percolate query isn't support as part of a role query");
        } else if (queryBuilder instanceof HasChildQueryBuilder) {
            throw new IllegalArgumentException("has_child query isn't support as part of a role query");
        } else if (queryBuilder instanceof HasParentQueryBuilder) {
            throw new IllegalArgumentException("has_parent query isn't support as part of a role query");
        } else if (queryBuilder instanceof BoolQueryBuilder) {
            BoolQueryBuilder boolQueryBuilder = (BoolQueryBuilder) queryBuilder;
            List<QueryBuilder> clauses = new ArrayList<>();
            clauses.addAll(boolQueryBuilder.filter());
            clauses.addAll(boolQueryBuilder.must());
            clauses.addAll(boolQueryBuilder.mustNot());
            clauses.addAll(boolQueryBuilder.should());
            for (QueryBuilder clause : clauses) {
                verifyRoleQuery(clause);
            }
        } else if (queryBuilder instanceof ConstantScoreQueryBuilder) {
            verifyRoleQuery(((ConstantScoreQueryBuilder) queryBuilder).innerQuery());
        } else if (queryBuilder instanceof FunctionScoreQueryBuilder) {
            verifyRoleQuery(((FunctionScoreQueryBuilder) queryBuilder).query());
        } else if (queryBuilder instanceof BoostingQueryBuilder) {
            verifyRoleQuery(((BoostingQueryBuilder) queryBuilder).negativeQuery());
            verifyRoleQuery(((BoostingQueryBuilder) queryBuilder).positiveQuery());
        }
    }

    /**
     * Fall back validation that verifies that queries during rewrite don't use
     * the client to make remote calls. In the case of DLS this can cause a dead
     * lock if DLS is also applied on these remote calls. For example in the
     * case of terms query with lookup, this can cause recursive execution of
     * the DLS query until the get thread pool has been exhausted:
     * https://github.com/elastic/x-plugins/issues/3145
     */
    static void failIfQueryUsesClient(ScriptService scriptService, QueryBuilder queryBuilder, QueryRewriteContext original)
            throws IOException {
        Client client = new FilterClient(original.getClient()) {
            @Override
            protected <Request extends ActionRequest, Response extends ActionResponse,
                    RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder>>
            void doExecute(Action<Request, Response, RequestBuilder> action, Request request, ActionListener<Response> listener) {
                throw new IllegalStateException("role queries are not allowed to execute additional requests");
            }
        };
        QueryRewriteContext copy = new QueryRewriteContext(original.getIndexSettings(), original.getMapperService(), scriptService, null,
                client, original.getIndexReader(), original.getClusterState(), original::nowInMillis);
        queryBuilder.rewrite(copy);
    }
}
