/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
import static org.mockito.Mockito.verifyNoMoreInteractions;

public class AnalysisLimitsWriterTests extends ESTestCase {
    private OutputStreamWriter writer;

    @Before
    public void setUpMocks() {
        writer = Mockito.mock(OutputStreamWriter.class);
    }

    @After
    public void verifyNoMoreWriterInteractions() {
        verifyNoMoreInteractions(writer);
    }

    public void testWrite_GivenUnsetValues() throws IOException {
        AnalysisLimits limits = new AnalysisLimits(null, null);
        AnalysisLimitsWriter analysisLimitsWriter = new AnalysisLimitsWriter(limits, writer);

        analysisLimitsWriter.write();

        verify(writer).write("[memory]\n[results]\n");
    }

    public void testWrite_GivenModelMemoryLimitIsZero() throws IOException {
        AnalysisLimits limits = new AnalysisLimits(0L, null);
        AnalysisLimitsWriter analysisLimitsWriter = new AnalysisLimitsWriter(limits, writer);

        analysisLimitsWriter.write();

        verify(writer).write("[memory]\n[results]\n");
    }

    public void testWrite_GivenModelMemoryLimitWasSet() throws IOException {
        AnalysisLimits limits = new AnalysisLimits(10L, null);
        AnalysisLimitsWriter analysisLimitsWriter = new AnalysisLimitsWriter(limits, writer);

        analysisLimitsWriter.write();

        verify(writer).write("[memory]\nmodelmemorylimit = 10\n[results]\n");
    }

    public void testWrite_GivenCategorizationExamplesLimitWasSet() throws IOException {
        AnalysisLimits limits = new AnalysisLimits(0L, 5L);
        AnalysisLimitsWriter analysisLimitsWriter = new AnalysisLimitsWriter(limits, writer);

        analysisLimitsWriter.write();

        verify(writer).write("[memory]\n[results]\nmaxexamples = 5\n");
    }

    public void testWrite_GivenAllFieldsSet() throws IOException {
        AnalysisLimits limits = new AnalysisLimits(1024L, 3L);
        AnalysisLimitsWriter analysisLimitsWriter = new AnalysisLimitsWriter(limits, writer);

        analysisLimitsWriter.write();

        verify(writer).write(
                "[memory]\nmodelmemorylimit = 1024\n[results]\nmaxexamples = 3\n");
    }
}
