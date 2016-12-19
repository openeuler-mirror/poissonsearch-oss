/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
public class DataCountsTests extends AbstractSerializingTestCase<DataCounts> {

    @Override
    protected DataCounts createTestInstance() {
        return new DataCounts(randomAsciiOfLength(10), randomIntBetween(1, 1_000_000),
                randomIntBetween(1, 1_000_000), randomIntBetween(1, 1_000_000), randomIntBetween(1, 1_000_000),
                randomIntBetween(1, 1_000_000), randomIntBetween(1, 1_000_000), randomIntBetween(1, 1_000_000),
                new DateTime(randomDateTimeZone()).toDate(), new DateTime(randomDateTimeZone()).toDate());
    }

    @Override
    protected Writeable.Reader<DataCounts> instanceReader() {
        return DataCounts::new;
    }

    @Override
    protected DataCounts parseInstance(XContentParser parser, ParseFieldMatcher matcher) {
        return DataCounts.PARSER.apply(parser, () -> matcher);
    }

    public void testCountsEquals_GivenEqualCounts() {
        DataCounts counts1 = createCounts(1, 2, 3, 4, 5, 6, 7, 8, 9);
        DataCounts counts2 = createCounts(1, 2, 3, 4, 5, 6, 7, 8, 9);

        assertTrue(counts1.equals(counts2));
        assertTrue(counts2.equals(counts1));
    }

    public void testCountsHashCode_GivenEqualCounts() {
        DataCounts counts1 = createCounts(1, 2, 3, 4, 5, 6, 7, 8, 9);
        DataCounts counts2 = createCounts(1, 2, 3, 4, 5, 6, 7, 8, 9);

        assertEquals(counts1.hashCode(), counts2.hashCode());
    }

    public void testCountsCopyConstructor() {
        DataCounts counts1 = createCounts(1, 2, 3, 4, 5, 6, 7, 8, 9);
        DataCounts counts2 = new DataCounts(counts1);

        assertEquals(counts1.hashCode(), counts2.hashCode());
    }

    public void testCountCreatedZero() throws Exception {
        DataCounts counts = new DataCounts(randomAsciiOfLength(16));
        assertAllFieldsEqualZero(counts);
    }

    public void testCountCopyCreatedFieldsNotZero() throws Exception {
        DataCounts counts1 = createCounts(1, 200, 400, 3, 4, 5, 6, 1479211200000L, 1479384000000L);
        assertAllFieldsGreaterThanZero(counts1);

        DataCounts counts2 = new DataCounts(counts1);
        assertAllFieldsGreaterThanZero(counts2);
    }

    public void testIncrements() {
        DataCounts counts = new DataCounts(randomAsciiOfLength(16));

        counts.incrementInputBytes(15);
        assertEquals(15, counts.getInputBytes());

        counts.incrementInvalidDateCount(20);
        assertEquals(20, counts.getInvalidDateCount());

        counts.incrementMissingFieldCount(25);
        assertEquals(25, counts.getMissingFieldCount());

        counts.incrementOutOfOrderTimeStampCount(30);
        assertEquals(30, counts.getOutOfOrderTimeStampCount());

        counts.incrementProcessedRecordCount(40);
        assertEquals(40, counts.getProcessedRecordCount());
    }

    public void testGetInputRecordCount() {
        DataCounts counts = new DataCounts(randomAsciiOfLength(16));
        counts.incrementProcessedRecordCount(5);
        assertEquals(5, counts.getInputRecordCount());

        counts.incrementOutOfOrderTimeStampCount(2);
        assertEquals(7, counts.getInputRecordCount());

        counts.incrementInvalidDateCount(1);
        assertEquals(8, counts.getInputRecordCount());
    }

    public void testCalcProcessedFieldCount() {
        DataCounts counts = new DataCounts(randomAsciiOfLength(16), 10L, 0L, 0L, 0L, 0L, 0L, 0L, new Date(), new Date());
        counts.calcProcessedFieldCount(3);

        assertEquals(30, counts.getProcessedFieldCount());

        counts = new DataCounts(randomAsciiOfLength(16), 10L, 0L, 0L, 0L, 0L, 5L, 0L, new Date(), new Date());
        counts.calcProcessedFieldCount(3);
        assertEquals(25, counts.getProcessedFieldCount());
    }

    public void testEquals() {
        DataCounts counts1 = new DataCounts(
                randomAsciiOfLength(16), 10L, 5000L, 2000L, 300L, 6L, 15L, 0L, new Date(), new Date(1435000000L));
        DataCounts counts2 = new DataCounts(counts1);

        assertEquals(counts1, counts2);
        counts2.incrementInputBytes(1);
        assertFalse(counts1.equals(counts2));
    }

    public void testSetEarliestRecordTimestamp_doesnotOverwrite() {
        DataCounts counts = new DataCounts(randomAsciiOfLength(12));
        counts.setEarliestRecordTimeStamp(new Date(100L));

        ESTestCase.expectThrows(IllegalStateException.class, () -> counts.setEarliestRecordTimeStamp(new Date(200L)));
        assertEquals(new Date(100L), counts.getEarliestRecordTimeStamp());
    }

    private void assertAllFieldsEqualZero(DataCounts stats) throws Exception {
        assertEquals(0L, stats.getProcessedRecordCount());
        assertEquals(0L, stats.getProcessedFieldCount());
        assertEquals(0L, stats.getInputBytes());
        assertEquals(0L, stats.getInputFieldCount());
        assertEquals(0L, stats.getInputRecordCount());
        assertEquals(0L, stats.getInvalidDateCount());
        assertEquals(0L, stats.getMissingFieldCount());
        assertEquals(0L, stats.getOutOfOrderTimeStampCount());
    }

    private void assertAllFieldsGreaterThanZero(DataCounts stats) throws Exception {
        assertThat(stats.getProcessedRecordCount(), greaterThan(0L));
        assertThat(stats.getProcessedFieldCount(), greaterThan(0L));
        assertThat(stats.getInputBytes(), greaterThan(0L));
        assertThat(stats.getInputFieldCount(), greaterThan(0L));
        assertThat(stats.getInputRecordCount(), greaterThan(0L));
        assertThat(stats.getInputRecordCount(), greaterThan(0L));
        assertThat(stats.getInvalidDateCount(), greaterThan(0L));
        assertThat(stats.getMissingFieldCount(), greaterThan(0L));
        assertThat(stats.getOutOfOrderTimeStampCount(), greaterThan(0L));
        assertThat(stats.getLatestRecordTimeStamp().getTime(), greaterThan(0L));
    }

    private static DataCounts createCounts(
            long processedRecordCount, long processedFieldCount, long inputBytes, long inputFieldCount,
            long invalidDateCount, long missingFieldCount, long outOfOrderTimeStampCount, long earliestRecordTime, long latestRecordTime) {

        DataCounts counts = new DataCounts("foo", processedRecordCount, processedFieldCount, inputBytes,
                inputFieldCount, invalidDateCount, missingFieldCount, outOfOrderTimeStampCount,
                new Date(earliestRecordTime), new Date(latestRecordTime));

        return counts;
    }

}
