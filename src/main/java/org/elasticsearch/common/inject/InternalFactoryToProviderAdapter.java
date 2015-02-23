/**
 * Copyright (C) 2006 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.elasticsearch.common.inject;

import org.elasticsearch.common.inject.internal.*;
import org.elasticsearch.common.inject.spi.Dependency;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @author crazybob@google.com (Bob Lee)
 */
class InternalFactoryToProviderAdapter<T> implements InternalFactory<T> {

    private final Initializable<Provider<? extends T>> initializable;
    private final Object source;

    public InternalFactoryToProviderAdapter(Initializable<Provider<? extends T>> initializable) {
        this(initializable, SourceProvider.UNKNOWN_SOURCE);
    }

    public InternalFactoryToProviderAdapter(
            Initializable<Provider<? extends T>> initializable, Object source) {
        this.initializable = checkNotNull(initializable, "provider");
        this.source = checkNotNull(source, "source");
    }

    @Override
    public T get(Errors errors, InternalContext context, Dependency<?> dependency)
            throws ErrorsException {
        try {
            return errors.checkForNull(initializable.get(errors).get(), source, dependency);
        } catch (RuntimeException userException) {
            throw errors.withSource(source).errorInProvider(userException).toException();
        }
    }

    @Override
    public String toString() {
        return initializable.toString();
    }
}
