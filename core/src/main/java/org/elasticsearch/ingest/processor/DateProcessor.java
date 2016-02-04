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

package org.elasticsearch.ingest.processor;

import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.ingest.core.AbstractProcessor;
import org.elasticsearch.ingest.core.AbstractProcessorFactory;
import org.elasticsearch.ingest.core.ConfigurationUtils;
import org.elasticsearch.ingest.core.IngestDocument;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.ISODateTimeFormat;

import java.util.ArrayList;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

public final class DateProcessor extends AbstractProcessor {

    public static final String TYPE = "date";
    static final String DEFAULT_TARGET_FIELD = "@timestamp";

    private final DateTimeZone timezone;
    private final Locale locale;
    private final String matchField;
    private final String targetField;
    private final List<String> matchFormats;
    private final List<Function<String, DateTime>> dateParsers;

    DateProcessor(String tag, DateTimeZone timezone, Locale locale, String matchField, List<String> matchFormats, String targetField) {
        super(tag);
        this.timezone = timezone;
        this.locale = locale;
        this.matchField = matchField;
        this.targetField = targetField;
        this.matchFormats = matchFormats;
        this.dateParsers = new ArrayList<>();
        for (String matchFormat : matchFormats) {
            DateFormat dateFormat = DateFormat.fromString(matchFormat);
            dateParsers.add(dateFormat.getFunction(matchFormat, timezone, locale));
        }
    }

    @Override
    public void execute(IngestDocument ingestDocument) {
        String value = ingestDocument.getFieldValue(matchField, String.class);

        DateTime dateTime = null;
        Exception lastException = null;
        for (Function<String, DateTime> dateParser : dateParsers) {
            try {
                dateTime = dateParser.apply(value);
            } catch (Exception e) {
                //try the next parser and keep track of the exceptions
                lastException = ExceptionsHelper.useOrSuppress(lastException, e);
            }
        }

        if (dateTime == null) {
            throw new IllegalArgumentException("unable to parse date [" + value + "]", lastException);
        }

        ingestDocument.setFieldValue(targetField, ISODateTimeFormat.dateTime().print(dateTime));
    }

    @Override
    public String getType() {
        return TYPE;
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

    public static final class Factory extends AbstractProcessorFactory<DateProcessor> {

        @SuppressWarnings("unchecked")
        public DateProcessor doCreate(String processorTag, Map<String, Object> config) throws Exception {
            String matchField = ConfigurationUtils.readStringProperty(TYPE, processorTag, config, "match_field");
            String targetField = ConfigurationUtils.readStringProperty(TYPE, processorTag, config, "target_field", DEFAULT_TARGET_FIELD);
            String timezoneString = ConfigurationUtils.readOptionalStringProperty(TYPE, processorTag, config, "timezone");
            DateTimeZone timezone = timezoneString == null ? DateTimeZone.UTC : DateTimeZone.forID(timezoneString);
            String localeString = ConfigurationUtils.readOptionalStringProperty(TYPE, processorTag, config, "locale");
            Locale locale = Locale.ENGLISH;
            if (localeString != null) {
                try {
                    locale = (new Locale.Builder()).setLanguageTag(localeString).build();
                } catch (IllformedLocaleException e) {
                    throw new IllegalArgumentException("Invalid language tag specified: " + localeString);
                }
            }
            List<String> matchFormats = ConfigurationUtils.readList(TYPE, processorTag, config, "match_formats");
            return new DateProcessor(processorTag, timezone, locale, matchField, matchFormats, targetField);
        }
    }
}
