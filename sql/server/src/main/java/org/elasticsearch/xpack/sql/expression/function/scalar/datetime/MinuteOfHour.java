/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.expression.function.scalar.datetime;

import org.elasticsearch.xpack.sql.expression.Expression;
import org.elasticsearch.xpack.sql.tree.Location;
import org.joda.time.ReadableDateTime;

import java.time.temporal.ChronoField;
import java.util.TimeZone;

public class MinuteOfHour extends DateTimeFunction {

    public MinuteOfHour(Location location, Expression argument, TimeZone timeZone) {
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
    protected int extract(ReadableDateTime dt) {
        return dt.getMinuteOfHour();
    }

    @Override
    protected ChronoField chronoField() {
        return ChronoField.MINUTE_OF_HOUR;
    }
}
