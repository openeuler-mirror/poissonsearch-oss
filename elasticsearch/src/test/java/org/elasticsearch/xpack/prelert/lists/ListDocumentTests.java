/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
    @Override
    protected ListDocument createTestInstance() {
        int size = randomInt(10);
        List<String> items = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            items.add(randomAsciiOfLengthBetween(1, 20));
        }
        return new ListDocument(randomAsciiOfLengthBetween(1, 20), items);
    }

    @Override
    protected Reader<ListDocument> instanceReader() {
        return ListDocument::new;
    }

    @Override
    protected ListDocument parseInstance(XContentParser parser, ParseFieldMatcher matcher) {
        return ListDocument.PARSER.apply(parser, () -> matcher);
    }

    public void testNullId() {
        NullPointerException ex = expectThrows(NullPointerException.class, () -> new ListDocument(null, Collections.emptyList()));
        assertEquals(ListDocument.ID.getPreferredName() + " must not be null", ex.getMessage());
    }

    public void testNullItems() {
        NullPointerException ex = expectThrows(NullPointerException.class, () -> new ListDocument(randomAsciiOfLengthBetween(1, 20), null));
        assertEquals(ListDocument.ITEMS.getPreferredName() + " must not be null", ex.getMessage());
    }

}
