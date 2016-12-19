/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.prelert.job.process.autodetect.AutodetectProcess;
import org.junit.Assert;
import org.junit.Before;
import org.mockito.Mockito;

import org.elasticsearch.xpack.prelert.job.AnalysisConfig;
import org.elasticsearch.xpack.prelert.job.DataDescription;
import org.elasticsearch.xpack.prelert.job.Detector;
import org.elasticsearch.xpack.prelert.job.condition.Condition;
import org.elasticsearch.xpack.prelert.job.condition.Operator;
import org.elasticsearch.xpack.prelert.job.process.autodetect.writer.AbstractDataToProcessWriter.InputOutputMap;
import org.elasticsearch.xpack.prelert.job.status.StatusReporter;
import org.elasticsearch.xpack.prelert.job.transform.TransformConfig;
import org.elasticsearch.xpack.prelert.job.transform.TransformConfigs;
import org.elasticsearch.xpack.prelert.job.transform.TransformType;
import org.elasticsearch.xpack.prelert.transforms.Concat;
import org.elasticsearch.xpack.prelert.transforms.HighestRegisteredDomain;
import org.elasticsearch.xpack.prelert.transforms.RegexSplit;
import org.elasticsearch.xpack.prelert.transforms.StringTransform;
import org.elasticsearch.xpack.prelert.transforms.Transform;
import org.elasticsearch.xpack.prelert.transforms.Transform.TransformIndex;

/**
 * Testing methods of AbstractDataToProcessWriter but uses the concrete
 * instances.
 * <p>
 * Asserts that the transforms have the right input and outputs.
 */
public class AbstractDataToProcessWriterTests extends ESTestCase {
    private AutodetectProcess autodetectProcess;
    private StatusReporter statusReporter;
    private Logger jobLogger;

    @Before
    public void setUpMocks() {
        autodetectProcess = Mockito.mock(AutodetectProcess.class);
        statusReporter = Mockito.mock(StatusReporter.class);
        jobLogger = Mockito.mock(Logger.class);
    }

    public void testInputFields_MulitpleInputsSingleOutput() throws IOException {
        DataDescription.Builder dd = new DataDescription.Builder();
        dd.setTimeField("time_field");

        Detector.Builder detector = new Detector.Builder("metric", "value");
        detector.setByFieldName("host-metric");
        detector.setDetectorDescription("metric(value) by host-metric");
        AnalysisConfig ac = new AnalysisConfig.Builder(Arrays.asList(detector.build())).build();

        TransformConfig tc = new TransformConfig(TransformType.Names.CONCAT_NAME);
        tc.setInputs(Arrays.asList("host", "metric"));
        tc.setOutputs(Arrays.asList("host-metric"));

        TransformConfigs transforms = new TransformConfigs(Arrays.asList(tc));

        AbstractDataToProcessWriter writer =
                new CsvDataToProcessWriter(true, autodetectProcess, dd.build(), ac, transforms, statusReporter, jobLogger);

        Set<String> inputFields = new HashSet<>(writer.inputFields());
        assertEquals(4, inputFields.size());
        assertTrue(inputFields.contains("time_field"));
        assertTrue(inputFields.contains("value"));
        assertTrue(inputFields.contains("host"));
        assertTrue(inputFields.contains("metric"));

        String[] header = { "time_field", "metric", "host", "value" };
        writer.buildTransformsAndWriteHeader(header);
        List<Transform> trs = writer.postDateTransforms;
        assertEquals(1, trs.size());
        Transform tr = trs.get(0);

        List<TransformIndex> readIndexes = tr.getReadIndexes();
        assertEquals(readIndexes.get(0), new TransformIndex(0, 2));
        assertEquals(readIndexes.get(1), new TransformIndex(0, 1));

        List<TransformIndex> writeIndexes = tr.getWriteIndexes();
        assertEquals(writeIndexes.get(0), new TransformIndex(2, 1));

        Map<String, Integer> inputIndexes = writer.getInputFieldIndexes();
        assertEquals(4, inputIndexes.size());
        Assert.assertEquals(new Integer(0), inputIndexes.get("time_field"));
        Assert.assertEquals(new Integer(1), inputIndexes.get("metric"));
        Assert.assertEquals(new Integer(2), inputIndexes.get("host"));
        Assert.assertEquals(new Integer(3), inputIndexes.get("value"));

        Map<String, Integer> outputIndexes = writer.getOutputFieldIndexes();
        assertEquals(4, outputIndexes.size());
        Assert.assertEquals(new Integer(0), outputIndexes.get("time_field"));
        Assert.assertEquals(new Integer(1), outputIndexes.get("host-metric"));
        Assert.assertEquals(new Integer(2), outputIndexes.get("value"));
        Assert.assertEquals(new Integer(3), outputIndexes.get(LengthEncodedWriter.CONTROL_FIELD_NAME));

        List<InputOutputMap> inOutMaps = writer.getInputOutputMap();
        assertEquals(1, inOutMaps.size());
        assertEquals(inOutMaps.get(0).inputIndex, 3);
        assertEquals(inOutMaps.get(0).outputIndex, 2);
    }

