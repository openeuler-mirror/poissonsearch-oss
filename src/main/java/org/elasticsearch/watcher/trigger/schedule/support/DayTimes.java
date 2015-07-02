/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.trigger.schedule.support;

import com.google.common.primitives.Ints;
import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.watcher.WatcherException;
import org.elasticsearch.watcher.trigger.schedule.ScheduleTriggerException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 *
 */
public class DayTimes implements Times {

    public static final DayTimes NOON = new DayTimes("noon", new int[] { 12 }, new int[] { 0 });
    public static final DayTimes MIDNIGHT = new DayTimes("midnight", new int[] { 0 }, new int[] { 0 });

    final int[] hour;
    final int[] minute;
    final String time;

    public DayTimes() {
        this(0, 0);
    }

    public DayTimes(int hour, int minute) {
        this(new int[] { hour }, new int[] { minute });
    }

    public DayTimes(int[] hour, int[] minute) {
        this(null, hour, minute);
    }

    DayTimes(String time, int[] hour, int[] minute) {
        this.time = time;
        this.hour = hour;
        this.minute = minute;
        validate();
    }

    public int[] hour() {
        return hour;
    }

    public int[] minute() {
        return minute;
    }

    public String time() {
        return time;
    }

    public static DayTimes parse(String time) throws ParseException {
        if (NOON.time.equals(time)) {
            return NOON;
        }
        if (MIDNIGHT.time.equals(time)) {
            return MIDNIGHT;
        }
        int[] hour;
        int[] minute;
        int i = time.indexOf(":");
        if (i < 0) {
            throw new ParseException("could not parse time [" + time + "]. time format must be in the form of hh:mm");
        }
        if (i == time.length() - 1 || time.indexOf(":", i + 1) >= 0) {
            throw new ParseException("could not parse time [" + time + "]. time format must be in the form of hh:mm");
        }
        String hrStr = time.substring(0, i);
        String minStr = time.substring(i + 1);
        if (hrStr.length() != 1 && hrStr.length() != 2) {
            throw new ParseException("could not parse time [" + time + "]. time format must be in the form of hh:mm");
        }
        if (minStr.length() != 2) {
            throw new ParseException("could not parse time [" + time + "]. time format must be in the form of hh:mm");
        }
        try {
            hour = new int[] { Integer.parseInt(hrStr) };
        } catch (NumberFormatException nfe) {
            throw new ParseException("could not parse time [" + time + "]. time hour [" + hrStr + "] is not a number ");
        }
        try {
            minute = new int[] { Integer.parseInt(minStr) };
        } catch (NumberFormatException nfe) {
            throw new ParseException("could not parse time [" + time + "]. time minute [" + minStr + "] is not a number ");
        }
        return new DayTimes(time, hour, minute);
    }

    public void validate() {
        for (int i = 0; i < hour.length; i++) {
            if (!validHour(hour[i])) {
                throw new ScheduleTriggerException("invalid time [" + this + "]. invalid time hour value [" + hour[i] + "]. time hours must be between 0 and 23 incl.");
            }
        }
        for (int i = 0; i < minute.length; i++) {
            if (!validMinute(minute[i])) {
                throw new ScheduleTriggerException("invalid time [" + this + "]. invalid time minute value [" + minute[i] + "]. time minutes must be between 0 and 59 incl.");
            }
        }
    }

    static boolean validHour(int hour) {
        return hour >= 0 && hour < 24;
    }

    static boolean validMinute(int minute) {
        return minute >= 0 && minute < 60;
    }

    public String cron() {
        String hrs = Ints.join(",", hour);
        String mins = Ints.join(",", minute);
        return "0 " + mins + " " + hrs + " * * ?";
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        if (time != null) {
            return builder.value(time);
        }
        return builder.startObject()
                .field(HOUR_FIELD.getPreferredName(), hour)
                .field(MINUTE_FIELD.getPreferredName(), minute)
                .endObject();
    }

