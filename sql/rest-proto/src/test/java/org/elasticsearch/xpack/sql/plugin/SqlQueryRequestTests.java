/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.plugin;

import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.search.SearchModule;
import org.elasticsearch.test.AbstractSerializingTestCase;
import org.elasticsearch.test.ESTestCase;
import org.junit.Before;

import java.io.IOException;
import java.util.Collections;
import java.util.function.Consumer;

import static org.elasticsearch.xpack.sql.plugin.SqlTestUtils.randomFilter;
import static org.elasticsearch.xpack.sql.plugin.SqlTestUtils.randomFilterOrNull;

public class SqlQueryRequestTests extends AbstractSerializingTestCase<SqlQueryRequest> {

    public AbstractSqlRequest.Mode testMode;

    @Before
    public void setup() {
        testMode = randomFrom(AbstractSqlRequest.Mode.values());
    }

    @Override
    protected NamedWriteableRegistry getNamedWriteableRegistry() {
        SearchModule searchModule = new SearchModule(Settings.EMPTY, false, Collections.emptyList());
        return new NamedWriteableRegistry(searchModule.getNamedWriteables());
    }

    @Override
    protected NamedXContentRegistry xContentRegistry() {
        SearchModule searchModule = new SearchModule(Settings.EMPTY, false, Collections.emptyList());
        return new NamedXContentRegistry(searchModule.getNamedXContents());
    }

    @Override
    protected SqlQueryRequest createTestInstance() {
        return new SqlQueryRequest(testMode, randomAlphaOfLength(10),
                SqlTestUtils.randomFilterOrNull(random()), randomDateTimeZone(),
                between(1, Integer.MAX_VALUE), randomTV(), randomTV(), randomAlphaOfLength(10)
        );
    }

    @Override
    protected Writeable.Reader<SqlQueryRequest> instanceReader() {
        return SqlQueryRequest::new;
    }

    private TimeValue randomTV() {
        return TimeValue.parseTimeValue(randomTimeValue(), null, "test");
    }

    @Override
    protected SqlQueryRequest doParseInstance(XContentParser parser) throws IOException {
        return SqlQueryRequest.fromXContent(parser, testMode);
    }

    @Override
    protected SqlQueryRequest mutateInstance(SqlQueryRequest instance) throws IOException {
        @SuppressWarnings("unchecked")
        Consumer<SqlQueryRequest> mutator = randomFrom(
                request -> request.mode(randomValueOtherThan(request.mode(), () -> randomFrom(AbstractSqlRequest.Mode.values()))),
                request -> request.query(randomValueOtherThan(request.query(), () -> randomAlphaOfLength(5))),
                request -> request.timeZone(randomValueOtherThan(request.timeZone(), ESTestCase::randomDateTimeZone)),
                request -> request.fetchSize(randomValueOtherThan(request.fetchSize(), () -> between(1, Integer.MAX_VALUE))),
                request -> request.requestTimeout(randomValueOtherThan(request.requestTimeout(), this::randomTV)),
                request -> request.filter(randomValueOtherThan(request.filter(),
                        () -> request.filter() == null ? randomFilter(random()) : randomFilterOrNull(random()))),
                request -> request.cursor(randomValueOtherThan(request.cursor(), SqlQueryResponseTests::randomStringCursor))
        );
        SqlQueryRequest newRequest = new SqlQueryRequest(instance.mode(), instance.query(), instance.filter(),
                instance.timeZone(), instance.fetchSize(), instance.requestTimeout(), instance.pageTimeout(), instance.cursor());
        mutator.accept(newRequest);
        return newRequest;
    }
}
