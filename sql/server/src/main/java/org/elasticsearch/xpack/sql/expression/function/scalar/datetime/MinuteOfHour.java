/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.expression.function.scalar.datetime;

import org.elasticsearch.xpack.sql.expression.Expression;
import org.elasticsearch.xpack.sql.tree.Location;
import org.joda.time.DateTimeZone;

import java.time.temporal.ChronoField;

public class MinuteOfHour extends DateTimeFunction {
    public MinuteOfHour(Location location, Expression argument, DateTimeZone timeZone) {
        super(location, argument, timeZone);
    }

    @Override
    public String dateTimeFormat() {
        return "m";
    }

    @Override
    public String interval() {
        return "minute";
    }

    @Override
    protected ChronoField chronoField() {
        return ChronoField.MINUTE_OF_HOUR;
    }

    @Override
    protected DateTimeExtractor extractor() {
        return DateTimeExtractor.MINUTE_OF_HOUR;
    }
}