    @Override
    public String toString() {
        if (time != null) {
            return time;
        }
        StringBuilder sb = new StringBuilder();
        for (int h = 0; h < hour.length; h++) {
            for (int m = 0; m < minute.length; m++) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                if (hour[h] < 10) {
                    sb.append("0");
                }
                sb.append(hour[h]).append(":");
                if (minute[m] < 10) {
                    sb.append("0");
                }
                sb.append(minute[m]);
            }
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DayTimes time = (DayTimes) o;

        if (!Arrays.equals(hour, time.hour)) return false;
        if (!Arrays.equals(minute, time.minute)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(hour);
        result = 31 * result + Arrays.hashCode(minute);
        return result;
    }

    public static DayTimes parse(XContentParser parser, XContentParser.Token token) throws IOException, ParseException  {
        if (token == XContentParser.Token.VALUE_STRING) {
            return DayTimes.parse(parser.text());
        }
        if (token != XContentParser.Token.START_OBJECT) {
            throw new ParseException("could not parse time. expected string/number value or an object, but found [" + token + "]");
        }
        List<Integer> hours = new ArrayList<>();
        List<Integer> minutes = new ArrayList<>();
        String currentFieldName = null;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (ParseFieldMatcher.STRICT.match(currentFieldName, HOUR_FIELD)) {
                if (token.isValue()) {
                    hours.add(parseHourValue(parser, token));
                } else if (token == XContentParser.Token.START_ARRAY) {
                    while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                        hours.add(parseHourValue(parser, token));
                    }
                } else {
                    throw new ParseException("invalid time hour value. expected string/number value or an array of string/number values, but found [" + token + "]");
                }
            } else if (ParseFieldMatcher.STRICT.match(currentFieldName, MINUTE_FIELD)) {
                if (token.isValue()) {
                    minutes.add(parseMinuteValue(parser, token));
                } else if (token == XContentParser.Token.START_ARRAY) {
                    while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                        minutes.add(parseMinuteValue(parser, token));
                    }
                } else {
                    throw new ParseException("invalid time minute value. expected string/number value or an array of string/number values, but found [" + token + "]");
                }
            }
        }
        if (hours.isEmpty()) {
            hours.add(0);
        }
        if (minutes.isEmpty()) {
            minutes.add(0);
        }
        return new DayTimes(Ints.toArray(hours), Ints.toArray(minutes));
    }

    public static int parseHourValue(XContentParser parser, XContentParser.Token token) throws IOException, ParseException {
        switch (token) {
            case VALUE_NUMBER:
                int hour = parser.intValue();
                if (!DayTimes.validHour(hour)) {
                    throw new ParseException("invalid time hour value [" + hour + "] (possible values may be between 0 and 23 incl.)");
                }
                return hour;

            case VALUE_STRING:
                String value = parser.text();
                try {
                    hour = Integer.valueOf(value);
                    if (!DayTimes.validHour(hour)) {
                        throw new ParseException("invalid time hour value [" + hour + "] (possible values may be between 0 and 23 incl.)");
                    }
                    return hour;
                } catch (NumberFormatException nfe) {
                    throw new ParseException("invalid time hour value [" + value + "]");
                }

            default:
                throw new ParseException("invalid hour value. expected string/number value, but found [" + token + "]");
        }
    }

    public static int parseMinuteValue(XContentParser parser, XContentParser.Token token) throws IOException, ParseException {
        switch (token) {
            case VALUE_NUMBER:
                int minute = parser.intValue();
                if (!DayTimes.validMinute(minute)) {
                    throw new ParseException("invalid time minute value [" + minute + "] (possible values may be between 0 and 59 incl.)");
                }
                return minute;

            case VALUE_STRING:
                String value = parser.text();
                try {
                    minute = Integer.valueOf(value);
                    if (!DayTimes.validMinute(minute)) {
                        throw new ParseException("invalid time minute value [" + minute + "] (possible values may be between 0 and 59 incl.)");
                    }
                    return minute;
                } catch (NumberFormatException nfe) {
                    throw new ParseException("invalid time minute value [" + value + "]");
                }

            default:
                throw new ParseException("invalid time minute value. expected string/number value, but found [" + token + "]");
        }
    }

    public static class ParseException extends WatcherException {

        public ParseException(String msg) {
            super(msg);
        }

        public ParseException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

}
