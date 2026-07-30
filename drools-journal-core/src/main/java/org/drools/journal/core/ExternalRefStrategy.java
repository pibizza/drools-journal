/*
 * Copyright (c) 2026 Drools Journal Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.drools.journal.core;

import java.util.function.Function;

import org.drools.journal.api.ExternalRef;
import org.drools.journal.api.ObjectStorageStrategy;
import org.drools.journal.api.Payload;
import org.kie.api.runtime.rule.FactHandle;

/**
 * {@link ObjectStorageStrategy} that stores only a reference key; the fact
 * lives in an external store. The key is computed by a caller-supplied function.
 *
 * <p>This implementation intentionally leaves {@link #load} unimplemented —
 * callers that need external-store retrieval must subclass or provide their own
 * full implementation.
 */
public final class ExternalRefStrategy implements ObjectStorageStrategy {

    private final Function<Object, String> keySupplier;
    private final Function<ExternalRef, Object> loader;

    public ExternalRefStrategy(final Function<Object, String> keySupplier,
                               final Function<ExternalRef, Object> loader) {
        this.keySupplier = keySupplier;
        this.loader = loader;
    }

    @Override
    public Payload store(final Object fact, final FactHandle handle) {
        return new ExternalRef(fact.getClass().getName(), keySupplier.apply(fact));
    }

    @Override
    public Object load(final Payload payload) {
        ExternalRef ref = (ExternalRef) payload;
        Object fact = loader.apply(ref);
        if (fact == null) {
            throw new IllegalStateException(
                    "External store returned null for key '" + ref.dbKey()
                            + "' (type " + ref.typeName() + ")");
        }
        return fact;
    }
}
