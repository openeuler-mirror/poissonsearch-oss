/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authz.accesscontrol;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.*;
import org.apache.lucene.util.*;
import org.apache.lucene.util.BitSet;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.support.LoggerMessageFormat;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.cache.bitset.BitsetFilterCache;
import org.elasticsearch.index.engine.EngineConfig;
import org.elasticsearch.index.engine.EngineException;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.internal.ParentFieldMapper;
import org.elasticsearch.index.query.IndexQueryParserService;
import org.elasticsearch.index.query.ParsedQuery;
import org.elasticsearch.index.settings.IndexSettings;
import org.elasticsearch.index.shard.IndexSearcherWrapper;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.shard.ShardUtils;
import org.elasticsearch.shield.authz.InternalAuthorizationService;
import org.elasticsearch.shield.authz.accesscontrol.DocumentSubsetReader.DocumentSubsetDirectoryReader;
import org.elasticsearch.shield.support.Exceptions;

import java.io.IOException;
import java.util.*;

import static org.apache.lucene.search.BooleanClause.Occur.FILTER;

/**
 * An {@link IndexSearcherWrapper} implementation that is used for field and document level security.
 * <p>
 * Based on the {@link RequestContext} this class will enable field and/or document level security.
 * <p>
 * Field level security is enabled by wrapping the original {@link DirectoryReader} in a {@link FieldSubsetReader}
 * in the {@link #wrap(DirectoryReader)} method.
 * <p>
 * Document level security is enabled by wrapping the original {@link DirectoryReader} in a {@link DocumentSubsetReader}
 * instance.
 */
public final class ShieldIndexSearcherWrapper extends AbstractComponent implements IndexSearcherWrapper {

    private final MapperService mapperService;
    private final Set<String> allowedMetaFields;
    private final IndexQueryParserService parserService;
    private final BitsetFilterCache bitsetFilterCache;

    @Inject
    public ShieldIndexSearcherWrapper(@IndexSettings Settings indexSettings, IndexQueryParserService parserService, MapperService mapperService, BitsetFilterCache bitsetFilterCache) {
        super(indexSettings);
        this.mapperService = mapperService;
        this.parserService = parserService;
        this.bitsetFilterCache = bitsetFilterCache;

        Set<String> allowedMetaFields = new HashSet<>();
        allowedMetaFields.addAll(Arrays.asList(MapperService.getAllMetaFields()));
        allowedMetaFields.add("_source"); // TODO: add _source to MapperService#META_FIELDS?
        allowedMetaFields.add("_version"); // TODO: add _version to MapperService#META_FIELDS?
        allowedMetaFields.remove("_all"); // The _all field contains actual data and we can't include that by default.

        this.allowedMetaFields = Collections.unmodifiableSet(allowedMetaFields);
    }

    @Override
    public DirectoryReader wrap(DirectoryReader reader) {
        final Set<String> allowedMetaFields = this.allowedMetaFields;
        try {
            RequestContext context = RequestContext.current();
            if (context == null) {
                logger.debug("couldn't locate the current request, field level security will only allow meta fields");
                return FieldSubsetReader.wrap(reader, allowedMetaFields);
            }

            IndicesAccessControl indicesAccessControl = context.getRequest().getFromContext(InternalAuthorizationService.INDICES_PERMISSIONS_KEY);
            if (indicesAccessControl == null) {
                throw Exceptions.authorizationError("no indices permissions found");
            }
            ShardId shardId = ShardUtils.extractShardId(reader);
            if (shardId == null) {
                throw new IllegalStateException(LoggerMessageFormat.format("couldn't extract shardId from reader [{}]", reader));
            }

            IndicesAccessControl.IndexAccessControl permissions = indicesAccessControl.getIndexPermissions(shardId.getIndex());
            // No permissions have been defined for an index, so don't intercept the index reader for access control
            if (permissions == null) {
                return reader;
            }

            if (permissions.getQueries() != null) {
                BooleanQuery.Builder roleQuery = new BooleanQuery.Builder();
                for (BytesReference bytesReference : permissions.getQueries()) {
                    ParsedQuery parsedQuery = parserService.parse(bytesReference);
                    roleQuery.add(parsedQuery.query(), FILTER);
                }
                reader = DocumentSubsetReader.wrap(reader, bitsetFilterCache, roleQuery.build());
            }

            if (permissions.getFields() != null) {
                // now add the allowed fields based on the current granted permissions and :
                Set<String> allowedFields = new HashSet<>(allowedMetaFields);
                for (String field : permissions.getFields()) {
                    allowedFields.addAll(mapperService.simpleMatchToIndexNames(field));
                }
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
    public IndexSearcher wrap(EngineConfig engineConfig, IndexSearcher searcher) throws EngineException {
        final DirectoryReader directoryReader = (DirectoryReader) searcher.getIndexReader();
        if (directoryReader instanceof DocumentSubsetDirectoryReader) {
            // The reasons why we return a custom searcher:
            // 1) in the case the role query is sparse then large part of the main query can be skipped
            // 2) If the role query doesn't match with any docs in a segment, that a segment can be skipped
            IndexSearcher indexSearcher = new IndexSearcher(directoryReader) {

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

                        Scorer scorer = weight.scorer(ctx);
                        if (scorer != null) {
                            try {
                                // if the role query result set is sparse then we should use the SparseFixedBitSet for advancing:
                                if (roleQueryBits instanceof SparseFixedBitSet) {
                                    SparseFixedBitSet sparseFixedBitSet = (SparseFixedBitSet) roleQueryBits;
                                    Bits realLiveDocs = reader.getWrappedLiveDocs();
                                    intersectScorerAndRoleBits(scorer, sparseFixedBitSet, leafCollector, realLiveDocs);
                                } else {
                                    BulkScorer bulkScorer = weight.bulkScorer(ctx);
                                    Bits liveDocs = reader.getLiveDocs();
                                    bulkScorer.score(leafCollector, liveDocs);
                                }
                            } catch (CollectionTerminatedException e) {
                                // collection was terminated prematurely
                                // continue with the following leaf
                            }
                        }

                    }
                }
            };
            indexSearcher.setQueryCache(engineConfig.getQueryCache());
            indexSearcher.setQueryCachingPolicy(engineConfig.getQueryCachingPolicy());
            indexSearcher.setSimilarity(engineConfig.getSimilarity());
            return indexSearcher;
        }
        return searcher;
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

    static void intersectScorerAndRoleBits(Scorer scorer, SparseFixedBitSet roleBits, LeafCollector collector, Bits acceptDocs) throws IOException {
        // ConjunctionDISI uses the DocIdSetIterator#cost() to order the iterators, so if roleBits has the lowest cardinality it should be used first:
        DocIdSetIterator iterator = ConjunctionDISI.intersect(Arrays.asList(new BitSetIterator(roleBits, roleBits.approximateCardinality()), scorer));
        for (int docId = iterator.nextDoc(); docId < DocIdSetIterator.NO_MORE_DOCS; docId = iterator.nextDoc()) {
            if (acceptDocs == null || acceptDocs.get(docId)) {
                collector.collect(docId);
            }
        }
    }

}
