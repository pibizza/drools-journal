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
package org.drools.journal.chronicle.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.drools.journal.api.EmbeddedPayload;
import org.drools.journal.api.ExternalRef;
import org.drools.journal.api.Payload;

final class PayloadCodec {

    private static final byte TAG_EMBEDDED = 0;
    private static final byte TAG_EXTERNAL = 1;

    private PayloadCodec() {
    }

    static byte[] encode(final Payload payload) {
        if (payload instanceof EmbeddedPayload ep) {
            final byte[] bytes = ep.bytes();
            final ByteBuffer buf = ByteBuffer.allocate(1 + bytes.length);
            buf.put(TAG_EMBEDDED);
            buf.put(bytes);
            return buf.array();
        }
        if (payload instanceof ExternalRef er) {
            final byte[] typeBytes = er.typeName().getBytes(StandardCharsets.UTF_8);
            final byte[] keyBytes = er.dbKey().getBytes(StandardCharsets.UTF_8);
            final ByteBuffer buf = ByteBuffer.allocate(1 + 4 + typeBytes.length + 4 + keyBytes.length);
            buf.put(TAG_EXTERNAL);
            buf.putInt(typeBytes.length);
            buf.put(typeBytes);
            buf.putInt(keyBytes.length);
            buf.put(keyBytes);
            return buf.array();
        }
        throw new IllegalArgumentException("Unknown Payload type: " + payload.getClass());
    }

    static Payload decode(final byte[] data) {
        final ByteBuffer buf = ByteBuffer.wrap(data);
        final byte tag = buf.get();
        if (tag == TAG_EMBEDDED) {
            final byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            return new EmbeddedPayload(bytes);
        }
        if (tag == TAG_EXTERNAL) {
            final int typeLen = buf.getInt();
            final byte[] typeBytes = new byte[typeLen];
            buf.get(typeBytes);
            final int keyLen = buf.getInt();
            final byte[] keyBytes = new byte[keyLen];
            buf.get(keyBytes);
            return new ExternalRef(
                    new String(typeBytes, StandardCharsets.UTF_8),
                    new String(keyBytes, StandardCharsets.UTF_8));
        }
        throw new IllegalArgumentException("Unknown Payload tag: " + tag);
    }
}
