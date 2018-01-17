/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.expression.function.scalar.datetime;

import org.elasticsearch.xpack.sql.expression.Expression;
import org.elasticsearch.xpack.sql.expression.function.scalar.datetime.DateTimeProcessor.DateTimeExtractor;
import org.elasticsearch.xpack.sql.tree.Location;
import org.elasticsearch.xpack.sql.tree.NodeInfo.NodeCtor2;
import org.joda.time.DateTimeZone;

import java.time.temporal.ChronoField;

public class WeekOfWeekYear extends DateTimeFunction {
    public WeekOfWeekYear(Location location, Expression field, DateTimeZone timeZone) {
        super(location, field, timeZone);
    }

    @Override
    protected NodeCtor2<Expression, DateTimeZone, DateTimeFunction> ctorForInfo() {
        return WeekOfWeekYear::new;
    }

    @Override
    protected WeekOfWeekYear replaceChild(Expression newChild) {
        return new WeekOfWeekYear(location(), newChild, timeZone());
    }

    @Override
    public String dateTimeFormat() {
        return "w";
    }

    @Override
    protected ChronoField chronoField() {
        return ChronoField.ALIGNED_WEEK_OF_YEAR;
    }

    @Override
    protected DateTimeExtractor extractor() {
        return DateTimeExtractor.WEEK_OF_YEAR;
    }
}
