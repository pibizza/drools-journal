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


/**
 * SPI for append-only journal storage.
 *
 * <p>Implementations must guarantee:
 * <ul>
 *   <li>Appends are atomic — a partial write is never visible to a reader.</li>
 *   <li>Positions are monotonically increasing {@code long} values that can be
 *       passed directly to {@link #scan(long)} in a subsequent call.</li>
 * </ul>
 */
public interface JournalStorage extends AutoCloseable {

    /**
     * Appends a record to the journal.
     *
     * @param record the journal record to append
     * @return the position assigned to this record, usable as {@code fromPosition}
     *         in a later {@link #scan(long)} call
     */
    long append(JournalRecord record);

    /**
     * Appends a {@link SafepointRecord} with the next sequence number and the
     * current wall-clock time. All records appended before this call are
     * considered durable; records appended after are pending until the next
     * safepoint.
     */
    void safepoint();

    /**
     * Opens a forward-only scanner starting at {@code fromPosition}.
     * The first call to {@link JournalScanner#next()} returns the record at
     * {@code fromPosition}, or the first record after it if that exact position
     * holds no record boundary.
     *
     * @param fromPosition position returned by a previous {@link #append} call,
     *                     or {@code 0} to scan from the beginning
     * @return a scanner positioned at {@code fromPosition}; caller must close it
     */
    JournalScanner scan(long fromPosition);

    /**
     * Returns the position of the most recently appended record, or {@code -1}
     * if the journal is empty.
     */
    long latestPosition();

    /**
     * Releases all resources held by this storage instance.
     */
    @Override
    void close();
}
