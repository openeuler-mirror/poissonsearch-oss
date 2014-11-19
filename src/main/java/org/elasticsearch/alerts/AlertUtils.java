/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.alerts;

import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.script.ScriptService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 */
public final class AlertUtils {

    public final static IndicesOptions DEFAULT_INDICES_OPTIONS = IndicesOptions.lenientExpandOpen();
    public final static SearchType DEFAULT_SEARCH_TYPE = SearchType.COUNT;

    private AlertUtils() {
    }

    /**
     * Reads a new search request instance for the specified parser.
     */
    public static SearchRequest readSearchRequest(XContentParser parser) throws IOException {
        SearchRequest searchRequest = new SearchRequest();
        IndicesOptions indicesOptions = DEFAULT_INDICES_OPTIONS;
        SearchType searchType = DEFAULT_SEARCH_TYPE;

        XContentParser.Token token;
        String searchRequestFieldName = null;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                searchRequestFieldName = parser.currentName();
            } else if (token == XContentParser.Token.START_ARRAY) {
                switch (searchRequestFieldName) {
                    case "indices":
                        List<String> indices = new ArrayList<>();
                        while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                            if (token == XContentParser.Token.VALUE_STRING) {
                                indices.add(parser.textOrNull());
                            } else {
                                throw new ElasticsearchIllegalArgumentException("Unexpected token [" + token + "]");
                            }
                        }
                        searchRequest.indices(indices.toArray(new String[indices.size()]));
                        break;
                    default:
                        throw new ElasticsearchIllegalArgumentException("Unexpected field [" + searchRequestFieldName + "]");
                }
            } else if (token == XContentParser.Token.START_OBJECT) {
                XContentBuilder builder;
                switch (searchRequestFieldName) {
                    case "body":
                        builder = XContentBuilder.builder(parser.contentType().xContent());
                        builder.copyCurrentStructure(parser);
                        searchRequest.source(builder);
                        break;
                    case "indices_options":
                        boolean expandOpen = indicesOptions.expandWildcardsOpen();
                        boolean expandClosed = indicesOptions.expandWildcardsClosed();
                        boolean allowNoIndices = indicesOptions.allowNoIndices();
                        boolean ignoreUnavailable = indicesOptions.ignoreUnavailable();

                        String indicesFieldName = null;
                        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                            if (token == XContentParser.Token.FIELD_NAME) {
                                indicesFieldName = parser.currentName();
                            } else if (token.isValue()) {
                                switch (indicesFieldName) {
                                    case "expand_wildcards":
                                        switch (parser.text()) {
                                            case "all":
                                                expandOpen = true;
                                                expandClosed = true;
                                                break;
                                            case "open":
                                                expandOpen = true;
                                                break;
                                            case "closed":
                                                expandClosed = true;
                                                break;
                                            case "none":
                                                break;
                                            default:
                                                throw new ElasticsearchIllegalArgumentException("Unexpected value [" + parser.text() + "]");
                                        }
                                        break;
                                    case "ignore_unavailable":
                                        ignoreUnavailable = parser.booleanValue();
                                        break;
                                    case "allow_no_indices":
                                        allowNoIndices = parser.booleanValue();
                                        break;
                                    default:
                                        throw new ElasticsearchIllegalArgumentException("Unexpected field [" + indicesFieldName + "]");
                                }
                            } else {
                                throw new ElasticsearchIllegalArgumentException("Unexpected token [" + token + "]");
                            }
                        }
                        indicesOptions = IndicesOptions.fromOptions(ignoreUnavailable, allowNoIndices, expandOpen, expandClosed);
                        break;
                    default:
                        throw new ElasticsearchIllegalArgumentException("Unexpected field [" + searchRequestFieldName + "]");
                }
            } else if (token.isValue()) {
                switch (searchRequestFieldName) {
                    case "template_name":
                        searchRequest.templateName(parser.textOrNull());
                        break;
                    case "template_type":
                        searchRequest.templateType(readScriptType(parser.textOrNull()));
                        break;
                    case "search_type":
                        searchType = SearchType.fromString(parser.text());
                        break;
                    default:
                        throw new ElasticsearchIllegalArgumentException("Unexpected field [" + searchRequestFieldName + "]");
                }
            } else {
                throw new ElasticsearchIllegalArgumentException("Unexpected field [" + searchRequestFieldName + "]");
            }
        }

        searchRequest.searchType(searchType);
        searchRequest.indicesOptions(indicesOptions);
        return searchRequest;
    }

    /**
     * Writes the searchRequest to the specified builder.
     */
    public static void writeSearchRequest(SearchRequest searchRequest, XContentBuilder builder, ToXContent.Params params) throws IOException {
        if (searchRequest == null) {
            builder.nullValue();
            return;
        }

        builder.startObject();
        if (Strings.hasLength(searchRequest.source())) {
            XContentHelper.writeRawField("body", searchRequest.source(), builder, params);
        }
        if (searchRequest.templateName() != null) {
            builder.field("template_name", searchRequest.templateName());
        }
        if (searchRequest.templateType() != null) {
            builder.field("template_type", writeScriptType(searchRequest.templateType()));
        }
        builder.startArray("indices");
        for (String index : searchRequest.indices()) {
            builder.value(index);
        }
        builder.endArray();
        if (searchRequest.indicesOptions() != DEFAULT_INDICES_OPTIONS) {
            IndicesOptions options = searchRequest.indicesOptions();
            builder.startObject("indices_options");
            String value;
            if (options.expandWildcardsClosed() && options.expandWildcardsOpen()) {
                value = "all";
            } else if (options.expandWildcardsOpen()) {
                value = "open";
            } else if (options.expandWildcardsClosed()) {
                value = "closed";
            } else {
                value = "none";
            }
            builder.field("expand_wildcards", value);
            builder.field("ignore_unavailable", options.ignoreUnavailable());
            builder.field("allow_no_indices", options.allowNoIndices());
            builder.endObject();
        }
        if (searchRequest.searchType() != DEFAULT_SEARCH_TYPE) {
            builder.field("search_type", searchRequest.searchType().toString().toLowerCase(Locale.ENGLISH));
        }
        builder.endObject();
    }

    private static ScriptService.ScriptType readScriptType(String value) {
        switch (value) {
            case "indexed":
                return ScriptService.ScriptType.INDEXED;
            case "inline":
                return ScriptService.ScriptType.INLINE;
            case "file":
                return ScriptService.ScriptType.FILE;
            default:
                throw new ElasticsearchIllegalArgumentException("Unknown script_type value [" + value + "]");
        }
    }

    private static String writeScriptType(ScriptService.ScriptType value) {
        switch (value) {
            case INDEXED:
                return "indexed";
            case INLINE:
                return "inline";
            case FILE:
                return "file";
            default:
                throw new ElasticsearchIllegalArgumentException("Illegal script_type value [" + value + "]");
        }
    }

}