    public void testInputFields_SingleInputMulitpleOutputs() throws IOException {
        DataDescription.Builder dd = new DataDescription.Builder();
        dd.setTimeField("time_field");

        Detector.Builder detector = new Detector.Builder("metric", "value");
        detector.setByFieldName(TransformType.DOMAIN_SPLIT.defaultOutputNames().get(0));
        detector.setOverFieldName(TransformType.DOMAIN_SPLIT.defaultOutputNames().get(1));
        AnalysisConfig ac = new AnalysisConfig.Builder(Arrays.asList(detector.build())).build();

        TransformConfig tc = new TransformConfig(TransformType.Names.DOMAIN_SPLIT_NAME);
        tc.setInputs(Arrays.asList("domain"));

        TransformConfigs transforms = new TransformConfigs(Arrays.asList(tc));

        AbstractDataToProcessWriter writer =
                new CsvDataToProcessWriter(true, autodetectProcess, dd.build(), ac, transforms, statusReporter, jobLogger);

        Set<String> inputFields = new HashSet<>(writer.inputFields());

        assertEquals(3, inputFields.size());
        assertTrue(inputFields.contains("time_field"));
        assertTrue(inputFields.contains("value"));
        assertTrue(inputFields.contains("domain"));

        String[] header = { "time_field", "domain", "value" };
        writer.buildTransformsAndWriteHeader(header);
        List<Transform> trs = writer.postDateTransforms;
        assertEquals(1, trs.size());

        Map<String, Integer> inputIndexes = writer.getInputFieldIndexes();
        assertEquals(3, inputIndexes.size());
        Assert.assertEquals(new Integer(0), inputIndexes.get("time_field"));
        Assert.assertEquals(new Integer(1), inputIndexes.get("domain"));
        Assert.assertEquals(new Integer(2), inputIndexes.get("value"));

        Map<String, Integer> outputIndexes = writer.getOutputFieldIndexes();

        List<String> allOutputs = new ArrayList<>(TransformType.DOMAIN_SPLIT.defaultOutputNames());
        allOutputs.add("value");
        Collections.sort(allOutputs); // outputs are in alphabetical order

        assertEquals(5, outputIndexes.size()); // time + control field + outputs
        Assert.assertEquals(new Integer(0), outputIndexes.get("time_field"));

        int count = 1;
        for (String f : allOutputs) {
            Assert.assertEquals(new Integer(count++), outputIndexes.get(f));
        }
        Assert.assertEquals(new Integer(allOutputs.size() + 1), outputIndexes.get(LengthEncodedWriter.CONTROL_FIELD_NAME));

        List<InputOutputMap> inOutMaps = writer.getInputOutputMap();
        assertEquals(1, inOutMaps.size());
        assertEquals(inOutMaps.get(0).inputIndex, 2);
        assertEquals(inOutMaps.get(0).outputIndex, allOutputs.indexOf("value") + 1);

        Transform tr = trs.get(0);
        assertEquals(tr.getReadIndexes().get(0), new TransformIndex(0, 1));

        List<TransformIndex> writeIndexes = new ArrayList<>();
        int[] outIndexes = new int[TransformType.DOMAIN_SPLIT.defaultOutputNames().size()];
        for (int i = 0; i < outIndexes.length; i++) {
            writeIndexes.add(new TransformIndex(2, allOutputs.indexOf(TransformType.DOMAIN_SPLIT.defaultOutputNames().get(i)) + 1));
        }
        assertEquals(writeIndexes, tr.getWriteIndexes());
    }

    /**
     * Only one output of the transform is used
     */

