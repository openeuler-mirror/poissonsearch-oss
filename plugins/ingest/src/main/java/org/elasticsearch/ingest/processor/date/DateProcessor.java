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

package org.elasticsearch.ingest.processor.date;

import org.elasticsearch.ingest.Data;
import org.elasticsearch.ingest.processor.ConfigurationUtils;
import org.elasticsearch.ingest.processor.Processor;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.ISODateTimeFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DateProcessor implements Processor {

    public static final String TYPE = "date";
    static final String DEFAULT_TARGET_FIELD = "@timestamp";

    private final DateTimeZone timezone;
    private final Locale locale;
    private final String matchField;
    private final String targetField;
    private final List<String> matchFormats;
    private final List<DateParser> dateParsers;

    DateProcessor(DateTimeZone timezone, Locale locale, String matchField, List<String> matchFormats, String targetField) {
        this.timezone = timezone;
        this.locale = locale;
        this.matchField = matchField;
        this.targetField = targetField;
        this.matchFormats = matchFormats;
        this.dateParsers = new ArrayList<>();
        for (String matchFormat : matchFormats) {
             dateParsers.add(DateParserFactory.createDateParser(matchFormat, timezone, locale));
        }
    }

    @Override
    public void execute(Data data) {
        String value = data.getProperty(matchField);
        // TODO(talevy): handle custom timestamp fields

        DateTime dateTime = null;
        Exception lastException = null;
        for (DateParser dateParser : dateParsers) {
            try {
                dateTime = dateParser.parseDateTime(value);
            } catch(Exception e) {
                //try the next parser and keep track of the last exception
                lastException = e;
            }
        }

        if (dateTime == null) {
            throw new IllegalArgumentException("unable to parse date [" + value + "]", lastException);
        }

        data.addField(targetField, ISODateTimeFormat.dateTime().print(dateTime));
    }

    DateTimeZone getTimezone() {
        return timezone;
    }

    Locale getLocale() {
        return locale;
    }

    String getMatchField() {
        return matchField;
    }

    String getTargetField() {
        return targetField;
    }

    List<String> getMatchFormats() {
        return matchFormats;
    }

    public static class Factory implements Processor.Factory<DateProcessor> {

        @SuppressWarnings("unchecked")
        public DateProcessor create(Map<String, Object> config) {
            String matchField = ConfigurationUtils.readStringProperty(config, "match_field");
            String targetField = ConfigurationUtils.readStringProperty(config, "target_field", DEFAULT_TARGET_FIELD);
            String timezoneString = ConfigurationUtils.readOptionalStringProperty(config, "timezone");
            DateTimeZone timezone = timezoneString == null ? DateTimeZone.UTC : DateTimeZone.forID(timezoneString);
            String localeString = ConfigurationUtils.readOptionalStringProperty(config, "locale");
            Locale locale = localeString == null ? Locale.ENGLISH : Locale.forLanguageTag(localeString);
            List<String> matchFormats = ConfigurationUtils.readStringList(config, "match_formats");
            return new DateProcessor(timezone, locale, matchField, matchFormats, targetField);
        }
    }
}
