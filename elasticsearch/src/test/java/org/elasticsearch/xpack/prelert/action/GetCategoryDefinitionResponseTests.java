/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.action;

import org.elasticsearch.xpack.prelert.job.persistence.QueryPage;
import org.elasticsearch.xpack.prelert.job.results.CategoryDefinition;
import org.elasticsearch.xpack.prelert.support.AbstractStreamableTestCase;

import java.util.Collections;

public class GetCategoryDefinitionResponseTests extends AbstractStreamableTestCase<GetCategoryDefinitionAction.Response> {

    @Override
    protected GetCategoryDefinitionAction.Response createTestInstance() {
        QueryPage<CategoryDefinition> queryPage =
                new QueryPage<>(Collections.singletonList(new CategoryDefinition(randomAsciiOfLength(10))), 1L);
        return new GetCategoryDefinitionAction.Response(queryPage);
    }

    @Override
    protected GetCategoryDefinitionAction.Response createBlankInstance() {
        return new GetCategoryDefinitionAction.Response();
    }
}
