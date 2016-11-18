/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.job.condition;

import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.io.stream.Writeable.Reader;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.prelert.job.messages.Messages;
import org.elasticsearch.xpack.prelert.support.AbstractSerializingTestCase;

import static org.hamcrest.Matchers.containsString;

public class ConditionTests extends AbstractSerializingTestCase<Condition> {

    public void testSetValues() {
        Condition cond = new Condition(Operator.EQ, "5");
        assertEquals(Operator.EQ, cond.getOperator());
        assertEquals("5", cond.getValue());
    }

    public void testHashCodeAndEquals() {
        Condition cond1 = new Condition(Operator.MATCH, "regex");
        Condition cond2 = new Condition(Operator.MATCH, "regex");

        assertEquals(cond1, cond2);
        assertEquals(cond1.hashCode(), cond2.hashCode());

        Condition cond3 = new Condition(Operator.EQ, "5");
        assertFalse(cond1.equals(cond3));
        assertFalse(cond1.hashCode() == cond3.hashCode());
    }

    @Override
    protected Condition createTestInstance() {
        Operator op = randomFrom(Operator.values());
        Condition condition;
        switch (op) {
        case EQ:
        case GT:
        case GTE:
        case LT:
        case LTE:
            condition = new Condition(op, Double.toString(randomDouble()));
            break;
        case MATCH:
            condition = new Condition(op, randomAsciiOfLengthBetween(1, 20));
            break;
        default:
            throw new AssertionError("Unknown operator selected: " + op.getName());
        }
        return condition;
    }

    @Override
    protected Reader<Condition> instanceReader() {
        return Condition::new;
    }

    @Override
    protected Condition parseInstance(XContentParser parser, ParseFieldMatcher matcher) {
        return Condition.PARSER.apply(parser, () -> matcher);
    }

    public void testInvalidTransformName() throws Exception {
        BytesArray json = new BytesArray("{ \"value\":\"someValue\" }");
        XContentParser parser = XContentFactory.xContent(json).createParser(json);
        IllegalArgumentException ex = expectThrows(IllegalArgumentException.class,
                () -> Condition.PARSER.apply(parser, () -> ParseFieldMatcher.STRICT));
        assertThat(ex.getMessage(), containsString("Required [operator]"));
    }

    public void testVerifyArgsNumericArgs() {
        new Condition(Operator.LTE, "100");
        new Condition(Operator.GT, "10.0");
    }

    public void testVerify_GivenEmptyValue() {
        IllegalArgumentException e = ESTestCase.expectThrows(IllegalArgumentException.class, () -> new Condition(Operator.LT, ""));
        assertEquals(Messages.getMessage(Messages.JOB_CONFIG_CONDITION_INVALID_VALUE_NUMBER, ""), e.getMessage());
    }

    public void testVerify_GivenInvalidRegex() {
        IllegalArgumentException e = ESTestCase.expectThrows(IllegalArgumentException.class, () -> new Condition(Operator.MATCH, "[*"));
        assertEquals(Messages.getMessage(Messages.JOB_CONFIG_CONDITION_INVALID_VALUE_REGEX, "[*"), e.getMessage());
    }

    public void testVerify_GivenNullRegex() {
        IllegalArgumentException e = ESTestCase.expectThrows(IllegalArgumentException.class,
                () -> new Condition(Operator.MATCH, null));
        assertEquals(Messages.getMessage(Messages.JOB_CONFIG_CONDITION_INVALID_VALUE_NULL, "[*"), e.getMessage());
    }
}
