/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.execution.search;

import org.elasticsearch.Version;
import org.elasticsearch.common.io.FastStringReader;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.Writeable.Reader;
import org.elasticsearch.test.AbstractWireSerializingTestCase;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class ScrollCursorTests extends AbstractWireSerializingTestCase<ScrollCursor> {
    public static ScrollCursor randomScrollCursor() {
        int extractorsSize = between(1, 20);
        List<HitExtractor> extractors = new ArrayList<>(extractorsSize);
        for (int i = 0; i < extractorsSize; i++) {
            extractors.add(randomHitExtractor(0));
        }
        return new ScrollCursor(randomAlphaOfLength(5), extractors);
    }

    static HitExtractor randomHitExtractor(int depth) {
        List<Supplier<HitExtractor>> options = new ArrayList<>();
        if (depth < 5) {
            options.add(() -> ProcessingHitExtractorTests.randomProcessingHitExtractor(depth));
        }
        options.add(ConstantExtractorTests::randomConstantExtractor);
        options.add(DocValueExtractorTests::randomDocValueExtractor);
        options.add(InnerHitExtractorTests::randomInnerHitExtractor);
        options.add(SourceExtractorTests::randomSourceExtractor);
        return randomFrom(options).get();
    }

    @Override
    protected NamedWriteableRegistry getNamedWriteableRegistry() {
        return new NamedWriteableRegistry(HitExtractor.getNamedWriteables());
    }

    @Override
    protected ScrollCursor createTestInstance() {
        return randomScrollCursor();
    }

    @Override
    protected Reader<ScrollCursor> instanceReader() {
        return ScrollCursor::new;
    }

    @Override
    protected ScrollCursor copyInstance(ScrollCursor instance, Version version) throws IOException {
        /* Randomly chose between internal protocol round trip and String based
         * round trips used to toXContent. */
        if (randomBoolean()) {
            return super.copyInstance(instance, version);
        }
        // See comment in NextPageInfo#decodeFromString about versioning
        assertEquals(Version.CURRENT, version);
        try (StringWriter output = new StringWriter()) { 
            instance.writeTo(output);
            try (java.io.Reader in = new FastStringReader(output.toString())) {
                return new ScrollCursor(in);
            }
        }
    }
}
