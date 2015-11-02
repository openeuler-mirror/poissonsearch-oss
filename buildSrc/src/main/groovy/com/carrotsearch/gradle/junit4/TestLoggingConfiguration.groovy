package com.carrotsearch.gradle.junit4

import org.gradle.util.ConfigureUtil

class TestLoggingConfiguration {
    /** Display mode for output streams. */
    static enum OutputMode {
        /** Always display the output emitted from tests. */
        ALWAYS,
        /**
         * Display the output only if a test/ suite failed. This requires internal buffering
         * so the output will be shown only after a test completes.
         */
        ONERROR,
        /** Don't display the output, even on test failures. */
        NEVER
    }

    OutputMode outputMode = OutputMode.ONERROR
    SlowTestsConfiguration slowTests = new SlowTestsConfiguration()
    StackTraceFiltersConfiguration stackTraceFilters = new StackTraceFiltersConfiguration()

    void slowTests(Closure closure) {
        ConfigureUtil.configure(closure, slowTests)
    }

    void stackTraceFilters(Closure closure) {
        ConfigureUtil.configure(closure, stackTraceFilters)
    }

    void outputMode(String mode) {
        outputMode = mode.toUpperCase() as OutputMode
    }
}
