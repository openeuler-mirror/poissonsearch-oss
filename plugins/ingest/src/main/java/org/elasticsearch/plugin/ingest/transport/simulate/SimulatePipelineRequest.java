/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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

package org.elasticsearch.plugin.ingest.transport.simulate;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.ingest.IngestDocument;
import org.elasticsearch.ingest.Pipeline;
import org.elasticsearch.ingest.ConfigurationUtils;
import org.elasticsearch.plugin.ingest.PipelineStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.action.ValidateActions.addValidationError;
import static org.elasticsearch.ingest.IngestDocument.MetaData;

public class SimulatePipelineRequest extends ActionRequest {

    private String id;
    private boolean verbose;
    private BytesReference source;

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = null;
        if (source == null) {
            validationException = addValidationError("source is missing", validationException);
        }
        return validationException;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public BytesReference getSource() {
        return source;
    }

    public void setSource(BytesReference source) {
        this.source = source;
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        id = in.readString();
        verbose = in.readBoolean();
        source = in.readBytesReference();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(id);
        out.writeBoolean(verbose);
        out.writeBytesReference(source);
    }

    public static final class Fields {
        static final String PIPELINE = "pipeline";
        static final String DOCS = "docs";
        static final String SOURCE = "_source";
    }

    static class Parsed {
        private final List<IngestDocument> documents;
        private final Pipeline pipeline;
        private final boolean verbose;

        Parsed(Pipeline pipeline, List<IngestDocument> documents, boolean verbose) {
            this.pipeline = pipeline;
            this.documents = Collections.unmodifiableList(documents);
            this.verbose = verbose;
        }

        public Pipeline getPipeline() {
            return pipeline;
        }

        public List<IngestDocument> getDocuments() {
            return documents;
        }

        public boolean isVerbose() {
            return verbose;
        }
    }

    private static final Pipeline.Factory PIPELINE_FACTORY = new Pipeline.Factory();
    static final String SIMULATED_PIPELINE_ID = "_simulate_pipeline";

    static Parsed parseWithPipelineId(String pipelineId, Map<String, Object> config, boolean verbose, PipelineStore pipelineStore) {
        if (pipelineId == null) {
            throw new IllegalArgumentException("param [pipeline] is null");
        }
        Pipeline pipeline = pipelineStore.get(pipelineId);
        List<IngestDocument> ingestDocumentList = parseDocs(config);
        return new Parsed(pipeline, ingestDocumentList, verbose);
    }

    static Parsed parse(Map<String, Object> config, boolean verbose, PipelineStore pipelineStore) throws Exception {
        Map<String, Object> pipelineConfig = ConfigurationUtils.readMap(config, Fields.PIPELINE);
        Pipeline pipeline = PIPELINE_FACTORY.create(SIMULATED_PIPELINE_ID, pipelineConfig, pipelineStore.getProcessorFactoryRegistry());
        List<IngestDocument> ingestDocumentList = parseDocs(config);
        return new Parsed(pipeline, ingestDocumentList, verbose);
    }

    private static List<IngestDocument> parseDocs(Map<String, Object> config) {
        List<Map<String, Object>> docs = ConfigurationUtils.readList(config, Fields.DOCS);
        List<IngestDocument> ingestDocumentList = new ArrayList<>();
        for (Map<String, Object> dataMap : docs) {
            Map<String, Object> document = ConfigurationUtils.readMap(dataMap, Fields.SOURCE);
            IngestDocument ingestDocument = new IngestDocument(ConfigurationUtils.readStringProperty(dataMap, MetaData.INDEX.getFieldName(), "_index"),
                    ConfigurationUtils.readStringProperty(dataMap, MetaData.TYPE.getFieldName(), "_type"),
                    ConfigurationUtils.readStringProperty(dataMap, MetaData.ID.getFieldName(), "_id"),
                    ConfigurationUtils.readOptionalStringProperty(dataMap, MetaData.ROUTING.getFieldName()),
                    ConfigurationUtils.readOptionalStringProperty(dataMap, MetaData.PARENT.getFieldName()),
                    ConfigurationUtils.readOptionalStringProperty(dataMap, MetaData.TIMESTAMP.getFieldName()),
                    ConfigurationUtils.readOptionalStringProperty(dataMap, MetaData.TTL.getFieldName()),
                    document);
            ingestDocumentList.add(ingestDocument);
        }
        return ingestDocumentList;
    }
}
