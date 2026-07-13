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

import java.nio.file.Path;
import java.util.List;

import net.openhft.chronicle.bytes.MethodReader;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.JournalScanner;
import org.drools.journal.chronicle.internal.CatalogIndex;
import org.drools.journal.chronicle.internal.ChronicleDataRecordHandler;

final class MultiQueueScanner implements JournalScanner {

    private final Path rootDir;
    private final List<String> livePages;
    private final ChronicleDataRecordHandler handler;
    private int currentPageListIndex;
    private String currentPageId;
    private SingleChronicleQueue currentQueue;
    private MethodReader currentReader;
    private JournalRecord buffered;
    private long syntheticPosition;
    private boolean closed;

    MultiQueueScanner(final Path rootDir, final ChronicleDataRecordHandler handler, final List<String> livePages) {
        this.rootDir = rootDir;
        this.livePages = livePages;
        this.handler = handler;
        this.currentPageListIndex = -1;
    }

    static MultiQueueScanner create(final Path rootDir, final SingleChronicleQueue catalogQueue) {
        List<String> livePages = CatalogIndex.build(catalogQueue).livePages();
        MultiQueueScanner scanner = new MultiQueueScanner(rootDir, new ChronicleDataRecordHandler(), livePages);
        scanner.openNextNonEmptyQueue();
        return scanner;
    }

    @Override
    public boolean hasNext() {
        return buffered != null;
    }

    @Override
    public JournalRecord next() {
        JournalRecord result = buffered;
        syntheticPosition++;
        advance();
        return result;
    }

    @Override
    public long position() {
        return syntheticPosition;
    }

    @Override
    public String currentPageId() {
        return currentPageId;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            closeCurrentQueue();
        }
    }

    private void advance() {
        handler.reset();
        if (currentReader != null && currentReader.readOne()) {
            buffered = handler.pending();
        } else {
            closeCurrentQueue();
            openNextNonEmptyQueue();
        }
    }

    private void openNextNonEmptyQueue() {
        buffered = null;
        while (++currentPageListIndex < livePages.size()) {
            currentPageId = livePages.get(currentPageListIndex);
            Path queuePath = rootDir.resolve("page-" + currentPageId);
            if (!queuePath.toFile().exists()) {
                continue;
            }
            currentQueue = SingleChronicleQueueBuilder.binary(queuePath).build();
            var tailer = currentQueue.createTailer();
            tailer.toStart();
            currentReader = tailer.methodReader(handler);
            handler.reset();
            if (currentReader.readOne()) {
                buffered = handler.pending();
                return;
            }
            closeCurrentQueue();
        }
        currentPageId = livePages.isEmpty() ? null : currentPageId;
    }

    private void closeCurrentQueue() {
        if (currentQueue != null) {
            currentQueue.close();
            currentQueue = null;
            currentReader = null;
        }
    }
}
