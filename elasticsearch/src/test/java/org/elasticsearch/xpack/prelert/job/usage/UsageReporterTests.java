/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
        UsagePersister persister = Mockito.mock(UsagePersister.class);
        UsageReporter usage = new UsageReporter(settings, "job1", persister);

        usage.addBytesRead(10);
        usage.addFieldsRecordsRead(5);

        assertEquals(10, usage.getBytesReadSinceLastReport());
        assertEquals(5, usage.getFieldsReadSinceLastReport());
        assertEquals(1, usage.getRecordsReadSinceLastReport());

        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            assertTrue(false);
        }

        usage.addBytesRead(50);
        Mockito.verify(persister, Mockito.times(1)).persistUsage("job1", 60L, 5L, 1L);

        assertEquals(0, usage.getBytesReadSinceLastReport());
        assertEquals(0, usage.getFieldsReadSinceLastReport());
        assertEquals(0, usage.getRecordsReadSinceLastReport());

        // Write another
        usage.addBytesRead(20);
        usage.addFieldsRecordsRead(10);

        assertEquals(20, usage.getBytesReadSinceLastReport());
        assertEquals(10, usage.getFieldsReadSinceLastReport());
        assertEquals(1, usage.getRecordsReadSinceLastReport());

        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            assertTrue(false);
        }

        usage.addBytesRead(10);
        Mockito.verify(persister, Mockito.times(1)).persistUsage("job1", 30L, 10L, 1L);

        assertEquals(0, usage.getBytesReadSinceLastReport());
        assertEquals(0, usage.getFieldsReadSinceLastReport());
        assertEquals(0, usage.getRecordsReadSinceLastReport());

    }
}
