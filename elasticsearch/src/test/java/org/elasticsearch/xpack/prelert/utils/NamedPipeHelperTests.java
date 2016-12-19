/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

/**
 * Only negative test cases are covered, as positive tests would need to create named pipes,
 * and this is not possible in Java with the Elasticsearch security manager configuration.
 */
public class NamedPipeHelperTests extends ESTestCase {

    NamedPipeHelper NAMED_PIPE_HELPER = new NamedPipeHelper();

    public void testOpenForInputGivenPipeDoesNotExist() {
        Environment env = new Environment(
                Settings.builder().put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString()).build());
        IOException ioe = ESTestCase.expectThrows(FileNotFoundException.class,
                () -> NAMED_PIPE_HELPER.openNamedPipeInputStream(
                NAMED_PIPE_HELPER.getDefaultPipeDirectoryPrefix(env) + "this pipe does not exist",
                Duration.ofSeconds(1)));

        assertTrue(ioe.getMessage(),
                ioe.getMessage().contains("pipe does not exist") ||
                ioe.getMessage().contains("The system cannot find the file specified"));
    }

    public void testOpenForOutputGivenPipeDoesNotExist() {
        Environment env = new Environment(
                Settings.builder().put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString()).build());
        IOException ioe = ESTestCase.expectThrows(FileNotFoundException.class,
                () -> NAMED_PIPE_HELPER.openNamedPipeOutputStream(
                NAMED_PIPE_HELPER.getDefaultPipeDirectoryPrefix(env) + "this pipe does not exist",
                Duration.ofSeconds(1)));

        assertTrue(ioe.getMessage(), ioe.getMessage().contains("No such file or directory") ||
                ioe.getMessage().contains("The system cannot find the file specified"));
    }

    public void testOpenForInputGivenPipeIsRegularFile() throws IOException {
        Environment env = new Environment(
                Settings.builder().put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString()).build());
        Path tempFile = Files.createTempFile(env.tmpFile(), "not a named pipe", null);

        IOException ioe = ESTestCase.expectThrows(IOException.class, () ->
                NAMED_PIPE_HELPER.openNamedPipeInputStream(tempFile, Duration.ofSeconds(1)));

        assertTrue(ioe.getMessage(), ioe.getMessage().contains("is not a named pipe"));

        assertTrue(Files.deleteIfExists(tempFile));
    }

    public void testOpenForOutputGivenPipeIsRegularFile() throws IOException {
        Environment env = new Environment(
                Settings.builder().put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString()).build());
        Path tempFile = Files.createTempFile(env.tmpFile(), "not a named pipe", null);

        IOException ioe = ESTestCase.expectThrows(IOException.class, () ->
                NAMED_PIPE_HELPER.openNamedPipeOutputStream(tempFile, Duration.ofSeconds(1)));

        assertTrue(ioe.getMessage(), ioe.getMessage().contains("is not a named pipe") ||
                ioe.getMessage().contains("The system cannot find the file specified"));

        assertTrue(Files.deleteIfExists(tempFile));
    }
}
