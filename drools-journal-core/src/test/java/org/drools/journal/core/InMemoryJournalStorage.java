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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.JournalScanner;
import org.drools.journal.api.JournalStorage;

/**
 * Non-durable, in-process {@link JournalStorage} for use in tests.
 *
 * <p>Records are stored in a plain list; positions are zero-based list indices.
 * No files are written and no external dependencies are required.
 *
 * <p>Use {@link #appendRecord(JournalRecord)} to add records directly.
 * {@link #append(ByteBuffer)} is not supported — this implementation has no
 * wire format.
 *
 * <p>NOT thread-safe — designed for single-threaded test use only.
 */
class InMemoryJournalStorage implements JournalStorage {

    // NOT thread-safe — designed for single-threaded test use only
    private final List<JournalRecord> records = new ArrayList<>();
    private boolean closed = false;

    @Override
    public long append(final JournalRecord record) {
        checkOpen();
        records.add(record);
        return records.size() - 1;
    }

    @Override
    public JournalScanner scan(final long fromPosition) {
        checkOpen();
        return new InMemoryJournalScanner(Collections.unmodifiableList(records), fromPosition);
    }

    @Override
    public long latestPosition() {
        checkOpen();
        return records.isEmpty() ? -1 : records.size() - 1;
    }

    @Override
    public void close() {
        closed = true;
    }

    /** Returns the total number of records appended so far. */
    int size() {
        return records.size();
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("InMemoryJournalStorage has been closed");
        }
    }
}
