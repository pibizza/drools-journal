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
package org.drools.journal.api;

import org.kie.api.runtime.rule.FactHandle;

/**
 * SPI for storing and loading individual facts in the journal.
 *
 * <p>Implementations own both directions: {@link #store} serialises a fact into
 * a {@link Payload} on the write path; {@link #load} reconstructs the fact from
 * that payload on the restore path.
 */
public interface ObjectStorageStrategy {

    /**
     * Serialises {@code fact} into a journal payload.
     *
     * @param fact   the fact object being inserted or updated
     * @param handle the FactHandle assigned to this fact in the session
     * @return an {@link EmbeddedPayload} with inline bytes, or an {@link ExternalRef}
     */
    Payload store(Object fact, FactHandle handle);

    /**
     * Reconstructs a fact from its journal payload.
     *
     * @param payload the payload produced by a prior {@link #store} call
     * @return the original fact object
     */
    Object load(Payload payload);
}
