/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
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

package org.elasticsearch.search.internal;

import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.facets.Facets;
import org.elasticsearch.util.io.stream.StreamInput;
import org.elasticsearch.util.io.stream.StreamOutput;
import org.elasticsearch.util.io.stream.Streamable;
import org.elasticsearch.util.json.JsonBuilder;
import org.elasticsearch.util.json.ToJson;

import java.io.IOException;

import static org.elasticsearch.search.facets.Facets.*;
import static org.elasticsearch.search.internal.InternalSearchHits.*;

/**
 * @author kimchy (Shay Banon)
 */
public class InternalSearchResponse implements Streamable, ToJson {

    private InternalSearchHits hits;

    private Facets facets;

    private InternalSearchResponse() {
    }

    public InternalSearchResponse(InternalSearchHits hits, Facets facets) {
        this.hits = hits;
        this.facets = facets;
    }

    public SearchHits hits() {
        return hits;
    }

    public Facets facets() {
        return facets;
    }

    @Override public void toJson(JsonBuilder builder, Params params) throws IOException {
        hits.toJson(builder, params);
        if (facets != null) {
            facets.toJson(builder, params);
        }
    }

    public static InternalSearchResponse readInternalSearchResponse(StreamInput in) throws IOException {
        InternalSearchResponse response = new InternalSearchResponse();
        response.readFrom(in);
        return response;
    }

    @Override public void readFrom(StreamInput in) throws IOException {
        hits = readSearchHits(in);
        if (in.readBoolean()) {
            facets = readFacets(in);
        }
    }

    @Override public void writeTo(StreamOutput out) throws IOException {
        hits.writeTo(out);
        if (facets == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            facets.writeTo(out);
        }
    }
}
