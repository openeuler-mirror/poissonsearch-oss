/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.analysis.catalog;

import org.elasticsearch.common.Nullable;

import java.util.Objects;
import java.util.function.Function;

/**
 * Index representation that is compatible with SQL ({@link EsIndex}).
 */
public final class Catalog {

    public static final Catalog EMPTY = new Catalog(GetIndexResult::notFound);
            
    private final Function<String, GetIndexResult> resultFunction;

    //TODO given that this always holds a single index, we cana probably get rid of the whole idea of Catalog
    public Catalog(GetIndexResult result) {
        assert result != null;
        this.resultFunction = index -> result.matches(index) ? result : GetIndexResult.notFound(index);
    }

    private Catalog(Function<String, GetIndexResult> resultFunction) {
        assert resultFunction != null;
        this.resultFunction = resultFunction;
    }

    /**
     * Lookup the information for a table.
     */
    public GetIndexResult getIndex(String index) {
        return resultFunction.apply(index);
    }

    public static final class GetIndexResult {
        public static GetIndexResult valid(EsIndex index) {
            Objects.requireNonNull(index, "index must not be null if it was found");
            return new GetIndexResult(index, null);
        }
        public static GetIndexResult invalid(String invalid) {
            Objects.requireNonNull(invalid, "invalid must not be null to signal that the index is invalid");
            return new GetIndexResult(null, invalid);
        }
        public static GetIndexResult notFound(String name) {
            Objects.requireNonNull(name, "name must not be null");
            return invalid("Index '" + name + "' does not exist");
        }

        private final EsIndex index;
        @Nullable
        private final String invalid;

        private GetIndexResult(EsIndex index, @Nullable String invalid) {
            this.index = index;
            this.invalid = invalid;
        }

        private boolean matches(String index) {
            return isValid() && this.index.name().equals(index);
        }

        /**
         * Get the {@linkplain EsIndex} built by the {@linkplain Catalog}.
         * @throws MappingException if the index is invalid for
         *      use with sql
         */
        public EsIndex get() {
            if (invalid != null) {
                throw new MappingException(invalid);
            }
            return index;
        }

        /**
         * Is the index valid for use with sql? Returns {@code false} if the
         * index wasn't found.
         */
        public boolean isValid() {
            return invalid == null;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null || obj.getClass() != getClass()) {
                return false;
            }
            GetIndexResult other = (GetIndexResult) obj;
            return Objects.equals(index, other.index)
                    && Objects.equals(invalid, other.invalid);
        }

        @Override
        public int hashCode() {
            return Objects.hash(index, invalid);
        }

        @Override
        public String toString() {
            return invalid != null ? invalid : index.name();
        }
    }
}