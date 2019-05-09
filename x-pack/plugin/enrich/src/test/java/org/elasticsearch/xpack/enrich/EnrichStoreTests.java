/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.enrich;

import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.elasticsearch.xpack.core.enrich.EnrichPolicy;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.elasticsearch.xpack.enrich.EnrichPolicyTests.randomEnrichPolicy;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

public class EnrichStoreTests extends ESSingleNodeTestCase {

    public void testCrud() throws Exception {
        EnrichPolicy policy = randomEnrichPolicy(XContentType.JSON);
        ClusterService clusterService = getInstanceFromNode(ClusterService.class);
        String name = "my-policy";

        AtomicReference<Exception> error = saveEnrichPolicy(name, policy, clusterService);
        assertThat(error.get(), nullValue());

        EnrichPolicy result = EnrichStore.getPolicy(name, clusterService.state());
        assertThat(result, equalTo(policy));

        Map<String, EnrichPolicy> listPolicies = EnrichStore.getPolicies(clusterService.state());
        assertThat(listPolicies.size(), equalTo(1));
        assertThat(listPolicies.get(name), equalTo(policy));

        deleteEnrichPolicy(name, clusterService);
        result = EnrichStore.getPolicy(name, clusterService.state());
        assertThat(result, nullValue());
    }

    public void testPutValidation() throws Exception {
        EnrichPolicy policy = randomEnrichPolicy(XContentType.JSON);
        ClusterService clusterService = getInstanceFromNode(ClusterService.class);

        {
            String nullOrEmptyName = randomBoolean() ? "" : null;

            IllegalArgumentException error = expectThrows(IllegalArgumentException.class,
                () -> saveEnrichPolicy(nullOrEmptyName, policy, clusterService));

            assertThat(error.getMessage(), equalTo("name is missing or empty"));
        }
        {
            IllegalArgumentException error = expectThrows(IllegalArgumentException.class,
                () -> saveEnrichPolicy("my-policy", null, clusterService));

            assertThat(error.getMessage(), equalTo("policy is missing"));
        }
    }

    public void testDeleteValidation() {
        ClusterService clusterService = getInstanceFromNode(ClusterService.class);

        {
            String nullOrEmptyName = randomBoolean() ? "" : null;

            IllegalArgumentException error = expectThrows(IllegalArgumentException.class,
                () -> deleteEnrichPolicy(nullOrEmptyName, clusterService));

            assertThat(error.getMessage(), equalTo("name is missing or empty"));
        }
        {
            ResourceNotFoundException error = expectThrows(ResourceNotFoundException.class,
                () -> deleteEnrichPolicy("my-policy", clusterService));

            assertThat(error.getMessage(), equalTo("policy [my-policy] not found"));
        }
    }

    public void testGetValidation() {
        ClusterService clusterService = getInstanceFromNode(ClusterService.class);
        String nullOrEmptyName = randomBoolean() ? "" : null;

        IllegalArgumentException error = expectThrows(IllegalArgumentException.class,
            () -> EnrichStore.getPolicy(nullOrEmptyName, clusterService.state()));

        assertThat(error.getMessage(), equalTo("name is missing or empty"));

        EnrichPolicy policy = EnrichStore.getPolicy("null-policy", clusterService.state());
        assertNull(policy);
    }

    public void testListValidation()  {
        ClusterService clusterService = getInstanceFromNode(ClusterService.class);
        Map<String, EnrichPolicy> policies = EnrichStore.getPolicies(clusterService.state());
        assertTrue(policies.isEmpty());
    }

    private AtomicReference<Exception> saveEnrichPolicy(String name, EnrichPolicy policy,
                                                        ClusterService clusterService) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        EnrichStore.putPolicy(name, policy, clusterService, e -> {
            error.set(e);
            latch.countDown();
        });
        latch.await();
        return error;
    }

    private void deleteEnrichPolicy(String name, ClusterService clusterService) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        EnrichStore.deletePolicy(name, clusterService, e -> {
            error.set(e);
            latch.countDown();
        });
        latch.await();
        if (error.get() != null){
            throw error.get();
        }
    }
}
