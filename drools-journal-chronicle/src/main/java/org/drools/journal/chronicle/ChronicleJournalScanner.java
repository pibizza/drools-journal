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
package org.drools.journal.chronicle;

import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.bytes.MethodReader;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.JournalScanner;
import org.drools.journal.chronicle.internal.ChronicleRecordHandler;

final class ChronicleJournalScanner implements JournalScanner {

    private final ExcerptTailer tailer;
    private final MethodReader reader;
    private final ChronicleRecordHandler handler;
    private long currentPosition;
    private String currentPageId;
    private JournalRecord buffered;
    private long bufferedPosition;
    private String bufferedPageId;
    private boolean closed;

    ChronicleJournalScanner(final ExcerptTailer tailer, final long fromPosition) {
        this.tailer = tailer;
        this.handler = new ChronicleRecordHandler();
        this.reader = tailer.methodReader(handler);
        if (fromPosition == 0) {
            tailer.toStart();
        } else {
            tailer.moveToIndex(fromPosition);
        }
        this.currentPosition = fromPosition;
        advance();
        this.currentPageId = bufferedPageId;
    }

    @Override
    public boolean hasNext() {
        return buffered != null;
    }

    @Override
    public JournalRecord next() {
        final JournalRecord result = buffered;
        currentPosition = bufferedPosition;
        currentPageId = bufferedPageId;
        advance();
        return result;
    }

    @Override
    public long position() {
        return currentPosition;
    }

    @Override
    public String currentPageId() {
        return currentPageId;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            tailer.close();
        }
    }

    private void advance() {
        handler.pending = null;
        final long indexBeforeRead = tailer.index();
        if (reader.readOne()) {
            buffered = handler.pending;
            bufferedPosition = indexBeforeRead;
            bufferedPageId = handler.currentPageId;
        } else {
            buffered = null;
            bufferedPageId = null;
        }
    }
}
