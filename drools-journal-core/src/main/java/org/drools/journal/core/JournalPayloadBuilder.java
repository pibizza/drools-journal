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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import org.drools.core.common.InternalFactHandle;
import org.drools.journal.api.EmbeddedPayload;
import org.drools.journal.api.ExternalRef;
import org.drools.journal.api.ObjectStorageStrategy;
import org.drools.journal.api.Payload;
import org.drools.journal.api.StorageDecision;

public final class JournalPayloadBuilder {

    private JournalPayloadBuilder() {
    }

    public static Object deserialize(final Payload payload) {
        if (payload instanceof EmbeddedPayload embedded) {
            try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(embedded.bytes()))) {
                return ois.readObject();
            } catch (IOException | ClassNotFoundException e) {
                throw new RuntimeException("Failed to deserialize payload", e);
            }
        }
        throw new UnsupportedOperationException("Cannot deserialize payload type: " + payload.getClass().getName());
    }

    public static EmbeddedPayload embed(final Object object) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(object);
            return new EmbeddedPayload(bos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize fact: " + object.getClass().getName(), e);
        }
    }

    static Payload build(final Object object, final InternalFactHandle handle, final ObjectStorageStrategy strategy) {
        if (strategy.decide(object, handle) == StorageDecision.EXTERNAL_REF) {
            return new ExternalRef(object.getClass().getName(), String.valueOf(handle.getId()));
        }
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(object);
            return new EmbeddedPayload(bos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize fact: " + object.getClass().getName(), e);
        }
    }
}
