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
package org.elasticsearch.util.yaml.snakeyaml.nodes;

/**
 * Stores one key value pair used in a map.
 */
public class NodeTuple {

    private final YamlNode keyNode;
    private final YamlNode valueNode;

    public NodeTuple(YamlNode keyNode, YamlNode valueNode) {
        if (keyNode == null || valueNode == null) {
            throw new NullPointerException("Nodes must be provided.");
        }
        this.keyNode = keyNode;
        this.valueNode = valueNode;
    }

    /**
     * Key node.
     */
    public YamlNode getKeyNode() {
        return keyNode;
    }

    /**
     * Value node.
     *
     * @return value
     */
    public YamlNode getValueNode() {
        return valueNode;
    }

    @Override
    public String toString() {
        return "<NodeTuple keyNode=" + keyNode.toString() + "; valueNode=" + valueNode.toString()
                + ">";
    }
}
