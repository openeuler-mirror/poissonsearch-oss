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


package org.elasticsearch.ingest;

import org.elasticsearch.ingest.processor.Processor;

import java.io.IOException;
import java.util.*;

/**
 * A pipeline is a list of {@link Processor} instances grouped under a unique id.
 */
public final class Pipeline {

    private final String id;
    private final String description;
    private final List<Processor> processors;

    public Pipeline(String id, String description, List<Processor> processors) {
        this.id = id;
        this.description = description;
        this.processors = processors;
    }

    /**
     * Modifies the data of a document to be indexed based on the processor this pipeline holds
     */
    public void execute(Data data) {
        for (Processor processor : processors) {
            processor.execute(data);
        }
    }

    /**
     * The unique id of this pipeline
     */
    public String getId() {
        return id;
    }

    /**
     * An optional description of what this pipeline is doing to the data gets processed by this pipeline.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Unmodifiable list containing each processor that operates on the data.
     */
    public List<Processor> getProcessors() {
        return processors;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Pipeline pipeline = (Pipeline) o;
        return Objects.equals(id, pipeline.id) &&
                Objects.equals(description, pipeline.description) &&
                Objects.equals(processors, pipeline.processors);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, description, processors);
    }

    public final static class Factory {

        public Pipeline create(String id, Map<String, Object> config, Map<String, Processor.Factory> processorRegistry) throws IOException {
            String description = (String) config.get("description");
            List<Processor> processors = new ArrayList<>();
            @SuppressWarnings("unchecked")
            List<Map<String, Map<String, Object>>> processorConfigs = (List<Map<String, Map<String, Object>>>) config.get("processors");
            if (processorConfigs != null ) {
                for (Map<String, Map<String, Object>> processor : processorConfigs) {
                    for (Map.Entry<String, Map<String, Object>> entry : processor.entrySet()) {
                        Processor.Factory factory = processorRegistry.get(entry.getKey());
                        if (factory != null) {
                            Map<String, Object> processorConfig = entry.getValue();
                            processors.add(factory.create(processorConfig));
                            if (processorConfig.isEmpty() == false) {
                                throw new IllegalArgumentException("processor [" + entry.getKey() + "] doesn't support one or more provided configuration parameters [" + Arrays.toString(processorConfig.keySet().toArray()) + "]");
                            }
                        } else {
                            throw new IllegalArgumentException("No processor type exist with name [" + entry.getKey() + "]");
                        }
                    }
                }
            }
            return new Pipeline(id, description, Collections.unmodifiableList(processors));
        }

    }
}
