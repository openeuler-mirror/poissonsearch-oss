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

package org.elasticsearch.cloud.gce.tests;

import org.junit.Test;

public class GceSameTagsTest extends GceAbstractTest {

    public GceSameTagsTest() {
        super(GceComputeServiceTwoNodesSameTagsMock.class);
    }

    @Test
    public void two_nodes_should_run_no_tag() {
        // Then we start our node for tests
        nodeBuilder(null);

        // Let's start a second node
        nodeBuilder(null);

        // We expect having 1 nodes as part of the cluster, let's test that
        checkNumberOfNodes(2);
    }

    @Test
    public void two_nodes_should_run_two_tags() {
        // Then we start our node for tests
        nodeBuilder("elasticsearch,dev");

        // Let's start a second node
        nodeBuilder("elasticsearch,dev");

        // We expect having 2 nodes as part of the cluster, let's test that
        checkNumberOfNodes(2);
    }

    @Test
    public void two_nodes_should_run_one_tag() {
        // Then we start our node for tests
        nodeBuilder("elasticsearch");

        // Let's start a second node
        nodeBuilder("elasticsearch");

        // We expect having 2 nodes as part of the cluster, let's test that
        checkNumberOfNodes(2);
    }
}
