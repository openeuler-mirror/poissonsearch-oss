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

package org.elasticsearch.ingest.processor;

import org.elasticsearch.ingest.core.AbstractProcessor;
import org.elasticsearch.ingest.core.AbstractProcessorFactory;
import org.elasticsearch.ingest.core.IngestDocument;
import org.elasticsearch.ingest.core.TemplateService;
import org.elasticsearch.ingest.core.ConfigurationUtils;
import org.elasticsearch.ingest.core.Processor;

import java.util.Map;

/**
 * Processor that removes existing fields. Nothing happens if the field is not present.
 */
public class RemoveProcessor extends AbstractProcessor {

    public static final String TYPE = "remove";

    private final TemplateService.Template field;

    RemoveProcessor(String tag, TemplateService.Template field) {
        super(tag);
        this.field = field;
    }

    public TemplateService.Template getField() {
        return field;
    }

    @Override
    public void execute(IngestDocument document) {
        document.removeField(field);
    }

    @Override
    public String getType() {
        return TYPE;
    }

    public static class Factory extends AbstractProcessorFactory<RemoveProcessor> {

        private final TemplateService templateService;

        public Factory(TemplateService templateService) {
            this.templateService = templateService;
        }

        @Override
        public RemoveProcessor doCreate(String processorTag, Map<String, Object> config) throws Exception {
            String field = ConfigurationUtils.readStringProperty(config, "field");
            return new RemoveProcessor(processorTag, templateService.compile(field));
        }
    }
}

