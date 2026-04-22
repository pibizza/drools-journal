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

import java.util.Iterator;

/**
 * Forward-only cursor over a sequence of {@link JournalRecord}s.
 *
 * <p>Callers must close the scanner when done to release any underlying
 * resources (file handles, network connections, mapped memory).
 */
public interface JournalScanner extends Iterator<JournalRecord>, AutoCloseable {

    /**
     * Returns the position of the record most recently returned by
     * {@link #next()}, or the starting position if {@link #next()} has not
     * yet been called. The returned value is suitable as {@code fromPosition}
     * in a subsequent {@link JournalStorage#scan(long)} call.
     */
    long position();

    /**
     * Releases any resources held by this scanner.
     */
    @Override
    void close();
}
