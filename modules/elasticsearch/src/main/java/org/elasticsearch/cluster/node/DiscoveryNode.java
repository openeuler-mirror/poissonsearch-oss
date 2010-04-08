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

package org.elasticsearch.cluster.node;

import com.google.common.collect.ImmutableList;
import org.apache.lucene.util.StringHelper;
import org.elasticsearch.util.io.stream.StreamInput;
import org.elasticsearch.util.io.stream.StreamOutput;
import org.elasticsearch.util.io.stream.Streamable;
import org.elasticsearch.util.transport.TransportAddress;
import org.elasticsearch.util.transport.TransportAddressSerializers;

import java.io.IOException;
import java.io.Serializable;

/**
 * @author kimchy (Shay Banon)
 */
public class DiscoveryNode implements Streamable, Serializable {

    public static final ImmutableList<DiscoveryNode> EMPTY_LIST = ImmutableList.of();

    private String nodeName = StringHelper.intern("");

    private String nodeId;

    private TransportAddress address;

    private boolean dataNode = true;

    private DiscoveryNode() {
    }

    public DiscoveryNode(String nodeId, TransportAddress address) {
        this("", true, nodeId, address);
    }

    public DiscoveryNode(String nodeName, boolean dataNode, String nodeId, TransportAddress address) {
        if (nodeName == null) {
            this.nodeName = StringHelper.intern("");
        } else {
            this.nodeName = StringHelper.intern(nodeName);
        }
        this.dataNode = dataNode;
        this.nodeId = StringHelper.intern(nodeId);
        this.address = address;
    }

    /**
     * The address that the node can be communicated with.
     */
    public TransportAddress address() {
        return address;
    }

    /**
     * The unique id of the node.
     */
    public String id() {
        return nodeId;
    }

    /**
     * The name of the node.
     */
    public String name() {
        return this.nodeName;
    }

    /**
     * Should this node hold data (shards) or not.
     */
    public boolean dataNode() {
        return dataNode;
    }

    public static DiscoveryNode readNode(StreamInput in) throws IOException {
        DiscoveryNode node = new DiscoveryNode();
        node.readFrom(in);
        return node;
    }

    @Override public void readFrom(StreamInput in) throws IOException {
        nodeName = StringHelper.intern(in.readUTF());
        dataNode = in.readBoolean();
        nodeId = StringHelper.intern(in.readUTF());
        address = TransportAddressSerializers.addressFromStream(in);
    }

    @Override public void writeTo(StreamOutput out) throws IOException {
        out.writeUTF(nodeName);
        out.writeBoolean(dataNode);
        out.writeUTF(nodeId);
        TransportAddressSerializers.addressToStream(out, address);
    }

    @Override public boolean equals(Object obj) {
        if (!(obj instanceof DiscoveryNode))
            return false;

        DiscoveryNode other = (DiscoveryNode) obj;
        return this.nodeId.equals(other.nodeId);
    }

    @Override public int hashCode() {
        return nodeId.hashCode();
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        if (nodeName.length() > 0) {
            sb.append('[').append(nodeName).append(']');
        }
        if (nodeId != null) {
            sb.append('[').append(nodeId).append(']');
        }
        if (dataNode) {
            sb.append("[data]");
        }
        if (address != null) {
            sb.append('[').append(address).append(']');
        }
        return sb.toString();
    }
}
