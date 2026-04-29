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
package org.drools.journal.tests;

import java.util.List;
import java.util.NoSuchElementException;

import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.JournalScanner;

/**
 * In-memory {@link JournalScanner} backed by an unmodifiable snapshot of the
 * record list at the time {@link InMemoryJournalStorage#scan(long)} was called.
 *
 * <p>NOT thread-safe — designed for single-threaded test use only.
 */
class InMemoryJournalScanner implements JournalScanner {

    // NOT thread-safe — designed for single-threaded test use only
    private final List<JournalRecord> records;
    private long currentPosition;

    InMemoryJournalScanner(final List<JournalRecord> records, final long fromPosition) {
        this.records = records;
        // clamp to valid range: negative fromPosition starts at the beginning
        this.currentPosition = Math.max(fromPosition, 0);
    }

    @Override
    public boolean hasNext() {
        return currentPosition < records.size();
    }

    @Override
    public JournalRecord next() {
        if (!hasNext()) {
            throw new NoSuchElementException("No more records at position " + currentPosition);
        }
        return records.get((int) currentPosition++);
    }

    @Override
    public long position() {
        // position() returns the index of the record most recently returned by next(),
        // or the starting position if next() has not yet been called
        return currentPosition > 0 ? currentPosition - 1 : currentPosition;
    }

    @Override
    public void close() {
        // no resources to release
    }
}
