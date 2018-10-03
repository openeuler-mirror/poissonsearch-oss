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

package org.elasticsearch.client.watcher;

import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.client.ValidationException;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.protocol.xpack.watcher.DeleteWatchRequest;
import org.elasticsearch.protocol.xpack.watcher.PutWatchRequest;
import org.elasticsearch.test.ESTestCase;

import java.util.Optional;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

public class WatchRequestValidationTests extends ESTestCase {

    public void testAcknowledgeWatchInvalidWatchId()  {
        ValidationException e = expectThrows(ValidationException.class,
            () ->  new AckWatchRequest("id with whitespaces"));
        assertThat(e.validationErrors(), hasItem("watch id contains whitespace"));
    }

    public void testAcknowledgeWatchInvalidActionId() {
        ValidationException e = expectThrows(ValidationException.class,
            () -> new AckWatchRequest("_id", "action id with whitespaces"));
        assertThat(e.validationErrors(), hasItem("action id [action id with whitespaces] contains whitespace"));
    }

    public void testAcknowledgeWatchNullActionArray() {
        // need this to prevent some compilation errors, i.e. in 1.8.0_91
        String[] nullArray = null;
        Optional<ValidationException> e = new AckWatchRequest("_id", nullArray).validate();
        assertFalse(e.isPresent());
    }

    public void testAcknowledgeWatchNullActionId() {
        ValidationException e = expectThrows(ValidationException.class,
            () ->  new AckWatchRequest("_id", new String[] {null}));
        assertThat(e.validationErrors(), hasItem("action id may not be null"));
    }

    public void testDeleteWatchInvalidWatchId() {
        ActionRequestValidationException e = new DeleteWatchRequest("id with whitespaces").validate();
        assertThat(e, is(notNullValue()));
        assertThat(e.validationErrors(), hasItem("watch id contains whitespace"));
    }

    public void testDeleteWatchNullId() {
        ActionRequestValidationException e = new DeleteWatchRequest(null).validate();
        assertThat(e, is(notNullValue()));
        assertThat(e.validationErrors(), hasItem("watch id is missing"));
    }

    public void testPutWatchInvalidWatchId() {
        ActionRequestValidationException e = new PutWatchRequest("id with whitespaces", BytesArray.EMPTY, XContentType.JSON).validate();
        assertThat(e, is(notNullValue()));
        assertThat(e.validationErrors(), hasItem("watch id contains whitespace"));
    }

    public void testPutWatchNullId() {
        ActionRequestValidationException e = new PutWatchRequest(null, BytesArray.EMPTY, XContentType.JSON).validate();
        assertThat(e, is(notNullValue()));
        assertThat(e.validationErrors(), hasItem("watch id is missing"));
    }

    public void testPutWatchSourceNull() {
        ActionRequestValidationException e = new PutWatchRequest("foo", null, XContentType.JSON).validate();
        assertThat(e, is(notNullValue()));
        assertThat(e.validationErrors(), hasItem("watch source is missing"));
    }

    public void testPutWatchContentNull() {
        ActionRequestValidationException e = new PutWatchRequest("foo", BytesArray.EMPTY, null).validate();
        assertThat(e, is(notNullValue()));
        assertThat(e.validationErrors(), hasItem("request body is missing"));
    }
}
