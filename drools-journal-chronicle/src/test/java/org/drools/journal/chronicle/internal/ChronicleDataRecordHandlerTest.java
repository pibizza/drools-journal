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

import java.nio.file.Path;

import net.openhft.chronicle.bytes.MethodReader;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import org.drools.journal.api.EmbeddedPayload;
import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.RetractRecord;
import org.drools.journal.api.SafepointRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ChronicleDataRecordHandlerTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTrip_insert_capturesInsertRecord() {
        try (SingleChronicleQueue queue = SingleChronicleQueueBuilder.binary(tempDir.resolve("q")).build()) {
            ChronicleDataWriteOps writer = queue.acquireAppender().methodWriter(ChronicleDataWriteOps.class);
            byte[] payload = PayloadCodec.encode(new EmbeddedPayload(new byte[]{1, 2, 3}));
            writer.insert(42L, payload);

            ChronicleDataRecordHandler handler = new ChronicleDataRecordHandler();
            MethodReader reader = queue.createTailer().methodReader(handler);
            assertThat(reader.readOne()).isTrue();
            assertThat(handler.pending()).isInstanceOf(InsertRecord.class);
            InsertRecord ir = (InsertRecord) handler.pending();
            assertThat(ir.factHandleId()).isEqualTo(42L);
            assertThat(ir.logical()).isFalse();
        }
    }

    @Test
    void roundTrip_retract_capturesRetractRecord() {
        try (SingleChronicleQueue queue = SingleChronicleQueueBuilder.binary(tempDir.resolve("q")).build()) {
            ChronicleDataWriteOps writer = queue.acquireAppender().methodWriter(ChronicleDataWriteOps.class);
            writer.retract(99L);

            ChronicleDataRecordHandler handler = new ChronicleDataRecordHandler();
            MethodReader reader = queue.createTailer().methodReader(handler);
            assertThat(reader.readOne()).isTrue();
            assertThat(handler.pending()).isInstanceOf(RetractRecord.class);
            assertThat(((RetractRecord) handler.pending()).factHandleId()).isEqualTo(99L);
        }
    }

    @Test
    void roundTrip_safepoint_capturesSafepointRecord() {
        try (SingleChronicleQueue queue = SingleChronicleQueueBuilder.binary(tempDir.resolve("q")).build()) {
            ChronicleDataWriteOps writer = queue.acquireAppender().methodWriter(ChronicleDataWriteOps.class);
            writer.safepoint(7L, 1234567890L);

            ChronicleDataRecordHandler handler = new ChronicleDataRecordHandler();
            MethodReader reader = queue.createTailer().methodReader(handler);
            assertThat(reader.readOne()).isTrue();
            assertThat(handler.pending()).isInstanceOf(SafepointRecord.class);
            SafepointRecord sr = (SafepointRecord) handler.pending();
            assertThat(sr.sequenceNo()).isEqualTo(7L);
            assertThat(sr.timestamp()).isEqualTo(1234567890L);
        }
    }
}
