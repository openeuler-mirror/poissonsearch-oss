/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
        AtomicBoolean bool = new AtomicBoolean();
        new Thread(() -> {
            boolean result = listener.waitForFlush("_id", 10000);
            bool.set(result);
        }).start();
        assertBusy(() -> assertTrue(listener.awaitingFlushed.containsKey("_id")));
        assertFalse(bool.get());
        listener.acknowledgeFlush("_id");
        assertBusy(() -> assertTrue(bool.get()));
        assertEquals(0, listener.awaitingFlushed.size());
    }

    public void testClear() throws Exception {
        FlushListener listener = new FlushListener();

        int numWaits = 9;
        List<AtomicBoolean> bools = new ArrayList<>(numWaits);
        for (int i = 0; i < numWaits; i++) {
            int id = i;
            AtomicBoolean bool = new AtomicBoolean();
            bools.add(bool);
            new Thread(() -> {
                boolean result = listener.waitForFlush(String.valueOf(id), 10000);
                bool.set(result);
            }).start();
        }
        assertBusy(() -> assertEquals(numWaits, listener.awaitingFlushed.size()));
        for (AtomicBoolean bool : bools) {
            assertFalse(bool.get());
        }
        assertFalse(listener.cleared.get());
        listener.clear();
        for (AtomicBoolean bool : bools) {
            assertBusy(() -> assertTrue(bool.get()));
        }
        assertTrue(listener.awaitingFlushed.isEmpty());
        assertTrue(listener.cleared.get());
    }

}