    public void testInputFields_SingleInputMulitpleOutputs_OnlyOneOutputUsed() throws IOException {
        DataDescription.Builder dd = new DataDescription.Builder();
        dd.setTimeField("time_field");

        Detector.Builder detector = new Detector.Builder("metric", "value");
        detector.setByFieldName(TransformType.DOMAIN_SPLIT.defaultOutputNames().get(0));
        AnalysisConfig ac = new AnalysisConfig.Builder(Arrays.asList(detector.build())).build();

        TransformConfig tc = new TransformConfig(TransformType.Names.DOMAIN_SPLIT_NAME);
        tc.setInputs(Arrays.asList("domain"));

        TransformConfigs transforms = new TransformConfigs(Arrays.asList(tc));

        AbstractDataToProcessWriter writer =
                new CsvDataToProcessWriter(true, autodetectProcess, dd.build(), ac, transforms, statusReporter, jobLogger);

        Set<String> inputFields = new HashSet<>(writer.inputFields());

        assertEquals(3, inputFields.size());
        assertTrue(inputFields.contains("time_field"));
        assertTrue(inputFields.contains("value"));
        assertTrue(inputFields.contains("domain"));

        String[] header = { "time_field", "domain", "value" };
        writer.buildTransformsAndWriteHeader(header);
        List<Transform> trs = writer.postDateTransforms;
        assertEquals(1, trs.size());

        Map<String, Integer> inputIndexes = writer.getInputFieldIndexes();
        assertEquals(3, inputIndexes.size());
        Assert.assertEquals(new Integer(0), inputIndexes.get("time_field"));
        Assert.assertEquals(new Integer(1), inputIndexes.get("domain"));
        Assert.assertEquals(new Integer(2), inputIndexes.get("value"));

        Map<String, Integer> outputIndexes = writer.getOutputFieldIndexes();

        List<String> allOutputs = new ArrayList<>();
        allOutputs.add(TransformType.DOMAIN_SPLIT.defaultOutputNames().get(0));
        allOutputs.add("value");
        Collections.sort(allOutputs); // outputs are in alphabetical order

        assertEquals(4, outputIndexes.size()); // time + control field + outputs
        Assert.assertEquals(new Integer(0), outputIndexes.get("time_field"));

        int count = 1;
        for (String f : allOutputs) {
            Assert.assertEquals(new Integer(count++), outputIndexes.get(f));
        }
        Assert.assertEquals(new Integer(allOutputs.size() + 1), outputIndexes.get(LengthEncodedWriter.CONTROL_FIELD_NAME));

        List<InputOutputMap> inOutMaps = writer.getInputOutputMap();
        assertEquals(1, inOutMaps.size());
        assertEquals(inOutMaps.get(0).inputIndex, 2);
        assertEquals(inOutMaps.get(0).outputIndex, allOutputs.indexOf("value") + 1);

        Transform tr = trs.get(0);
        assertEquals(tr.getReadIndexes().get(0), new TransformIndex(0, 1));

        TransformIndex ti = new TransformIndex(2, allOutputs.indexOf(TransformType.DOMAIN_SPLIT.defaultOutputNames().get(0)) + 1);
        assertEquals(tr.getWriteIndexes().get(0), ti);
    }

    /**
     * Only one output of the transform is used
     */

    public void testBuildTransforms_ChainedTransforms() throws IOException {
        DataDescription.Builder dd = new DataDescription.Builder();
        dd.setTimeField("datetime");

        Detector.Builder detector = new Detector.Builder("metric", "value");
        detector.setByFieldName(TransformType.DOMAIN_SPLIT.defaultOutputNames().get(0));
        AnalysisConfig ac = new AnalysisConfig.Builder(Arrays.asList(detector.build())).build();

        TransformConfig concatTc = new TransformConfig(TransformType.Names.CONCAT_NAME);
        concatTc.setInputs(Arrays.asList("date", "time"));
        concatTc.setOutputs(Arrays.asList("datetime"));

        TransformConfig hrdTc = new TransformConfig(TransformType.Names.DOMAIN_SPLIT_NAME);
        hrdTc.setInputs(Arrays.asList("domain"));

        TransformConfigs transforms = new TransformConfigs(Arrays.asList(concatTc, hrdTc));

        AbstractDataToProcessWriter writer =
                new CsvDataToProcessWriter(true, autodetectProcess, dd.build(), ac, transforms, statusReporter, jobLogger);

        Set<String> inputFields = new HashSet<>(writer.inputFields());

        assertEquals(4, inputFields.size());
        assertTrue(inputFields.contains("date"));
        assertTrue(inputFields.contains("time"));
        assertTrue(inputFields.contains("value"));
        assertTrue(inputFields.contains("domain"));

        String[] header = { "date", "time", "domain", "value" };

        writer.buildTransformsAndWriteHeader(header);
        List<Transform> trs = writer.dateInputTransforms;
        assertEquals(1, trs.size());
        assertTrue(trs.get(0) instanceof Concat);

        trs = writer.postDateTransforms;
        assertEquals(1, trs.size());
        assertTrue(trs.get(0) instanceof HighestRegisteredDomain);

        Map<String, Integer> inputIndexes = writer.getInputFieldIndexes();
        assertEquals(4, inputIndexes.size());
        Assert.assertEquals(new Integer(0), inputIndexes.get("date"));
        Assert.assertEquals(new Integer(1), inputIndexes.get("time"));
        Assert.assertEquals(new Integer(2), inputIndexes.get("domain"));
        Assert.assertEquals(new Integer(3), inputIndexes.get("value"));
    }

