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
 */
package org.elasticsearch.persistent;

import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.test.AbstractWireSerializingTestCase;
import org.elasticsearch.persistent.PersistentActionCoordinator.State;
import org.elasticsearch.persistent.PersistentActionCoordinator.Status;

import static org.hamcrest.Matchers.containsString;

public class PersistentActionCoordinatorStatusTests extends AbstractWireSerializingTestCase<Status> {

    @Override
    protected Status createTestInstance() {
        return new Status(randomFrom(State.values()));
    }

    @Override
    protected Writeable.Reader<Status> instanceReader() {
        return Status::new;
    }

    public void testToString() {
        assertThat(createTestInstance().toString(), containsString("state"));
    }
}