/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.monitoring;

import org.elasticsearch.test.ESTestCase;

import java.util.Locale;

import static org.hamcrest.Matchers.containsString;

/**
 * Tests {@link MonitoredSystem}.
 */
public class MonitoredSystemTests extends ESTestCase {

    public void testGetSystem() {
        // everything is just lowercased...
        for (final MonitoredSystem system : MonitoredSystem.values()) {
            assertEquals(system.name().toLowerCase(Locale.ROOT), system.getSystem());
        }
    }

    public void testFromSystem() {
        for (final MonitoredSystem system : MonitoredSystem.values()) {
            final String lowercased = system.name().toLowerCase(Locale.ROOT);

            assertSame(system, MonitoredSystem.fromSystem(system.name()));
            assertSame(system, MonitoredSystem.fromSystem(lowercased));
        }
    }

    public void testFromUnknownSystem() {
        final String unknownSystem = randomAsciiOfLengthBetween(3, 4);

        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> {
            MonitoredSystem.fromSystem(unknownSystem);
        });

        assertThat(e.getMessage(), containsString(unknownSystem));
    }

}