    /**
     * The exclude transform returns fail fatal meaning the record shouldn't be
     * processed.
     */

    public void testApplyTransforms_transformReturnsExclude()
            throws IOException {
        DataDescription.Builder dd = new DataDescription.Builder();
        dd.setTimeField("datetime");

        Detector.Builder detector = new Detector.Builder("metric", "value");
        detector.setByFieldName("metric");
        AnalysisConfig ac = new AnalysisConfig.Builder(Arrays.asList(detector.build())).build();

        TransformConfig excludeConfig = new TransformConfig(TransformType.EXCLUDE.prettyName());
        excludeConfig.setInputs(Arrays.asList("metric"));
        excludeConfig.setCondition(new Condition(Operator.MATCH, "metricA"));

        TransformConfigs transforms = new TransformConfigs(Arrays.asList(excludeConfig));

        AbstractDataToProcessWriter writer =
                new CsvDataToProcessWriter(true, autodetectProcess, dd.build(), ac, transforms, statusReporter, jobLogger);

        String[] header = { "datetime", "metric", "value" };

        writer.buildTransformsAndWriteHeader(header);

        // metricA is excluded
        String[] input = { "1", "metricA", "0" };
        String[] output = new String[3];

        assertFalse(writer.applyTransformsAndWrite(() -> false, input, output, 3));

        verify(autodetectProcess, never()).writeRecord(output);
        verify(statusReporter, never()).reportRecordWritten(anyLong(), anyLong());

        // reset the call counts etc.
        Mockito.reset(statusReporter);

        // this is ok
        input = new String[] { "2", "metricB", "0" };
        String[] expectedOutput = { "2", null, null };
        assertTrue(writer.applyTransformsAndWrite(() -> false, input, output, 3));

        verify(autodetectProcess, times(1)).writeRecord(expectedOutput);
        verify(statusReporter, times(1)).reportRecordWritten(3, 2000);
    }

    public void testBuildTransforms_DateTransformsAreSorted() throws IOException {
        DataDescription.Builder dd = new DataDescription.Builder();
        dd.setTimeField("datetime");

        Detector.Builder detector = new Detector.Builder("metric", "value");
        detector.setByFieldName("type");
        AnalysisConfig ac = new AnalysisConfig.Builder(Arrays.asList(detector.build())).build();

        TransformConfig concatTc = new TransformConfig(TransformType.Names.CONCAT_NAME);
        concatTc.setInputs(Arrays.asList("DATE", "time"));
        concatTc.setOutputs(Arrays.asList("datetime"));

        TransformConfig upperTc = new TransformConfig(TransformType.Names.UPPERCASE_NAME);
        upperTc.setInputs(Arrays.asList("date"));
        upperTc.setOutputs(Arrays.asList("DATE"));

        TransformConfig splitTc = new TransformConfig(TransformType.Names.SPLIT_NAME);
        splitTc.setInputs(Arrays.asList("date-somethingelse"));
        splitTc.setOutputs(Arrays.asList("date"));
        splitTc.setArguments(Arrays.asList("-"));

        TransformConfigs transforms = new TransformConfigs(Arrays.asList(upperTc, concatTc, splitTc));

        AbstractDataToProcessWriter writer =
                new CsvDataToProcessWriter(true, autodetectProcess, dd.build(), ac, transforms, statusReporter, jobLogger);

        String[] header = { "date-somethingelse", "time", "type", "value" };

        writer.buildTransformsAndWriteHeader(header);

        // the date input transforms should be in this order
        List<Transform> trs = writer.dateInputTransforms;
        assertEquals(3, trs.size());
        assertTrue(trs.get(0) instanceof RegexSplit);
        assertTrue(trs.get(1) instanceof StringTransform);
        assertTrue(trs.get(2) instanceof Concat);
    }
}
