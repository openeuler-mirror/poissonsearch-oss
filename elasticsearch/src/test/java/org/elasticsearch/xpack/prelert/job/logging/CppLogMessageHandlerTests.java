/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
    public void testParse() throws IOException, TimeoutException {

        String testData = "{\"logger\":\"controller\",\"timestamp\":1478261151445,\"level\":\"INFO\",\"pid\":10211,"
                + "\"thread\":\"0x7fff7d2a8000\",\"message\":\"uname -a : Darwin Davids-MacBook-Pro.local 15.6.0 Darwin Kernel "
                + "Version 15.6.0: Thu Sep  1 15:01:16 PDT 2016; root:xnu-3248.60.11~2/RELEASE_X86_64 x86_64\",\"class\":\"prelert\","
                + "\"method\":\"core::CLogger::reconfigureFromProps\",\"file\":\"CLogger.cc\",\"line\":452}\n"
                + "{\"logger\":\"controller\",\"timestamp\":1478261151445,\"level\":\"DEBUG\",\"pid\":10211,\"thread\":\"0x7fff7d2a8000\","
                + "\"message\":\"Logger is logging to named pipe "
                + "/var/folders/k5/5sqcdlps5sg3cvlp783gcz740000h0/T/controller_log_784\",\"class\":\"prelert\","
                + "\"method\":\"core::CLogger::reconfigureLogToNamedPipe\",\"file\":\"CLogger.cc\",\"line\":333}\n"
                + "{\"logger\":\"controller\",\"timestamp\":1478261151445,\"level\":\"INFO\",\"pid\":10211,\"thread\":\"0x7fff7d2a8000\","
                + "\"message\":\"controller (64 bit): Version based on 6.5.0 (Build DEVELOPMENT BUILD by dave) "
                + "Copyright (c) Prelert Ltd 2006-2016\",\"method\":\"main\",\"file\":\"Main.cc\",\"line\":123}\n"
                + "{\"logger\":\"controller\",\"timestamp\":1478261169065,\"level\":\"ERROR\",\"pid\":10211,\"thread\":\"0x7fff7d2a8000\","
                + "\"message\":\"Did not understand verb 'a'\",\"class\":\"prelert\","
                + "\"method\":\"controller::CCommandProcessor::handleCommand\",\"file\":\"CCommandProcessor.cc\",\"line\":100}\n"
                + "{\"logger\":\"controller\",\"timestamp\":1478261169065,\"level\":\"DEBUG\",\"pid\":10211,\"thread\":\"0x7fff7d2a8000\","
                + "\"message\":\"Prelert controller exiting\",\"method\":\"main\",\"file\":\"Main.cc\",\"line\":147}\n";

        // Try different buffer sizes to smoke out edge case problems in the buffer management
        for (int readBufSize : new int[] { 11, 42, 101, 1024, 9999 }) {
            InputStream is = new ByteArrayInputStream(testData.getBytes(StandardCharsets.UTF_8));
            try (CppLogMessageHandler handler = new CppLogMessageHandler(is, "_id", readBufSize, 3)) {
                handler.tailStream();

                assertTrue(handler.hasLogStreamEnded());
                assertEquals(10211L, handler.getPid(Duration.ofMillis(1)));
                assertEquals("Did not understand verb 'a'\n", handler.getErrors());
                assertFalse(handler.seenFatalError());
            }
        }
    }
}
