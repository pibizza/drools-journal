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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import org.kie.api.runtime.rule.FactHandle;

/**
 * Built-in {@link ObjectStorageStrategy} that serialises facts inline using
 * Java serialisation. Facts must implement {@link java.io.Serializable}.
 */
public final class EmbedStrategy implements ObjectStorageStrategy {

    @Override
    public Payload store(final Object fact, final FactHandle handle) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(fact);
            return new EmbeddedPayload(bos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize fact: " + fact.getClass().getName(), e);
        }
    }

    @Override
    public Object load(final Payload payload) {
        if (payload instanceof EmbeddedPayload embedded) {
            try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(embedded.bytes()))) {
                return ois.readObject();
            } catch (IOException | ClassNotFoundException e) {
                throw new RuntimeException("Failed to deserialize payload", e);
            }
        }
        throw new UnsupportedOperationException("EmbedStrategy cannot load payload type: " + payload.getClass().getName());
    }
}
