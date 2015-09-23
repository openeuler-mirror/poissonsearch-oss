/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.common.util.set;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;

public final class Sets {
    private Sets() {
    }

    public static <T> HashSet<T> newHashSet(Iterator<T> iterator) {
        Objects.requireNonNull(iterator);
        HashSet<T> set = new HashSet<>();
        while (iterator.hasNext()) {
            set.add(iterator.next());
        }
        return set;
    }

    public static <T> HashSet<T> newHashSet(Iterable<T> iterable) {
        Objects.requireNonNull(iterable);
        return iterable instanceof Collection ? new HashSet<>((Collection)iterable) : newHashSet(iterable.iterator());
    }

    public static <T> HashSet<T> newHashSet(T... elements) {
        Objects.requireNonNull(elements);
        HashSet<T> set = new HashSet<>(elements.length);
        Collections.addAll(set, elements);
        return set;
    }

    /**
     * Create a new HashSet copying the original set with elements added. Useful
     * for initializing constants without static blocks.
     */
    public static <T> HashSet<T> newHashSetCopyWith(Set<T> original, T... elements) {
        Objects.requireNonNull(original);
        Objects.requireNonNull(elements);
        HashSet<T> set = new HashSet<>(original.size() + elements.length);
        set.addAll(original);
        Collections.addAll(set, elements);
        return set;
    }

    /**
     * Collects {@code Stream<Collection<T>>} into {@code Set<T>}.
     */
    @SuppressWarnings("unchecked")
    public static <T> Collector<Collection<T>, Set<T>, Set<T>> toFlatSet() {
        return (Collector<Collection<T>, Set<T>, Set<T>>) (Object) ToFlatSetCollector.INSTANCE;
    }

    public static <T> Set<T> newConcurrentHashSet() {
        return Collections.newSetFromMap(new ConcurrentHashMap<>());
    }

    public static <T> boolean haveEmptyIntersection(Set<T> left, Set<T> right) {
        Objects.requireNonNull(left);
        Objects.requireNonNull(right);
        return !left.stream().anyMatch(k -> right.contains(k));
    }

    public static <T> Set<T> difference(Set<T> left, Set<T> right) {
        Objects.requireNonNull(left);
        Objects.requireNonNull(right);
        return left.stream().filter(k -> !right.contains(k)).collect(Collectors.toSet());
    }

    public static <T> Set<T> union(Set<T> left, Set<T> right) {
        Objects.requireNonNull(left);
        Objects.requireNonNull(right);
        Set<T> union = new HashSet<>(left);
        union.addAll(right);
        return union;
    }

    /**
     * Collects {@code Stream<Collection<T>>} into {@code Set<T>}.
     */
    private static enum ToFlatSetCollector implements Collector<Collection<Object>, Set<Object>, Set<Object>> {
        INSTANCE;

        @Override
        public Supplier<Set<Object>> supplier() {
            return HashSet::new;
        }

        @Override
        public BiConsumer<Set<Object>, Collection<Object>> accumulator() {
            return Collection::addAll;
        }

        @Override
        public BinaryOperator<Set<Object>> combiner() {
            return (lhs, rhs) -> {
                lhs.addAll(rhs);
                return lhs;
            };
        }

        @Override
        public Function<Set<Object>, Set<Object>> finisher() {
            return Function.identity();
        }

        @Override
        public Set<java.util.stream.Collector.Characteristics> characteristics() {
            return EnumSet.of(Collector.Characteristics.IDENTITY_FINISH, Collector.Characteristics.UNORDERED);
        }
    }
}
