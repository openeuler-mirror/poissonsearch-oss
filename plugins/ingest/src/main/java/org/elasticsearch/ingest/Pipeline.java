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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class Pipeline {

    private final String id;
    private final String description;
    private final List<Processor> processors;

    private Pipeline(String id, String description, List<Processor> processors) {
        this.id = id;
        this.description = description;
        this.processors = processors;
    }

    public void execute(Data data) {
        for (Processor processor : processors) {
            processor.execute(data);
        }
    }

    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public List<Processor> getProcessors() {
        return processors;
    }

    public final static class Builder {

        private final String name;
        private String description;
        private List<Processor> processors = new ArrayList<>();

        public Builder(String name) {
            this.name = name;
        }

        public Builder(Map<String, Object> config) {
            name = (String) config.get("name");
            description = (String) config.get("description");
            @SuppressWarnings("unchecked")
            List<Map<String, Map<String, Object>>> processors = (List<Map<String, Map<String, Object>>>) config.get("processors");
            if (processors != null ) {
                for (Map<String, Map<String, Object>> processor : processors) {
                    for (Map.Entry<String, Map<String, Object>> entry : processor.entrySet()) {
                        // TODO: add lookup service...
                        if ("simple".equals(entry.getKey())) {
                            SimpleProcessor.Builder builder = new SimpleProcessor.Builder();
                            builder.fromMap(entry.getValue());
                            this.processors.add(builder.build());
                        } else {
                            throw new UnsupportedOperationException();
                        }
                    }
                }
            }
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public void addProcessors(Processor.Builder... processors) {
            for (Processor.Builder processor : processors) {
                this.processors.add(processor.build());
            }
        }

        public Pipeline build() {
            return new Pipeline(name, description, Collections.unmodifiableList(processors));
        }
    }
}
