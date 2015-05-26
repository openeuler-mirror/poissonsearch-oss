/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.test;

import java.io.IOException;

import org.elasticsearch.test.rest.ElasticsearchRestTestCase;
import org.elasticsearch.test.rest.RestTestCandidate;
import org.elasticsearch.test.rest.parser.RestTestParseException;

import com.carrotsearch.randomizedtesting.annotations.Name;
import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;

/** Rest API tests subset 0 */
public class ShieldRest0Tests extends ShieldRestTestCase {
    public ShieldRest0Tests(@Name("yaml") RestTestCandidate testCandidate) {
        super(testCandidate);
    }
  
    @ParametersFactory
    public static Iterable<Object[]> parameters() throws IOException, RestTestParseException {
        return ElasticsearchRestTestCase.createParameters(0, 8);
    }
}
