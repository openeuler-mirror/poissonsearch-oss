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

package org.elasticsearch.indexer;

import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.indexer.settings.IndexerSettings;

/**
 * @author kimchy (shay.banon)
 */
public class AbstractIndexerComponent implements IndexerComponent {

    protected final ESLogger logger;

    protected final IndexerName indexerName;

    protected final Settings indexSettings;

    protected final Settings componentSettings;

    protected AbstractIndexerComponent(IndexerName indexerName, @IndexerSettings Settings indexSettings) {
        this.indexerName = indexerName;
        this.indexSettings = indexSettings;
        this.componentSettings = indexSettings.getComponentSettings(getClass());

        this.logger = Loggers.getLogger(getClass(), indexSettings, indexerName);
    }

    protected AbstractIndexerComponent(IndexerName indexerName, @IndexerSettings Settings indexSettings, String prefixSettings) {
        this.indexerName = indexerName;
        this.indexSettings = indexSettings;
        this.componentSettings = indexSettings.getComponentSettings(prefixSettings, getClass());

        this.logger = Loggers.getLogger(getClass(), indexSettings, indexerName);
    }

    @Override public IndexerName indexerName() {
        return indexerName;
    }

    public String nodeName() {
        return indexSettings.get("name", "");
    }
}