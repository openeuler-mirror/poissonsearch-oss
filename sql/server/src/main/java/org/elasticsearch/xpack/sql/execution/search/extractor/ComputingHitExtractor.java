/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.execution.search.extractor;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.xpack.sql.expression.function.scalar.processor.runtime.HitExtractorProcessor;
import org.elasticsearch.xpack.sql.expression.function.scalar.processor.runtime.Processor;

import java.io.IOException;
import java.util.Objects;

/**
 * HitExtractor that delegates to a processor. The difference between this class
 * and {@link HitExtractorProcessor} is that the latter is used inside a
 * {@link Processor} tree as a leaf (and thus can effectively parse the
 * {@link SearchHit} while this class is used when scrolling and passing down
 * the results.
 *
 * In the future, the processor might be used across the board for all columns
 * to reduce API complexity (and keep the {@link HitExtractor} only as an
 * internal implementation detail).
 */
public class ComputingHitExtractor implements HitExtractor {
    /**
     * Stands for {@code comPuting}. We try to use short names for {@link HitExtractor}s
     * to save a few bytes when when we send them back to the user.
     */
    static final String NAME = "p";
    private final Processor processor;
    private final String hitName;

    public ComputingHitExtractor(Processor processor, String hitName) {
        this.processor = processor;
        this.hitName = hitName;
    }

    ComputingHitExtractor(StreamInput in) throws IOException {
        processor = in.readNamedWriteable(Processor.class);
        hitName = in.readOptionalString();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeNamedWriteable(processor);
        out.writeOptionalString(hitName);
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    public Processor processor() {
        return processor;
    }

    @Override
    public Object get(SearchHit hit) {
        return processor.process(hit);
    }

    @Override
    public String hitName() {
        return hitName;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || obj.getClass() != getClass()) {
            return false;
        }
        ComputingHitExtractor other = (ComputingHitExtractor) obj;
        return Objects.equals(processor, other.processor)
                && Objects.equals(hitName, other.hitName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(processor, hitName);
    }

    @Override
    public String toString() {
        return processor.toString();
    }
}